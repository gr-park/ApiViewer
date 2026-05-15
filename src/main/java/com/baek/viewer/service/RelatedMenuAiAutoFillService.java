package com.baek.viewer.service;

import com.baek.viewer.ai.AiMenuInferenceService;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * URL 분석(Extract) 직후, 관련메뉴 미흡으로 표시된 레코드에 대해 사내 AI(menu_inference)로
 * {@link ApiRecord#getDescriptionOverride()} 를 채우고 미흡 플래그를 재계산한다.
 */
@Service
public class RelatedMenuAiAutoFillService {

    private static final Logger log = LoggerFactory.getLogger(RelatedMenuAiAutoFillService.class);
    /** 미흡 판정에서 "김"(≥30자)에 걸리지 않도록 상한 — effective 텍스트 기준 */
    private static final int MAX_MENU_OVERRIDE_LEN = 29;

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
        if (repositoryName == null || repositoryName.isBlank()) {
            return 0;
        }
        List<ApiRecord> rows = recordRepo.findRelatedMenuDeficientActiveByRepository(repositoryName.trim());
        int applied = 0;
        for (ApiRecord r : rows) {
            try {
                long id = r.getId();
                String suggestion = menuInferenceService.suggestMenuForRecord(id);
                String cleaned = sanitizeMenuOverride(suggestion);
                if (cleaned.isEmpty()) {
                    continue;
                }
                ApiRecord fresh = recordRepo.findById(id).orElse(null);
                if (fresh == null) {
                    continue;
                }
                fresh.setDescriptionOverride(cleaned);
                deficiencyChecker.applyTo(fresh);
                recordRepo.save(fresh);
                applied++;
            } catch (Exception ex) {
                log.warn("[AI] 관련메뉴 자동 보완 실패 repo={} id={}: {}", repositoryName, r.getId(), ex.getMessage());
            }
        }
        return applied;
    }

    static String sanitizeMenuOverride(String raw) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (oneLine.length() > MAX_MENU_OVERRIDE_LEN) {
            oneLine = oneLine.substring(0, MAX_MENU_OVERRIDE_LEN).trim();
        }
        return oneLine;
    }
}
