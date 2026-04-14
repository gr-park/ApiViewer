package com.baek.viewer.controller;

import com.baek.viewer.service.MockAnalysisDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Mock 분석데이터 생성/삭제 API (관리자 전용).
 *
 * 실제 소스 분석 없이 더미 ApiRecord 를 생성하여 Railway 등 소스 반입이
 * 불가능한 환경에서 데모 데이터를 채울 때 사용한다.
 */
@RestController
@RequestMapping("/api/mock/analysis")
public class MockAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(MockAnalysisController.class);

    private final MockAnalysisDataService service;

    public MockAnalysisController(MockAnalysisDataService service) {
        this.service = service;
    }

    /** 현재 등록된 mock- 레포 목록 */
    @GetMapping("/repos")
    public ResponseEntity<List<String>> listRepos() {
        return ResponseEntity.ok(service.listMockRepos());
    }

    /**
     * Mock 분석데이터 생성.
     * @param repoName 레포명 (mock- prefix 없으면 자동 부착)
     * @param count    생성 건수 (1~5000)
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestParam String repoName,
                                      @RequestParam(defaultValue = "100") int count) {
        log.info("[Mock 분석데이터] POST repoName={} count={}", repoName, count);
        try {
            return ResponseEntity.ok(service.generate(repoName, count));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Mock 분석데이터] 생성 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Mock 분석데이터 삭제. repoName 미지정/ALL = 모든 mock-* 레포 일괄 삭제.
     */
    @DeleteMapping
    public ResponseEntity<?> delete(@RequestParam(required = false) String repoName) {
        log.warn("[Mock 분석데이터] DELETE repoName={}", repoName);
        try {
            return ResponseEntity.ok(service.delete(repoName));
        } catch (Exception e) {
            log.error("[Mock 분석데이터] 삭제 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
