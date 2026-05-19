package com.baek.viewer.service;

import com.baek.viewer.ai.AiMenuInferenceService;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.util.DescriptionOverrideSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * URL 분석(Extract) 직후, 관련메뉴 미흡으로 표시된 레코드에 대해 사내 AI(menu_inference)로
 * {@link ApiRecord#getDescriptionOverride()} 를 채우고 미흡 플래그를 재계산한다.
 */
@Service
public class RelatedMenuAiAutoFillService {

    private static final Logger log = LoggerFactory.getLogger(RelatedMenuAiAutoFillService.class);

    private final ApiRecordRepository recordRepo;
    private final AiMenuInferenceService menuInferenceService;
    private final RelatedMenuDeficiencyChecker deficiencyChecker;

    public RelatedMenuAiAutoFillService(ApiRecordRepository recordRepo,
                                        AiMenuInferenceService menuInferenceService,
                                        RelatedMenuDeficiencyChecker deficiencyChecker) {
        this.recordRepo = recordRepo;
        this.menuInferenceService = menuInferenceService;
        this.deficiencyChecker = deficiencyChecker;
    }

    public int fillDeficientInRepository(String repositoryName) {
        return fillDeficientInRepository(repositoryName, null);
    }

    /**
     * @param onProgress (processed 1..total, total) — Extract 진행률 UI용
     */
    public int fillDeficientInRepository(String repositoryName, BiConsumer<Integer, Integer> onProgress) {
        if (repositoryName == null || repositoryName.isBlank()) {
            return 0;
        }
        List<ApiRecord> rows = recordRepo.findRelatedMenuDeficientActiveByRepository(repositoryName.trim());
        int total = rows.size();
        if (onProgress != null && total > 0) {
            onProgress.accept(0, total);
        }
        int applied = 0;
        int index = 0;
        for (ApiRecord r : rows) {
            index++;
            try {
                long id = r.getId();
                String suggestion = menuInferenceService.suggestMenuForRecord(id);
                String cleaned = sanitizeMenuOverride(suggestion, deficiencyChecker.resolveMaxLen());
                if (cleaned.isEmpty()) {
                    continue;
                }
                ApiRecord fresh = recordRepo.findById(id).orElse(null);
                if (fresh == null) {
                    continue;
                }
                fresh.setDescriptionOverride(cleaned);
                fresh.setDescriptionOverrideSource(DescriptionOverrideSources.AI_AUTO);
                deficiencyChecker.applyTo(fresh);
                recordRepo.save(fresh);
                applied++;
            } catch (Exception ex) {
                log.warn("[AI] 관련메뉴 자동 보완 실패 repo={} id={}: {}", repositoryName, r.getId(), ex.getMessage());
            } finally {
                if (onProgress != null && total > 0) {
                    onProgress.accept(index, total);
                }
            }
        }
        return applied;
    }

    static String sanitizeMenuOverride(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        int cap = maxLen >= 1 ? maxLen : 29;
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (oneLine.length() > cap) {
            oneLine = oneLine.substring(0, cap).trim();
        }
        return oneLine;
    }
}
