package com.baek.viewer.controller;

import com.baek.viewer.model.ApmUrlStat;
import com.baek.viewer.repository.ApmUrlStatRepository;
import com.baek.viewer.repository.ApmCallDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * URL 호출현황 집계/조회 API (URL호출현황 화면 전용).
 * 공개 API — 관리자 인증 불필요.
 *
 * 구조:
 *  - /dashboard : apm_url_stat 집계 테이블 기반 대시보드 (6개 기간 preset, 빠름)
 *  - /apis      : apm_call_data 실시간 GROUP BY (임의 기간, 검색/페이징)
 *  - /daily     : 단일 URL 일별 상세
 *  - /monthly   : 단일 URL 월별 상세
 */
@RestController
@RequestMapping("/api/call-stats")
public class CallStatsController {

    private static final Logger log = LoggerFactory.getLogger(CallStatsController.class);
    private final ApmCallDataRepository apmRepo;
    private final ApmUrlStatRepository apmUrlStatRepo;

    public CallStatsController(ApmCallDataRepository apmRepo, ApmUrlStatRepository apmUrlStatRepo) {
        this.apmRepo = apmRepo;
        this.apmUrlStatRepo = apmUrlStatRepo;
    }

    private LocalDate parse(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try { return LocalDate.parse(s); } catch (Exception e) { return def; }
    }

