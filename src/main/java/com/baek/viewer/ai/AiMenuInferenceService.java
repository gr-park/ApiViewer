package com.baek.viewer.ai;

import com.baek.viewer.model.AiPromptTemplate;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.AiPromptTemplateRepository;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.util.PromptPlaceholderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiMenuInferenceService {

    private static final Logger log = LoggerFactory.getLogger(AiMenuInferenceService.class);

    private final GlobalConfigRepository globalRepo;
    private final AiPromptTemplateRepository templateRepo;
    private final ApiRecordRepository recordRepo;
    private final RepoConfigRepository repoConfigRepo;
    private final InternalOpenAiCompatibleClient aiClient;
    private final AiPromptTemplateLastRunUpdater lastRunUpdater;

    public AiMenuInferenceService(GlobalConfigRepository globalRepo,
                                  AiPromptTemplateRepository templateRepo,
                                  ApiRecordRepository recordRepo,
                                  RepoConfigRepository repoConfigRepo,
                                  InternalOpenAiCompatibleClient aiClient,
                                  AiPromptTemplateLastRunUpdater lastRunUpdater) {
        this.globalRepo = globalRepo;
        this.templateRepo = templateRepo;
        this.recordRepo = recordRepo;
        this.repoConfigRepo = repoConfigRepo;
        this.aiClient = aiClient;
        this.lastRunUpdater = lastRunUpdater;
    }

    /**
     * 관련 메뉴 한 줄 제안 (descriptionOverride 반영 전 소스만 사용)
     */
    public String suggestMenuForRecord(long recordId) {
        GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
        if (gc.getAiOpenApiBaseUrl() == null || gc.getAiOpenApiBaseUrl().isBlank()) {
            throw new IllegalStateException("사내 AI URL이 설정되지 않았습니다. 설정 → 사내 AI에서 구성하세요.");
        }

        AiPromptTemplate tpl = templateRepo.findBySlug(AiPromptSlugs.MENU_INFERENCE)
                .filter(AiPromptTemplate::isEnabled)
                .orElseThrow(() -> new IllegalStateException("프롬프트 템플릿 menu_inference 가 없거나 비활성입니다."));

        ApiRecord r = recordRepo.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("레코드를 찾을 수 없습니다: id=" + recordId));

        String business = "";
        if (r.getRepositoryName() != null) {
            business = repoConfigRepo.findByRepoName(r.getRepositoryName())
                    .map(RepoConfig::getBusinessName)
                    .orElse("");
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("business_name", nz(business));
        vars.put("repository_name", nz(r.getRepositoryName()));
        vars.put("api_path", nz(r.getApiPath()));
        vars.put("http_method", nz(r.getHttpMethod()));
        vars.put("full_url", nz(r.getFullUrl()));
        vars.put("source_api_operation", src(r.getApiOperationValue()));
        vars.put("source_description_tag", src(r.getDescriptionTag()));
        vars.put("source_method_javadoc", src(r.getFullComment()));
        vars.put("source_controller_javadoc", src(r.getControllerComment()));
        vars.put("source_request_property", src(r.getRequestPropertyValue()));
        vars.put("source_controller_request_property", src(r.getControllerRequestPropertyValue()));

        String prompt = PromptPlaceholderUtil.apply(tpl.getBody(), vars);
        log.debug("[AI] menu_inference 요청: recordId={}", recordId);
        try {
            String out = aiClient.chatCompletion(gc, prompt);
            String normalized = out != null ? out : "";
            lastRunUpdater.recordProductionBySlug(AiPromptSlugs.MENU_INFERENCE, normalized,
                    "recordId=" + recordId, null);
            log.info("[AI] menu_inference 완료: recordId={}, outLen={}", recordId, normalized.length());
            return normalized;
        } catch (Exception e) {
            lastRunUpdater.recordProductionBySlug(AiPromptSlugs.MENU_INFERENCE, null,
                    "recordId=" + recordId, e.getMessage());
            throw e;
        }
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String src(String v) {
        if (v == null || v.isBlank() || "-".equals(v.trim())) return "(없음)";
        return v.trim();
    }
}
