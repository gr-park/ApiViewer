package com.baek.viewer.ai;

import com.baek.viewer.model.AiPromptTemplate;
import com.baek.viewer.model.BatchExecutionLog;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.AiPromptTemplateRepository;
import com.baek.viewer.repository.BatchExecutionLogRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.util.PromptPlaceholderUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiOpsDigestService {

    private static final Logger log = LoggerFactory.getLogger(AiOpsDigestService.class);

    private static final Set<String> DEFAULT_JOB_TYPES = Set.of(
            "GIT_PULL", "GIT_PULL_EXTRACT", "APM_COLLECT", "DATA_BACKUP");

    private final GlobalConfigRepository globalRepo;
    private final AiPromptTemplateRepository templateRepo;
    private final BatchExecutionLogRepository batchLogRepo;
    private final InternalOpenAiCompatibleClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiPromptTemplateLastRunUpdater lastRunUpdater;

    public AiOpsDigestService(GlobalConfigRepository globalRepo,
                              AiPromptTemplateRepository templateRepo,
                              BatchExecutionLogRepository batchLogRepo,
                              InternalOpenAiCompatibleClient aiClient,
                              AiPromptTemplateLastRunUpdater lastRunUpdater) {
        this.globalRepo = globalRepo;
        this.templateRepo = templateRepo;
        this.batchLogRepo = batchLogRepo;
        this.aiClient = aiClient;
        this.lastRunUpdater = lastRunUpdater;
    }

    /**
     * 지정 배치가 성공 종료된 뒤, 설정·템플릿이 갖춰졌으면 비동기에서 호출.
     */
    @Transactional
    public void runDigestIfConfigured(String finishedJobType, String status) {
        if (!"SUCCESS".equalsIgnoreCase(status)) {
            return;
        }
        GlobalConfig gc = globalRepo.findById(1L).orElse(null);
        if (gc == null) return;
        if (gc.getAiOpenApiBaseUrl() == null || gc.getAiOpenApiBaseUrl().isBlank()) {
            return;
        }
        if (gc.getAiOpenApiToken() == null || gc.getAiOpenApiToken().isBlank()) {
            return;
        }

        Set<String> triggers = resolveTriggerTypes(gc);
        if (!triggers.contains(finishedJobType)) {
            log.debug("[AI] ops_digest 스킵: jobType={} (트리거 대상 아님)", finishedJobType);
            return;
        }

        AiPromptTemplate tpl = templateRepo.findBySlug(AiPromptSlugs.OPS_DIGEST)
                .filter(AiPromptTemplate::isEnabled)
                .orElse(null);
        if (tpl == null) {
            log.debug("[AI] ops_digest 스킵: 템플릿 없음 또는 비활성");
            return;
        }

        List<BatchExecutionLog> recent = batchLogRepo.findTop15ByOrderByIdDesc();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BatchExecutionLog b : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobType", b.getJobType());
            m.put("status", b.getStatus());
            m.put("summary", b.getResultSummary());
            m.put("startTime", b.getStartTime() != null ? b.getStartTime().toString() : null);
            m.put("endTime", b.getEndTime() != null ? b.getEndTime().toString() : null);
            m.put("itemCount", b.getItemCount());
            m.put("durationMs", b.getDurationMs());
            rows.add(m);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("[AI] ops_digest JSON 직렬화 실패: {}", e.getMessage());
            return;
        }

        Map<String, String> vars = Map.of("recent_batch_logs_json", json);
        String prompt = PromptPlaceholderUtil.apply(tpl.getBody(), vars);

        try {
            log.debug("[AI] ops_digest 요청: triggerJob={}", finishedJobType);
            String digest = aiClient.chatCompletion(gc, prompt);
            gc.setAiLastOpsDigest(digest);
            gc.setAiLastOpsDigestAt(LocalDateTime.now());
            globalRepo.save(gc);
            lastRunUpdater.recordProductionBySlug(AiPromptSlugs.OPS_DIGEST, digest,
                    "trigger=" + finishedJobType, null);
            log.info("[AI] ops_digest 저장 완료: triggerJob={}, len={}",
                    finishedJobType, digest != null ? digest.length() : 0);
        } catch (Exception e) {
            lastRunUpdater.recordProductionBySlug(AiPromptSlugs.OPS_DIGEST, null,
                    "trigger=" + finishedJobType, e.getMessage());
            log.warn("[AI] ops_digest 실패: {}", e.getMessage());
        }
    }

    private Set<String> resolveTriggerTypes(GlobalConfig gc) {
        String raw = gc.getAiOpsDigestJobTypes();
        if (raw != null && !raw.isBlank()) {
            return java.util.Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
        return DEFAULT_JOB_TYPES;
    }
}