    /**
     * 대시보드 — apm_url_stat 집계 테이블 기반.
     * 6개 기간: yesterday / week / month / 3month / 6month / year
     * 레포 미지정 시 전체 집계.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(@RequestParam(defaultValue = "month") String period,
                                        @RequestParam(required = false) String repo,
                                        @RequestParam(defaultValue = "10") int topN) {
        log.info("[대시보드] 기간={}, repo={}", period, repo);

        List<ApmUrlStat> stats = (repo != null && !repo.isBlank())
                ? apmUrlStatRepo.findByRepositoryName(repo)
                : apmUrlStatRepo.findAll();

        Function<ApmUrlStat, Long> callFn = switch (period) {
            case "yesterday" -> ApmUrlStat::getCallCountYesterday;
            case "week"      -> ApmUrlStat::getCallCountWeek;
            case "3month"    -> ApmUrlStat::getCallCount3Month;
            case "6month"    -> ApmUrlStat::getCallCount6Month;
            case "year"      -> ApmUrlStat::getCallCount;
            default          -> ApmUrlStat::getCallCountMonth; // month
        };
        Function<ApmUrlStat, Long> errFn = switch (period) {
            case "yesterday" -> ApmUrlStat::getErrorCountYesterday;
            case "week"      -> ApmUrlStat::getErrorCountWeek;
            case "3month"    -> ApmUrlStat::getErrorCount3Month;
            case "6month"    -> ApmUrlStat::getErrorCount6Month;
            case "year"      -> ApmUrlStat::getErrorCountYear;
            default          -> ApmUrlStat::getErrorCountMonth; // month
        };

        long totalCall  = stats.stream().mapToLong(callFn::apply).sum();
        long totalError = stats.stream().mapToLong(errFn::apply).sum();

        // 레포별 집계
        Map<String, long[]> repoMap = new LinkedHashMap<>();
        for (ApmUrlStat s : stats) {
            long[] arr = repoMap.computeIfAbsent(s.getRepositoryName(), k -> new long[3]);
            arr[0] += callFn.apply(s);
            arr[1] += errFn.apply(s);
            arr[2]++;
        }
        List<Map<String, Object>> byRepo = repoMap.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("repoName",   e.getKey());
                    m.put("callCount",  e.getValue()[0]);
                    m.put("errorCount", e.getValue()[1]);
                    m.put("apiCount",   e.getValue()[2]);
                    return m;
                }).toList();

        // TOP N
        int limit = Math.max(1, Math.min(topN, 100));
        List<Map<String, Object>> topApis = stats.stream()
                .filter(s -> callFn.apply(s) > 0)
                .sorted((a, b) -> Long.compare(callFn.apply(b), callFn.apply(a)))
                .limit(limit)
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("repoName",   s.getRepositoryName());
                    m.put("apiPath",    s.getApiPath());
                    m.put("callCount",  callFn.apply(s));
                    m.put("errorCount", errFn.apply(s));
                    return m;
                }).toList();

        // 집계 기준 시각 (최신 updatedAt)
        LocalDateTime updatedAt = stats.stream()
                .map(ApmUrlStat::getUpdatedAt)
                .filter(t -> t != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period",         period);
        result.put("updatedAt",      updatedAt != null ? updatedAt.toString() : null);
        result.put("totalCallCount", totalCall);
        result.put("totalErrorCount",totalError);
        result.put("byRepo",         byRepo);
        result.put("topApis",        topApis);
        return ResponseEntity.ok(result);
    }

    /**
     * URL별 집계 목록 (페이징/검색) — apm_call_data 실시간 GROUP BY.
     * ?from=&to=&repo=&q=&page=&size=
     * repo 미지정 시 전체 레포 조회 (q 없이 전체 조회는 느릴 수 있음 — 프론트에서 경고).
     */
    @GetMapping("/apis")
    public ResponseEntity<?> apis(@RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) String repo,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "200") int size) {
        LocalDate toDate   = parse(to,   LocalDate.now());
        LocalDate fromDate = parse(from,  toDate.minusDays(30));
        String repoArg = (repo != null && !repo.isBlank()) ? repo : null;
        String qArg    = (q    != null && !q.isBlank())    ? q    : null;
        int pageNum  = Math.max(0, page);
        int pageSize = Math.min(500, Math.max(1, size));
        log.info("[URL 조회] from={}, to={}, repo={}, q={}, page={}", fromDate, toDate, repoArg, qArg, pageNum);

        Pageable pageable = PageRequest.of(pageNum, pageSize);
        Page<Object[]> pageResult = apmRepo.aggregateByPeriod(fromDate, toDate, repoArg, qArg, pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] row : pageResult.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repoName",   row[0]);
            m.put("apiPath",    row[1]);
            m.put("totalCall",  ((Number) row[2]).longValue());
            m.put("totalError", ((Number) row[3]).longValue());
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from",          fromDate.toString());
        result.put("to",            toDate.toString());
        result.put("items",         items);
        result.put("page",          pageResult.getNumber());
        result.put("size",          pageResult.getSize());
        result.put("totalElements", pageResult.getTotalElements());
        result.put("totalPages",    pageResult.getTotalPages());
        return ResponseEntity.ok(result);
    }

    /** 단일 URL의 일별 세부 데이터 */
    @GetMapping("/daily")
    public ResponseEntity<?> daily(@RequestParam String repo,
                                    @RequestParam String apiPath,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        LocalDate toDate   = parse(to,   LocalDate.now());
        LocalDate fromDate = parse(from,  toDate.minusDays(29));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : apmRepo.dailyByApi(repo, apiPath, fromDate, toDate)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("callDate",   row[0].toString());
            m.put("source",     row[1]);
            m.put("callCount",  ((Number) row[2]).longValue());
            m.put("errorCount", ((Number) row[3]).longValue());
            rows.add(m);
        }
        return ResponseEntity.ok(Map.of("repoName", repo, "apiPath", apiPath, "rows", rows));
    }

    /** 단일 URL의 월별 세부 데이터 */
    @GetMapping("/monthly")
    public ResponseEntity<?> monthly(@RequestParam String repo,
                                      @RequestParam String apiPath,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        LocalDate toDate   = parse(to,   LocalDate.now());
        LocalDate fromDate = parse(from,  toDate.minusMonths(12));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : apmRepo.monthlyByApi(repo, apiPath, fromDate, toDate)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month",      row[0]);
            m.put("source",     row[1]);
            m.put("callCount",  ((Number) row[2]).longValue());
            m.put("errorCount", ((Number) row[3]).longValue());
            rows.add(m);
        }
        return ResponseEntity.ok(Map.of("repoName", repo, "apiPath", apiPath, "rows", rows));
    }
}
