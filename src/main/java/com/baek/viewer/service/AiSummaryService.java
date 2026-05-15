package com.baek.viewer.service;

import com.baek.viewer.ai.InternalOpenAiCompatibleClient;
import com.baek.viewer.model.ApiRecordStatsDto;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.ScheduleConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LOCAi 추천 메시지 — 주기적 생성 및 캐시 관리.
 * AI_SUMMARY 배치가 스케줄에 따라 generateAndCache() 를 호출한다.
 * 대시보드는 GET /api/ai/dashboard-summary/cached 로 캐시된 메시지를 조회한다.
 */
@Service
public class AiSummaryService {

    private static final Logger log = LoggerFactory.getLogger(AiSummaryService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ApiRecordRepository apiRecordRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final ScheduleConfigRepository scheduleConfigRepository;
    private final DbMonitorService dbMonitorService;
    private final GlobalConfigRepository globalConfigRepository;
    private final InternalOpenAiCompatibleClient aiClient;

    public AiSummaryService(ApiRecordRepository apiRecordRepository,
                            RepoConfigRepository repoConfigRepository,
                            ScheduleConfigRepository scheduleConfigRepository,
                            DbMonitorService dbMonitorService,
                            GlobalConfigRepository globalConfigRepository,
                            InternalOpenAiCompatibleClient aiClient) {
        this.apiRecordRepository = apiRecordRepository;
        this.repoConfigRepository = repoConfigRepository;
        this.scheduleConfigRepository = scheduleConfigRepository;
        this.dbMonitorService = dbMonitorService;
        this.globalConfigRepository = globalConfigRepository;
        this.aiClient = aiClient;
    }

    /** AI_SUMMARY 배치에서 호출 — 통계 수집 후 AI 요약 생성 및 GlobalConfig 에 저장 */
    @Transactional
    public String generateAndCache() {
        GlobalConfig gc = globalConfigRepository.findById(1L).orElse(null);
        if (gc == null || gc.getAiOpenApiBaseUrl() == null || gc.getAiOpenApiBaseUrl().isBlank()) {
            log.info("[AI_SUMMARY] AI 미설정, 건너뜀");
            return null;
        }

        String content = buildContent();
        if (content.isBlank()) {
            log.warn("[AI_SUMMARY] 수집된 컨텐츠 없음, 건너뜀");
            return null;
        }

        String prompt = content + "\n\n위 내용 기준으로 현황과 주요 이슈를 5줄 이내로 요약해.";
        try {
            String summary = aiClient.chatCompletion(gc, prompt);
            if (summary == null) summary = "";

            gc.setAiDashboardSummary(summary);
            gc.setAiDashboardSummaryAt(LocalDateTime.now());
            globalConfigRepository.save(gc);
            log.info("[AI_SUMMARY] 요약 생성 완료 ({} chars)", summary.length());
            return summary;
        } catch (Exception e) {
            log.warn("[AI_SUMMARY] AI 호출 실패: {}", e.getMessage());
            throw e;
        }
    }

    /** 캐시된 요약 반환 (summary, generatedAt, configured, dashboardEnabled) */
    public Map<String, Object> getCached() {
        GlobalConfig gc = globalConfigRepository.findById(1L).orElse(null);
        boolean dashboardEnabled = gc == null || gc.isAiDashboardEnabled();
        boolean configured = dashboardEnabled
                && gc != null
                && gc.getAiOpenApiBaseUrl() != null
                && !gc.getAiOpenApiBaseUrl().isBlank();
        String summary = gc != null ? gc.getAiDashboardSummary() : null;
        String generatedAt = (gc != null && gc.getAiDashboardSummaryAt() != null)
                ? gc.getAiDashboardSummaryAt().format(DT_FMT) : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured);
        result.put("dashboardEnabled", dashboardEnabled);
        result.put("summary", summary != null ? summary : "");
        result.put("generatedAt", generatedAt);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부: 컨텐츠 구성
    // ─────────────────────────────────────────────────────────────────────────

    private String buildContent() {
        List<String> parts = new ArrayList<>();

        // 1. URL 분석 요약
        try {
            parts.add(buildUrlStatsPart());
        } catch (Exception e) {
            log.warn("[AI_SUMMARY] URL 통계 수집 실패: {}", e.getMessage());
        }

        // 2. 팀별 현황
        try {
            parts.add(buildTeamStatsPart());
        } catch (Exception e) {
            log.warn("[AI_SUMMARY] 팀별 통계 수집 실패: {}", e.getMessage());
        }

        // 3. 배치 수행 결과
        try {
            parts.add(buildBatchResultPart());
        } catch (Exception e) {
            log.warn("[AI_SUMMARY] 배치 결과 수집 실패: {}", e.getMessage());
        }

        // 4. DB 현황 (서버사이드 배치는 항상 포함)
        try {
            parts.add(buildDbCurrentPart());
        } catch (Exception e) {
            log.warn("[AI_SUMMARY] DB 현황 수집 실패: {}", e.getMessage());
        }

        // 5. DB 증가 추이
        try {
            parts.add(buildDbHistoryPart());
        } catch (Exception e) {
            log.warn("[AI_SUMMARY] DB 추이 수집 실패: {}", e.getMessage());
        }

        return parts.stream().filter(s -> !s.isBlank()).collect(Collectors.joining("\n\n"));
    }

    private String buildUrlStatsPart() {
        List<ApiRecordStatsDto> all = apiRecordRepository.findAllForStats();
        long deleted = apiRecordRepository.countByStatus("삭제");
        long total = all.size();

        Map<String, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getStatus() != null ? r.getStatus() : "사용",
                        Collectors.counting()));

