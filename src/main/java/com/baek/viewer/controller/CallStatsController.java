package com.baek.viewer.controller;

import com.baek.viewer.repository.ApmCallDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * URL 호출현황 집계/조회 API (URL호출현황 화면 전용).
 * 공개 API — 관리자 인증 불필요.
 */
@RestController
@RequestMapping("/api/call-stats")
public class CallStatsController {

    private static final Logger log = LoggerFactory.getLogger(CallStatsController.class);
    private final ApmCallDataRepository apmRepo;

    public CallStatsController(ApmCallDataRepository apmRepo) {
        this.apmRepo = apmRepo;
    }

    private LocalDate parse(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try { return LocalDate.parse(s); } catch (Exception e) { return def; }
    }

    /** 전체 요약 — source별 총계, 레포별 총계, TOP N (기본 1년) */
    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String repo,
                                      @RequestParam(defaultValue = "10") int topN) {
        LocalDate toDate = parse(to, LocalDate.now().minusDays(1));
        LocalDate fromDate = parse(from, toDate.minusDays(364));  // 기본 최근 1년
        log.info("[호출현황 요약] from={}, to={}, repo={}", fromDate, toDate, repo);

        // source별 요약 (repo 지정은 summaryByRepo에서만 적용. source 요약은 전체 기준)
        List<Map<String, Object>> bySource = new ArrayList<>();
        long totalCall = 0, totalError = 0;
        Set<String> allApis = new HashSet<>();
        for (Object[] row : apmRepo.summaryBySource(fromDate, toDate)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", row[0]);
            long call = ((Number) row[1]).longValue();
            long err  = ((Number) row[2]).longValue();
            m.put("callCount", call);
            m.put("errorCount", err);
            m.put("apiCount", ((Number) row[3]).longValue());
            bySource.add(m);
            totalCall += call;
            totalError += err;
        }

        // 레포별 요약
        List<Map<String, Object>> byRepo = new ArrayList<>();
        for (Object[] row : apmRepo.summaryByRepo(fromDate, toDate)) {
            String repoName = (String) row[0];
            if (repo != null && !repo.isBlank() && !repo.equals(repoName)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repoName", repoName);
            m.put("callCount", ((Number) row[1]).longValue());
            m.put("errorCount", ((Number) row[2]).longValue());
            m.put("apiCount", ((Number) row[3]).longValue());
            byRepo.add(m);
        }

        // TOP N
        List<Map<String, Object>> topApis = new ArrayList<>();
        PageRequest topReq = PageRequest.of(0, Math.max(1, topN));
        for (Object[] row : apmRepo.topApis(fromDate, toDate,
                (repo != null && !repo.isBlank()) ? repo : null, topReq)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repoName", row[0]);
            m.put("apiPath", row[1]);
            m.put("callCount", ((Number) row[2]).longValue());
            m.put("errorCount", ((Number) row[3]).longValue());
            topApis.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", fromDate.toString());
        result.put("to", toDate.toString());
        result.put("totalCallCount", totalCall);
        result.put("totalErrorCount", totalError);
        result.put("bySource", bySource);
        result.put("byRepo", byRepo);
        result.put("topApis", topApis);
        return ResponseEntity.ok(result);
    }

    /**
     * URL별 집계 목록 (페이징/정렬/검색) — 4가지 기간 컬럼 반환.
     * ?repo=&q=&page=&size=&sort=totalYear&dir=desc
     * 컬럼: totalAll(전체) / totalYear(1년) / totalMonth(1달) / totalWeek(1주) / totalError(전체 에러)
     */
    @GetMapping("/apis")
    public ResponseEntity<?> apis(@RequestParam(required = false) String repo,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size,
                                   @RequestParam(defaultValue = "totalYear") String sort,
                                   @RequestParam(defaultValue = "desc") String dir) {
        LocalDate today = LocalDate.now();
        LocalDate yearAgo = today.minusDays(364);
        LocalDate monthAgo = today.minusDays(29);
        LocalDate weekAgo = today.minusDays(6);

        org.springframework.data.domain.Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));

        Page<Object[]> pageResult = apmRepo.aggregatePaged(
                yearAgo, monthAgo, weekAgo,
                (repo != null && !repo.isBlank()) ? repo : null,
                (q != null && !q.isBlank()) ? q : null,
                pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] row : pageResult.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repoName", row[0]);
            m.put("apiPath", row[1]);
            m.put("totalAll",   ((Number) row[2]).longValue());
            m.put("totalError", ((Number) row[3]).longValue());
            m.put("totalYear",  ((Number) row[4]).longValue());
            m.put("totalMonth", ((Number) row[5]).longValue());
            m.put("totalWeek",  ((Number) row[6]).longValue());
            items.add(m);
        }

        // 현재 페이지 내 정렬 적용
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        items.sort((a, b) -> {
            int mul = direction == Sort.Direction.ASC ? 1 : -1;
            if ("apiPath".equals(sort)) {
                return ((String) a.get("apiPath")).compareTo((String) b.get("apiPath")) * mul;
            }
            long av = a.get(sort) instanceof Number ? ((Number) a.get(sort)).longValue() : 0;
            long bv = b.get(sort) instanceof Number ? ((Number) b.get(sort)).longValue() : 0;
            return Long.compare(av, bv) * mul;
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", pageResult.getNumber());
        result.put("size", pageResult.getSize());
        result.put("totalElements", pageResult.getTotalElements());
        result.put("totalPages", pageResult.getTotalPages());
        return ResponseEntity.ok(result);
    }

    /** 단일 URL의 일별 세부 데이터 */
    @GetMapping("/daily")
    public ResponseEntity<?> daily(@RequestParam String repo,
                                    @RequestParam String apiPath,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        LocalDate toDate = parse(to, LocalDate.now().minusDays(1));
        LocalDate fromDate = parse(from, toDate.minusDays(29));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : apmRepo.dailyByApi(repo, apiPath, fromDate, toDate)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("callDate", row[0].toString());
            m.put("source", row[1]);
            m.put("callCount", ((Number) row[2]).longValue());
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
        LocalDate toDate = parse(to, LocalDate.now().minusDays(1));
        LocalDate fromDate = parse(from, toDate.minusMonths(12));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : apmRepo.monthlyByApi(repo, apiPath, fromDate, toDate)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", row[0]);
            m.put("source", row[1]);
            m.put("callCount", ((Number) row[2]).longValue());
            m.put("errorCount", ((Number) row[3]).longValue());
            rows.add(m);
        }
        return ResponseEntity.ok(Map.of("repoName", repo, "apiPath", apiPath, "rows", rows));
    }
}
