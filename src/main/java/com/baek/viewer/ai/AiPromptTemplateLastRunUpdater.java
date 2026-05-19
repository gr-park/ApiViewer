package com.baek.viewer.ai;

import com.baek.viewer.model.AiPromptTemplate;
import com.baek.viewer.repository.AiPromptTemplateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 사내 AI 호출 결과를 {@link AiPromptTemplate}에 짧은 트랜잭션으로 기록한다.
 * 긴 AI HTTP 호출과 DB 커밋을 분리하기 위해 {@code REQUIRES_NEW} 를 사용한다.
 */
@Component
public class AiPromptTemplateLastRunUpdater {

    private static final int ERR_MAX = 500;

    private final AiPromptTemplateRepository templateRepo;

    public AiPromptTemplateLastRunUpdater(AiPromptTemplateRepository templateRepo) {
        this.templateRepo = templateRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTestRun(long templateId, String reply, String errorMessage) {
        templateRepo.findById(templateId).ifPresent(t -> applyTest(t, reply, errorMessage));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProductionBySlug(String slug, String reply, String meta, String errorMessage) {
        if (slug == null || slug.isBlank()) return;
        templateRepo.findBySlug(slug).ifPresent(t -> applyProd(t, reply, meta, errorMessage));
    }

    private void applyTest(AiPromptTemplate t, String reply, String errorMessage) {
        t.setLastTestAt(LocalDateTime.now());
        if (errorMessage != null && !errorMessage.isBlank()) {
            t.setLastTestError(trunc(errorMessage));
            t.setLastTestReply(null);
        } else {
            t.setLastTestError(null);
            t.setLastTestReply(reply != null ? reply : "");
        }
        templateRepo.save(t);
    }

    private void applyProd(AiPromptTemplate t, String reply, String meta, String errorMessage) {
        t.setLastProdAt(LocalDateTime.now());
        t.setLastProdMeta(meta != null && !meta.isBlank() ? trunc(meta) : null);
        if (errorMessage != null && !errorMessage.isBlank()) {
            t.setLastProdError(trunc(errorMessage));
            t.setLastProdReply(null);
        } else {
            t.setLastProdError(null);
            t.setLastProdReply(reply != null ? reply : "");
        }
        templateRepo.save(t);
    }

    private static String trunc(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= ERR_MAX ? t : t.substring(0, ERR_MAX - 3) + "...";
    }
}