        long use        = byStatus.getOrDefault("사용", 0L);
        long blockDone  = byStatus.getOrDefault("차단완료", 0L);
        long block1     = byStatus.getOrDefault("①-① 차단대상", 0L);
        long block2     = byStatus.getOrDefault("①-② 담당자 판단", 0L);
        long block3     = byStatus.getOrDefault("①-③ 현업요청 제외대상", 0L) + byStatus.getOrDefault("①-④ 사용으로 변경", 0L);
        long review     = byStatus.getOrDefault("②-① 호출0건+변경있음", 0L)
                        + byStatus.getOrDefault("②-② 호출 3건 이하+변경없음", 0L)
                        + byStatus.getOrDefault("②-③ 사용으로 변경", 0L);

        return "[URL 분석 요약]\n" +
                String.format("전체: %,d건 (삭제 제외) | 삭제이력: %,d건\n", total, deleted) +
                String.format("사용: %,d건(%s) | 차단완료: %,d건(%s)\n",
                        use, pct(use, total), blockDone, pct(blockDone, total)) +
                String.format("차단대상(잔여): %,d건(%s) | 차단대상(담당자판단): %,d건 | 차단대상(제외): %,d건\n",
                        block1, pct(block1, total), block2, block3) +
                String.format("검토대상: %,d건(%s)", review, pct(review, total));
    }

    private String buildTeamStatsPart() {
        List<ApiRecordStatsDto> all = apiRecordRepository.findAllForStats();
        var repoConfigMap = repoConfigRepository.findAll().stream()
                .collect(Collectors.toMap(
                        com.baek.viewer.model.RepoConfig::getRepoName,
                        r -> r, (a, b) -> a));

        Map<String, Long> byTeam = new LinkedHashMap<>();
        Map<String, Long> blockByTeam = new LinkedHashMap<>();
        all.forEach(r -> {
            String team = effectiveTeam(r, repoConfigMap);
            byTeam.merge(team, 1L, Long::sum);
            if ("①-① 차단대상".equals(r.getStatus())) {
                blockByTeam.merge(team, 1L, Long::sum);
            }
        });

        StringBuilder sb = new StringBuilder("[URL 현황 — 팀별]\n");
        byTeam.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    long block = blockByTeam.getOrDefault(e.getKey(), 0L);
                    sb.append(String.format("  %s: %,d건 (차단대상 %,d건)\n", e.getKey(), e.getValue(), block));
                });
        return sb.toString().stripTrailing();
    }

    private String buildBatchResultPart() {
        List<ScheduleConfig> schedules = scheduleConfigRepository.findAll();
        StringBuilder sb = new StringBuilder("[시스템 배치 수행 결과]\n");
        schedules.stream()
                .filter(s -> s.getLastRunAt() != null)
                .sorted(Comparator.comparing(ScheduleConfig::getLastRunAt).reversed())
                .limit(6)
                .forEach(s -> sb.append(String.format("  %s: %s — %s\n",
                        s.getDescription() != null ? s.getDescription() : s.getJobType(),
                        s.getLastRunAt().format(DT_FMT),
                        s.getLastRunResult() != null ? s.getLastRunResult() : "-")));
        return sb.toString().stripTrailing();
    }

    private String buildDbCurrentPart() {
        Map<String, Object> cur = dbMonitorService.getCurrent();
        long dbSize  = toLong(cur.get("dbSizeBytes"));
        long usable  = toLong(cur.get("diskUsableBytes"));
        Object pct   = cur.get("diskUsedPct");
        long apiRec  = toLong(cur.get("apiRecordCount"));
        long apmCnt  = toLong(cur.get("apmCallDataCount"));
        long growB   = toLong(cur.get("todayGrowthBytes"));
        long growApm = toLong(cur.get("todayGrowthApm"));
        return "[DB 현황]\n" +
                String.format("DB 종류: %s, DB 크기: %.1fMB, 디스크 사용: %s%% (가용: %.1fMB)\n",
                        cur.getOrDefault("dbType", "-"),
                        dbSize / 1024.0 / 1024.0,
                        pct != null ? pct : "-",
                        usable / 1024.0 / 1024.0) +
                String.format("API 레코드: %,d건, APM 호출: %,d건, 오늘 DB 증가: %.2fMB, APM 증가: %,d건",
                        apiRec, apmCnt, growB / 1024.0 / 1024.0, growApm);
    }

    private String buildDbHistoryPart() {
        List<Map<String, Object>> history = dbMonitorService.getHistory(30);
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[DB 증가 추이 (최근 7일)]\n");
        history.stream()
                .skip(Math.max(0, history.size() - 7))
                .forEach(r -> sb.append(String.format("  %s: DB %.1fMB (+%.2fMB), APM %,d건 (+%,d건)\n",
                        r.get("date"),
                        toLong(r.get("dbSizeBytes")) / 1024.0 / 1024.0,
                        toLong(r.get("deltaBytes")) / 1024.0 / 1024.0,
                        toLong(r.get("apmCallDataCount")),
                        toLong(r.get("deltaApm")))));
        return sb.toString().stripTrailing();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String effectiveTeam(ApiRecordStatsDto r, Map<String, com.baek.viewer.model.RepoConfig> cfgMap) {
        if (r.getTeamOverride() != null && !r.getTeamOverride().isBlank()) return r.getTeamOverride();
        com.baek.viewer.model.RepoConfig c = cfgMap.get(r.getRepositoryName());
        return (c != null && c.getTeamName() != null && !c.getTeamName().isBlank()) ? c.getTeamName() : "(팀 미지정)";
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private String pct(long n, long total) {
        return total > 0 ? String.format("%.1f%%", n * 100.0 / total) : "0%";
    }
}
