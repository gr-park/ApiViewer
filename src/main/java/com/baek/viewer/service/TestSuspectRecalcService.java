package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** {@link ApiRecord#getTestSuspectReason()} 일괄 재평가 */
@Service
public class TestSuspectRecalcService {

    private static final Logger log = LoggerFactory.getLogger(TestSuspectRecalcService.class);
    private static final int BATCH_SIZE = 1000;

    private final ApiRecordRepository recordRepository;
    private final TestSuspectMatcher testSuspectMatcher;

    public TestSuspectRecalcService(ApiRecordRepository recordRepository,
                                    TestSuspectMatcher testSuspectMatcher) {
        this.recordRepository = recordRepository;
        this.testSuspectMatcher = testSuspectMatcher;
    }

    public RecalcResult recalculate(List<String> repoNames) {
        long start = System.currentTimeMillis();
        List<String> scope = repoNames != null ? repoNames : List.of();
        List<String> keywords = testSuspectMatcher.currentKeywords();
        log.info("[테스트의심 재평가] 시작 repo={} keywords={}", RecalcRepoScope.scopeLabel(scope), keywords.size());

        int total = 0;
        int updated = 0;
        Pageable pageable = PageRequest.of(0, BATCH_SIZE, Sort.by("id"));
        Page<ApiRecord> page;
        do {
            page = RecalcRepoScope.fetchPage(recordRepository, scope, pageable);
            List<ApiRecord> changed = new ArrayList<>();
            for (ApiRecord r : page.getContent()) {
                String newReason = testSuspectMatcher.matchFromRecord(r, keywords);
                if (!Objects.equals(r.getTestSuspectReason(), newReason)) {
                    r.setTestSuspectReason(newReason);
                    changed.add(r);
                }
            }
            if (!changed.isEmpty()) {
                recordRepository.saveAll(changed);
            }
            updated += changed.size();
            total += page.getNumberOfElements();
            pageable = pageable.next();
        } while (page.hasNext());

        long elapsed = System.currentTimeMillis() - start;
        log.info("[테스트의심 재평가] 완료 repo={} 전체={}건 변경={}건 소요={}ms",
                RecalcRepoScope.scopeLabel(scope), total, updated, elapsed);
        return new RecalcResult(
                total,
                updated,
                keywords,
                elapsed,
                RecalcRepoScope.isAllRepos(scope) ? null : List.copyOf(scope));
    }

    public record RecalcResult(int total, int updated, List<String> keywords, long elapsedMs, List<String> repoNames) {
    }
}
