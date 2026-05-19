package com.baek.viewer.service;

import com.baek.viewer.ai.InternalOpenAiCompatibleClient;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ApiRecord#getRelatedMenuDeficient()} 컬럼을 현재 필드·전역 글자수 기준으로 일괄 재계산한다.
 */
@Service
public class RelatedMenuDeficiencyRecalcService {

    private static final Logger log = LoggerFactory.getLogger(RelatedMenuDeficiencyRecalcService.class);
    private static final int BATCH_SIZE = 1000;

    private final ApiRecordRepository recordRepository;
    private final RelatedMenuDeficiencyChecker deficiencyChecker;
    private final RelatedMenuAiAutoFillService relatedMenuAiAutoFillService;
    private final GlobalConfigRepository globalConfigRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final InternalOpenAiCompatibleClient internalOpenAiCompatibleClient;

    public RelatedMenuDeficiencyRecalcService(ApiRecordRepository recordRepository,
                                                RelatedMenuDeficiencyChecker deficiencyChecker,
                                                RelatedMenuAiAutoFillService relatedMenuAiAutoFillService,
                                                GlobalConfigRepository globalConfigRepository,
                                                RepoConfigRepository repoConfigRepository,
                                                InternalOpenAiCompatibleClient internalOpenAiCompatibleClient) {
        this.recordRepository = recordRepository;
        this.deficiencyChecker = deficiencyChecker;
        this.relatedMenuAiAutoFillService = relatedMenuAiAutoFillService;
        this.globalConfigRepository = globalConfigRepository;
        this.repoConfigRepository = repoConfigRepository;
        this.internalOpenAiCompatibleClient = internalOpenAiCompatibleClient;
    }

    /** @param repoNames 빈 리스트 = 전체 레포 */
    public RecalcResult recalculate(List<String> repoNames, boolean applyAiAfter) {
        List<String> scope = repoNames != null ? repoNames : List.of();
        long start = System.currentTimeMillis();
        log.info("[관련메뉴 미흡 재계산] 시작 repo={} applyAi={}", RecalcRepoScope.scopeLabel(scope), applyAiAfter);

        int total = 0;
        int updated = 0;
        int turnedDeficient = 0;
        int turnedOk = 0;

        Pageable pageable = PageRequest.of(0, BATCH_SIZE, Sort.by("id"));
        Page<ApiRecord> page;
        do {
            page = RecalcRepoScope.fetchPage(recordRepository, scope, pageable);
            List<ApiRecord> changed = new ArrayList<>();
            for (ApiRecord r : page.getContent()) {
                boolean prev = Boolean.TRUE.equals(r.getRelatedMenuDeficient());
                boolean next = deficiencyChecker.isDeficient(r);
                if (prev != next) {
                    r.setRelatedMenuDeficient(next);
                    changed.add(r);
                    if (next) {
                        turnedDeficient++;
                    } else {
                        turnedOk++;
                    }
                }
            }
            if (!changed.isEmpty()) {
                recordRepository.saveAll(changed);
            }
            updated += changed.size();
            total += page.getNumberOfElements();
            pageable = pageable.next();
        } while (page.hasNext());

        int aiApplied = 0;
        String aiError = null;
        long remainingDeficient = 0;

        if (applyAiAfter) {
            try {
                assertAiReady(globalConfigRepository.findById(1L).orElse(new GlobalConfig()));
                for (String repo : reposForAiFill(scope)) {
                    aiApplied += relatedMenuAiAutoFillService.fillDeficientInRepository(repo);
                }
                remainingDeficient = countRemainingDeficient(scope);
            } catch (Exception ex) {
                aiError = ex.getMessage() != null ? ex.getMessage() : "사내 AI 보완 실패";
                log.warn("[관련메뉴 미흡 재계산] AI 보완 오류: {}", aiError);
                remainingDeficient = countRemainingDeficient(scope);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[관련메뉴 미흡 재계산] 완료 repo={} 전체={}건 변경={}건 미흡전환={} 정상전환={} aiApplied={} remaining={} 소요={}ms",
                RecalcRepoScope.scopeLabel(scope), total, updated, turnedDeficient, turnedOk, aiApplied, remainingDeficient, elapsed);

        return new RecalcResult(
                total,
                updated,
                turnedDeficient,
                turnedOk,
                elapsed,
                RecalcRepoScope.isAllRepos(scope) ? null : List.copyOf(scope),
                aiApplied,
                remainingDeficient,
                aiError);
    }

    private List<String> reposForAiFill(List<String> scope) {
        if (!RecalcRepoScope.isAllRepos(scope)) {
            return scope;
        }
        return repoConfigRepository.findAllByOrderByRepoNameAsc().stream()
                .map(RepoConfig::getRepoName)
                .filter(n -> n != null && !n.isBlank())
                .toList();
    }

    private long countRemainingDeficient(List<String> scope) {
        if (RecalcRepoScope.isAllRepos(scope)) {
            return recordRepository.countRelatedMenuDeficient();
        }
        return recordRepository.countRelatedMenuDeficientForRepos(scope);
    }

    private void assertAiReady(GlobalConfig gc) {
        if (!gc.isAiApiEnabled()) {
            throw new IllegalStateException("AI API 요청이 비활성화되어 있습니다.");
        }
        if (gc.getAiOpenApiBaseUrl() == null || gc.getAiOpenApiBaseUrl().isBlank()) {
            throw new IllegalStateException("사내 AI 베이스 URL이 설정되지 않았습니다.");
        }
        if (gc.getAiOpenApiToken() == null || gc.getAiOpenApiToken().isBlank()) {
            throw new IllegalStateException("사내 AI 토큰이 설정되지 않았습니다.");
        }
        internalOpenAiCompatibleClient.chatCompletion(gc, "ping", 8);
    }

    public record RecalcResult(
            int total,
            int updated,
            int nowDeficient,
            int nowOk,
            long elapsedMs,
            List<String> repoNames,
            int aiApplied,
            long remainingDeficient,
            String aiError) {
    }
}
