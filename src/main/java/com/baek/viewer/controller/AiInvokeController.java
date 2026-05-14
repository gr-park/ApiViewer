package com.baek.viewer.controller;

import com.baek.viewer.ai.AiMenuInferenceService;
import com.baek.viewer.ai.InternalOpenAiCompatibleClient;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.service.AiSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiInvokeController {

    private static final Logger log = LoggerFactory.getLogger(AiInvokeController.class);

    private final AiMenuInferenceService menuInferenceService;
    private final InternalOpenAiCompatibleClient aiClient;
    private final GlobalConfigRepository globalConfigRepository;
    private final AiSummaryService aiSummaryService;

    public AiInvokeController(AiMenuInferenceService menuInferenceService,
                               InternalOpenAiCompatibleClient aiClient,
                               GlobalConfigRepository globalConfigRepository,
                               AiSummaryService aiSummaryService) {
        this.menuInferenceService = menuInferenceService;
        this.aiClient = aiClient;
        this.globalConfigRepository = globalConfigRepository;
        this.aiSummaryService = aiSummaryService;
    }

    /**
     * 대시보드 — LOCAi 추천 메시지 캐시 조회 (AI_SUMMARY 배치가 주기적으로 갱신한 결과)
     */
    @GetMapping("/dashboard-summary/cached")
    public ResponseEntity<?> dashboardSummaryCached() {
        return ResponseEntity.ok(aiSummaryService.getCached());
    }

    /**
     * 대시보드 — LOCAi 추천 메시지 (화면 진입 시 호출) — 레거시 유지
     */
    @PostMapping("/dashboard-summary")
    public ResponseEntity<?> dashboardSummary(@RequestBody Map<String, Object> body) {
        Object contentObj = body != null ? body.get("content") : null;
        if (contentObj == null || contentObj.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content 필수"));
        }
        String content = contentObj.toString().strip();

        GlobalConfig gc = globalConfigRepository.findById(1L).orElse(null);
        if (gc == null || (gc.getAiOpenApiBaseUrl() == null || gc.getAiOpenApiBaseUrl().isBlank())) {
            return ResponseEntity.ok(Map.of("summary", "", "configured", false));
        }

        boolean adminMode = Boolean.TRUE.equals(body.get("adminMode"));
        String prompt = adminMode
                ? content + "\n\n위 내용 기준으로 현황과 주요 이슈를 5줄 이내로 요약해."
                : content + "\n\n위 내용 기준으로 100 단어 이내로 분석 결과를 요약해.";
        try {
            String summary = aiClient.chatCompletion(gc, prompt);
            return ResponseEntity.ok(Map.of("summary", summary != null ? summary : "", "configured", true));
        } catch (IllegalStateException e) {
            log.warn("[AI] dashboard-summary 실패: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("summary", "", "configured", true, "error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[AI] dashboard-summary 예외: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 분석현황 레코드 단건 — 관련 메뉴(현업 설명) AI 제안
     */
    @PostMapping("/menu-suggestion")
    public ResponseEntity<?> menuSuggestion(@RequestBody Map<String, Object> body) {
        Object rid = body != null ? body.get("recordId") : null;
        long recordId;
        if (rid instanceof Number n) recordId = n.longValue();
        else if (rid != null) {
            try {
                recordId = Long.parseLong(rid.toString());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "recordId 가 올바르지 않습니다."));
            }
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "recordId 필수"));
        }

        try {
            String suggestion = menuInferenceService.suggestMenuForRecord(recordId);
            return ResponseEntity.ok(Map.of("suggestion", suggestion != null ? suggestion : ""));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[AI] menu-suggestion 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
