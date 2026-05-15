package com.baek.viewer.ai;

import com.baek.viewer.model.GlobalConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI 호환 POST /v1/chat/completions 스타일 API 클라이언트.
 * 요청/응답 전문은 로그에 남기지 않는다 (토큰·개인정보).
 */
@Component
public class InternalOpenAiCompatibleClient {

    private static final Logger log = LoggerFactory.getLogger(InternalOpenAiCompatibleClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${api.viewer.ai.default-model:}")
    private String defaultModelProperty;

    /**
     * @return 모델 응답 텍스트 (choices[0].message.content)
     */
    public String chatCompletion(GlobalConfig gc, String userMessage) {
        return chatCompletion(gc, userMessage, null);
    }

    /**
     * @param maxTokens null이면 요청에 max_tokens 를 넣지 않음. 연결 확인 등 짧은 응답용.
     */
    public String chatCompletion(GlobalConfig gc, String userMessage, Integer maxTokens) {
        String url = gc.resolveAiChatEndpoint();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("사내 AI 베이스 URL이 설정되지 않았습니다.");
        }
        String token = gc.getAiOpenApiToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("사내 AI 토큰이 설정되지 않았습니다.");
        }

        ObjectNode root = objectMapper.createObjectNode();
        String model = gc.getAiOpenApiModel();
        if (model == null || model.isBlank()) {
            model = defaultModelProperty;
        }
        if (model != null && !model.isBlank()) {
            root.put("model", model.trim());
        }
        if (maxTokens != null && maxTokens > 0) {
            root.put("max_tokens", maxTokens);
        }
        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);
        root.put("temperature", 0.3);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token.trim());

        try {
            log.debug("[AI] chat 요청: endpoint={}, modelSet={}", url, model != null && !model.isBlank());
            ResponseEntity<String> res = restTemplate.postForEntity(
                    url, new HttpEntity<>(root.toString(), headers), String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                throw new IllegalStateException("AI 응답 오류: HTTP " + res.getStatusCode().value());
            }
            JsonNode tree = objectMapper.readTree(res.getBody());
            JsonNode choices = tree.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                log.warn("[AI] choices 없음");
                return "";
            }
            JsonNode content = choices.get(0).path("message").path("content");
            return content.isMissingNode() || content.isNull() ? "" : content.asText("").trim();
        } catch (RestClientException e) {
            log.warn("[AI] HTTP 호출 실패: {}", e.getMessage());
            throw new IllegalStateException("사내 AI 호출 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("[AI] 응답 파싱 실패: {}", e.getMessage());
            throw new IllegalStateException("AI 응답 처리 실패: " + e.getMessage(), e);
        }
    }
}
