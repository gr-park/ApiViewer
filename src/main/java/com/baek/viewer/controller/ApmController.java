package com.baek.viewer.controller;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.ApmCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/apm")
public class ApmController {

    private static final Logger log = LoggerFactory.getLogger(ApmController.class);
    private final ApmCollectionService apmCollectionService;
    private final RepoConfigRepository repoConfigRepository;
    private final com.baek.viewer.service.ApmArchiveService apmArchiveService;

    public ApmController(ApmCollectionService apmCollectionService,
                         RepoConfigRepository repoConfigRepository,
                         com.baek.viewer.service.ApmArchiveService apmArchiveService) {
        this.apmCollectionService = apmCollectionService;
        this.repoConfigRepository = repoConfigRepository;
        this.apmArchiveService = apmArchiveService;
    }

    /** 1년 이상 지난 호출이력 CSV 백업 + 원본 삭제 */
    @PostMapping("/archive")
    public ResponseEntity<?> archive(@RequestBody(required = false) java.util.Map<String, Object> body) {
        int keepDays = 365;
        boolean dryRun = false;
        if (body != null) {
            if (body.get("keepDays") instanceof Number n) keepDays = n.intValue();
            dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        }
        try {
            return ResponseEntity.ok(apmArchiveService.archive(keepDays, dryRun));
        } catch (Exception e) {
            log.error("[APM 아카이브 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** 아카이브 파일 목록 */
    @GetMapping("/archive/list")
    public ResponseEntity<?> archiveList() {
        try {
            return ResponseEntity.ok(apmArchiveService.listArchives());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** APM 수집 실시간 로그 조회 */
    @GetMapping("/logs")
    public ResponseEntity<?> getApmLogs(@RequestParam(defaultValue = "0") int from) {
        var logs = apmCollectionService.getApmLogs();
        int total = logs.size();
        var slice = from < total ? logs.subList(from, total) : List.of();
        return ResponseEntity.ok(Map.of("logs", slice, "total", total, "collecting", apmCollectionService.isApmCollecting()));
    }

    /** APM 수집 로그 초기화 */
    @DeleteMapping("/logs")
    public ResponseEntity<?> clearApmLogs() {
        apmCollectionService.getApmLogs().clear();
        return ResponseEntity.ok(Map.of("cleared", true));
    }

    /** Mock 데이터 생성 (source: MOCK/WHATAP/JENNIFER, 기본 MOCK) */
    @PostMapping("/mock/generate")
    public ResponseEntity<?> generateMock(@RequestBody Map<String, Object> body) {
        String repoName = (String) body.get("repoName");
        int days = body.containsKey("days") ? ((Number) body.get("days")).intValue() : 90;
        String source = body.get("source") != null ? body.get("source").toString() : "MOCK";
        if (repoName == null || repoName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "repoName 필수"));
        log.info("[APM Mock] 생성 요청: repo={}, days={}, source={}", repoName, days, source);
        return ResponseEntity.ok(apmCollectionService.generateMockData(repoName, days, source));
    }

    /**
     * 날짜 범위 지정 수동 수집 (와탭/제니퍼).
     * Body: { repoName, source, from (YYYY-MM-DD), to (YYYY-MM-DD) }
     * 제약: JENNIFER 최대 30일, WHATAP 최대 365일.
     */
    @PostMapping("/collect")
    public ResponseEntity<?> collectByRange(@RequestBody Map<String, Object> body) {
        try {
            String repoName = (String) body.get("repoName");
            String source = body.get("source") != null ? body.get("source").toString() : "MOCK";
            String from = (String) body.get("from");
            String to = (String) body.get("to");
            if (repoName == null || repoName.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "repoName 필수"));
            if (from == null || to == null)
                return ResponseEntity.badRequest().body(Map.of("error", "from/to 날짜 필수"));
            log.info("[APM 수동수집 요청] repo={}, source={}, {}~{}", repoName, source, from, to);
            apmCollectionService.getApmLogs().clear();
            return ResponseEntity.ok(apmCollectionService.generateMockDataByRange(
                    repoName, LocalDate.parse(from), LocalDate.parse(to), source));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 전체 레포 자동 수집. Body: { mockOnly: true/false }
     * mockOnly=true면 모든 레포에 MOCK으로, 아니면 레포설정의 whatap/jennifer 활성화 기준.
     */
    @PostMapping("/collect-all")
    public ResponseEntity<?> collectAll(@RequestBody(required = false) Map<String, Object> body) {
        boolean mockOnly = body != null && Boolean.TRUE.equals(body.get("mockOnly"));
        log.info("[APM 전체수집 요청] mockOnly={}", mockOnly);
        var repos = repoConfigRepository.findAll();
        return ResponseEntity.ok(apmCollectionService.collectAll(repos, mockOnly));
    }

    /**
     * 차트용 데이터 조회 — 단일 API의 기간별 호출건수.
     * ?repoName=..&apiPath=..&bucket=daily|weekly&days=30|90|365
     */
    @GetMapping("/chart")
    public ResponseEntity<?> getChart(@RequestParam String repoName,
                                       @RequestParam String apiPath,
                                       @RequestParam(defaultValue = "daily") String bucket,
                                       @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(apmCollectionService.getChartData(repoName, apiPath, bucket, days));
    }

    /** APM 데이터 → ApiRecord 호출건수 집계 반영 */
    @PostMapping("/aggregate")
    public ResponseEntity<?> aggregate(@RequestBody Map<String, String> body) {
        String repoName = body.get("repoName");
        if (repoName == null || repoName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "repoName 필수"));
        log.info("[APM 집계] 요청: repo={}", repoName);
        return ResponseEntity.ok(apmCollectionService.aggregateToRecords(repoName));
    }

    /** APM 일별 호출 데이터 조회 (source 필터: MOCK/WHATAP/JENNIFER/ALL) */
    @GetMapping("/data")
    public ResponseEntity<?> getData(@RequestParam String repoName,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String source) {
        LocalDate fromDate = from != null ? LocalDate.parse(from) : LocalDate.now().minusDays(30);
        LocalDate toDate = to != null ? LocalDate.parse(to) : LocalDate.now();
        List<ApmCallData> data = apmCollectionService.getCallData(repoName, fromDate, toDate, source);

        // API별 합계도 함께 반환
        Map<String, long[]> summary = new java.util.LinkedHashMap<>();
        for (ApmCallData d : data) {
            long[] arr = summary.computeIfAbsent(d.getApiPath(), k -> new long[2]);
            arr[0] += d.getCallCount();
            arr[1] += d.getErrorCount();
        }
        List<Map<String, Object>> apiSummary = summary.entrySet().stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("apiPath", e.getKey());
            m.put("totalCalls", e.getValue()[0]);
            m.put("totalErrors", e.getValue()[1]);
            return m;
        }).sorted((a, b) -> Long.compare((long) b.get("totalCalls"), (long) a.get("totalCalls")))
        .toList();

        return ResponseEntity.ok(Map.of(
            "data", data,
            "summary", apiSummary,
            "count", data.size(),
            "period", Map.of("from", fromDate.toString(), "to", toDate.toString())
        ));
    }

    /** APM 데이터 삭제 (?source=MOCK/WHATAP/JENNIFER/ALL, 기본 ALL) */
    @DeleteMapping("/mock/{repoName}")
    public ResponseEntity<?> deleteMock(@PathVariable String repoName,
                                        @RequestParam(required = false) String source) {
        log.info("[APM 데이터 삭제 요청] repo={}, source={}", repoName, source);
        return ResponseEntity.ok(apmCollectionService.deleteMockData(repoName, source));
    }

    /**
     * APM 호출이력 일괄 삭제 (MOCK 포함).
     * ?repoName=ALL이면 전체 레포 / ?source=ALL이면 모든 source.
     */
    @DeleteMapping("/data")
    public ResponseEntity<?> deleteCallData(@RequestParam(required = false, defaultValue = "ALL") String repoName,
                                            @RequestParam(required = false, defaultValue = "ALL") String source) {
        log.warn("[APM 호출이력 삭제] repo={}, source={}", repoName, source);
        var result = apmCollectionService.deleteMockData(repoName, source);
        // 호출이력 삭제 후 api_record의 집계 컬럼도 리셋
        apmCollectionService.resetCallCounts(repoName);
        return ResponseEntity.ok(result);
    }
}
