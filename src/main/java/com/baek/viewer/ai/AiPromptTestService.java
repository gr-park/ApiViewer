package com.baek.viewer.ai;

import com.baek.viewer.model.AiPromptTemplate;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.AiPromptTemplateRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.util.PromptPlaceholderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 설정 화면에서 프롬프트를 샘플 변수로 치환해 사내 AI 호출을 시험한다.
 * 저장하지 않은 편집 본문을 넘기면 그 내용으로 시험한다.
 */
@Service
public class AiPromptTestService {

    private static final Logger log = LoggerFactory.getLogger(AiPromptTestService.class);

    private final AiPromptTemplateRepository templateRepo;
    private final GlobalConfigRepository globalRepo;
    private final InternalOpenAiCompatibleClient aiClient;
    private final AiPromptTemplateLastRunUpdater lastRunUpdater;

    public AiPromptTestService(AiPromptTemplateRepository templateRepo,
                               GlobalConfigRepository globalRepo,
                               InternalOpenAiCompatibleClient aiClient,
                               AiPromptTemplateLastRunUpdater lastRunUpdater) {
        this.templateRepo = templateRepo;
        this.globalRepo = globalRepo;
        this.aiClient = aiClient;
        this.lastRunUpdater = lastRunUpdater;
    }

    /**
     * @param bodyOverride null/blank 이면 DB에 저장된 본문 사용
     */
    public Map<String, Object> runTest(long templateId, String bodyOverride) {
        AiPromptTemplate tpl = templateRepo.findById(templateId)
                .orElseThrow(() -> new NoSuchElementException("template not found: " + templateId));

        GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
        if (gc.getAiOpenApiBaseUrl() == null || gc.getAiOpenApiBaseUrl().isBlank()) {
            throw new IllegalStateException("사내 AI 베이스 URL이 설정되지 않았습니다.");
        }
        if (gc.getAiOpenApiToken() == null || gc.getAiOpenApiToken().isBlank()) {
            throw new IllegalStateException("사내 AI 토큰이 설정되지 않았습니다.");
        }

        String rawBody = (bodyOverride != null && !bodyOverride.isBlank()) ? bodyOverride : tpl.getBody();
        if (rawBody == null) rawBody = "";

        Map<String, String> vars = samplePlaceholderValues();
        String filled = PromptPlaceholderUtil.apply(rawBody, vars);

        log.debug("[AI] 프롬프트 테스트: templateId={}, slug={}, filledLen={}",
                templateId, tpl.getSlug(), filled.length());
        String reply;
        try {
            reply = aiClient.chatCompletion(gc, filled);
        } catch (Exception e) {
            lastRunUpdater.recordTestRun(templateId, null, e.getMessage());
            throw e;
        }
        log.info("[AI] 프롬프트 테스트 완료: templateId={}, slug={}, replyLen={}",
                templateId, tpl.getSlug(), reply != null ? reply.length() : 0);
        lastRunUpdater.recordTestRun(templateId, reply != null ? reply : "", null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", tpl.getSlug());
        out.put("filledPrompt", filled);
        out.put("reply", reply != null ? reply : "");
        return out;
    }

    /** menu_inference / ops_digest 및 기타 slug용 샘플 값 (미정의 {{키}} 는 빈 문자열) */
    private static Map<String, String> samplePlaceholderValues() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("business_name", "[테스트] 카드 한도 조회");
        m.put("repository_name", "card-api-sample");
        m.put("api_path", "/api/v1/card/limit");
        m.put("http_method", "GET");
        m.put("full_url", "https://example.test/api/v1/card/limit");
        m.put("source_api_operation", "카드 한도 조회");
        m.put("source_description_tag", "사용자별 한도 확인 API");
        m.put("source_method_javadoc", "한도 조회 처리 메소드입니다.");
        m.put("source_controller_javadoc", "카드 한도 관련 REST 컨트롤러");
        m.put("source_request_property", "consumes=APPLICATION_JSON");
        m.put("source_controller_request_property", "value=/api/v1/card");
        m.put("recent_batch_logs_json", "[{\"jobType\":\"GIT_PULL_EXTRACT\",\"status\":\"SUCCESS\",\"summary\":\"추출 완료 120건\",\"startTime\":\"2026-01-15T02:00:00\",\"endTime\":\"2026-01-15T02:03:00\",\"itemCount\":120,\"durationMs\":180000},{\"jobType\":\"APM_COLLECT\",\"status\":\"SUCCESS\",\"summary\":\"호출 수집 완료\",\"startTime\":\"2026-01-15T03:00:00\",\"endTime\":\"2026-01-15T03:01:00\",\"itemCount\":500,\"durationMs\":45000}]");
        return m;
    }
}
