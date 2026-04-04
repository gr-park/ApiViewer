package com.baek.viewer.controller;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.service.MockApmService;
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
    private final MockApmService mockApmService;

    public ApmController(MockApmService mockApmService) {
        this.mockApmService = mockApmService;
    }

    /** Mock 데이터 생성 */
    @PostMapping("/mock/generate")
    public ResponseEntity<?> generateMock(@RequestBody Map<String, Object> body) {
        String repoName = (String) body.get("repoName");
        int days = body.containsKey("days") ? ((Number) body.get("days")).intValue() : 90;
        if (repoName == null || repoName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "repoName 필수"));
        log.info("[APM Mock] 생성 요청: repo={}, days={}", repoName, days);
        return ResponseEntity.ok(mockApmService.generateMockData(repoName, days));
    }

    /** APM 데이터 → ApiRecord 호출건수 집계 반영 */
    @PostMapping("/aggregate")
    public ResponseEntity<?> aggregate(@RequestBody Map<String, String> body) {
        String repoName = body.get("repoName");
        if (repoName == null || repoName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "repoName 필수"));
        log.info("[APM 집계] 요청: repo={}", repoName);
        return ResponseEntity.ok(mockApmService.aggregateToRecords(repoName));
    }

    /** APM 일별 호출 데이터 조회 */
    @GetMapping("/data")
    public ResponseEntity<?> getData(@RequestParam String repoName,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        LocalDate fromDate = from != null ? LocalDate.parse(from) : LocalDate.now().minusDays(30);
        LocalDate toDate = to != null ? LocalDate.parse(to) : LocalDate.now();
        List<ApmCallData> data = mockApmService.getCallData(repoName, fromDate, toDate);

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
}
