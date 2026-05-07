package com.baek.viewer.service;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.ApmCallDataRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * APM ↔ URL분석현황(api_record) 매칭 진단 리포트 생성기.
 *
 * 목적:
 * - APM(apm_call_data)에는 호출 URL이 있는데 api_record에 매칭되지 않아 callCount가 0으로 보이거나,
 *   상태 판단이 어긋나는 문제를 빠르게 탐지한다.
 *
 * 설계:
 * - 큰 데이터는 저장하지 않고, 요약 + 레포별 top 미매칭 샘플만 저장(GlobalConfig.apmMatchReport).
 * - 조회 시점에서 재생성(refresh)도 가능.
 */
@Service
public class ApmMatchReportService {

    private static final Logger log = LoggerFactory.getLogger(ApmMatchReportService.class);
    private static final ObjectMapper om = new ObjectMapper();

    private final ApmCallDataRepository apmRepo;
    private final ApiRecordRepository apiRepo;
    private final GlobalConfigRepository globalRepo;

    public ApmMatchReportService(ApmCallDataRepository apmRepo,
                                 ApiRecordRepository apiRepo,
                                 GlobalConfigRepository globalRepo) {
        this.apmRepo = apmRepo;
        this.apiRepo = apiRepo;
        this.globalRepo = globalRepo;
    }

    /** 저장된 리포트 조회 (없으면 빈 리포트). */
    public Map<String, Object> getStoredReport() {
        GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
        String json = gc.getApmMatchReport();
        if (json == null || json.isBlank()) {
            return Map.of(
                    "generatedAt", "",
                    "periodDays", 365,
                    "repoCount", 0,
                    "mismatchRepoCount", 0,
                    "totalMismatchPaths", 0,
                    "repos", List.of()
            );
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = om.readValue(json, Map.class);
            // generatedAt는 컬럼 값을 우선 사용 (JSON이 오래된 형식이어도 표시 안정)
            if (gc.getApmMatchReportAt() != null) {
                parsed.put("generatedAt", gc.getApmMatchReportAt().toString());
            }
            return parsed;
        } catch (Exception e) {
            log.warn("[APM-MATCH] 저장된 리포트 파싱 실패: {}", e.getMessage());
            return Map.of(
                    "generatedAt", gc.getApmMatchReportAt() != null ? gc.getApmMatchReportAt().toString() : "",
                    "periodDays", 365,
                    "repoCount", 0,
                    "mismatchRepoCount", 0,
                    "totalMismatchPaths", 0,
                    "repos", List.of(),
                    "error", "stored_report_parse_failed"
            );
        }
    }

    /**
     * 리포트 생성 후 GlobalConfig에 저장.
     * @param days 진단 기간(기본 365)
     * @param perRepoTopN 레포별 미매칭 샘플 개수 (기본 30)
     */
    @Transactional
    public Map<String, Object> regenerateAndStore(int days, int perRepoTopN) {
        if (days <= 0) days = 365;
        if (perRepoTopN <= 0) perRepoTopN = 30;
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);

        List<String> repos = apiRepo.findAllRepositoryNames(); // 분석된 레포 기준
        List<Map<String, Object>> repoReports = new ArrayList<>();

        int mismatchRepoCount = 0;
        long totalMismatchPaths = 0;

        for (String repoName : repos) {
            if (repoName == null || repoName.isBlank()) continue;

            // api_record 쪽 경로 Set
            Set<String> analysisPaths = new HashSet<>();
            apiRepo.findByRepositoryName(repoName).forEach(r -> {
                if (r.getApiPath() != null && !r.getApiPath().isBlank()) analysisPaths.add(r.getApiPath());
            });

            // apm_call_data distinct 경로
            List<String> apmPaths = apmRepo.distinctApiPathsByRepo(repoName, from, today);
            if (apmPaths.isEmpty()) continue; // APM 데이터 없는 레포는 리포트 제외 (잡음 최소화)

            List<String> mismatches = new ArrayList<>();
            for (String p : apmPaths) {
                if (p == null || p.isBlank()) continue;
                if (!analysisPaths.contains(p)) mismatches.add(p);
            }

            if (!mismatches.isEmpty()) {
                mismatchRepoCount++;
                totalMismatchPaths += mismatches.size();
            }

            // 호출수 높은 미매칭 상위 N개 샘플
            List<Map<String, Object>> top = new ArrayList<>();
            try {
                // topApis는 기간 from~to 조건이므로 재사용
                var rows = apmRepo.topApis(from, today, repoName, PageRequest.of(0, Math.max(200, perRepoTopN * 3)));
                for (Object[] row : rows) {
                    String apiPath = row[1] != null ? String.valueOf(row[1]) : null;
                    if (apiPath == null || apiPath.isBlank()) continue;
                    if (analysisPaths.contains(apiPath)) continue;
                    long call = row[2] instanceof Number n ? n.longValue() : 0L;
                    long err = row[3] instanceof Number n ? n.longValue() : 0L;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("apiPath", apiPath);
                    m.put("callCount", call);
                    m.put("errorCount", err);
                    top.add(m);
                    if (top.size() >= perRepoTopN) break;
                }
            } catch (Exception e) {
                log.warn("[APM-MATCH] top mismatch sample 실패 repo={}: {}", repoName, e.getMessage());
            }

            Map<String, Object> rr = new LinkedHashMap<>();
            rr.put("repoName", repoName);
            rr.put("analysisApiCount", analysisPaths.size());
            rr.put("apmApiCount", apmPaths.size());
            rr.put("mismatchCount", mismatches.size());
            rr.put("matchRate", analysisPaths.isEmpty() ? 0.0
                    : (double) (apmPaths.size() - mismatches.size()) / (double) apmPaths.size());
            rr.put("topMismatches", top);
            repoReports.add(rr);
        }

        // mismatchCount desc, apmApiCount desc
        repoReports.sort((a, b) -> {
            int ma = ((Number) a.getOrDefault("mismatchCount", 0)).intValue();
            int mb = ((Number) b.getOrDefault("mismatchCount", 0)).intValue();
            if (ma != mb) return Integer.compare(mb, ma);
            int aa = ((Number) a.getOrDefault("apmApiCount", 0)).intValue();
            int ab = ((Number) b.getOrDefault("apmApiCount", 0)).intValue();
            return Integer.compare(ab, aa);
        });

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", now.toString());
        report.put("periodDays", days);
        report.put("repoCount", repos.size());
        report.put("mismatchRepoCount", mismatchRepoCount);
        report.put("totalMismatchPaths", totalMismatchPaths);
        report.put("repos", repoReports);

        try {
            String json = om.writeValueAsString(report);
            GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
            gc.setApmMatchReport(json);
            gc.setApmMatchReportAt(now);
            globalRepo.save(gc);
        } catch (Exception e) {
            log.warn("[APM-MATCH] 리포트 저장 실패: {}", e.getMessage());
        }

        log.info("[APM-MATCH] 리포트 생성 완료 days={} repoReports={} mismatchRepos={} totalMismatchPaths={}",
                days, repoReports.size(), mismatchRepoCount, totalMismatchPaths);
        return report;
    }
}

