package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.util.PathParamPatternUtil;
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

/**
 * {@link ApiRecord#getPathParamPattern()} 컬럼을 api_path 기준으로 일괄 재계산한다.
 */
@Service
public class PathParamPatternRecalcService {

    private static final Logger log = LoggerFactory.getLogger(PathParamPatternRecalcService.class);
    private static final int BATCH_SIZE = 1000;

    private final ApiRecordRepository recordRepository;

    public PathParamPatternRecalcService(ApiRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    public RecalcResult recalculate(List<String> repoNames) {
        long start = System.currentTimeMillis();
        List<String> scope = repoNames != null ? repoNames : List.of();
        log.info("[경로변수 패턴 재계산] 시작 repo={}", RecalcRepoScope.scopeLabel(scope));

        int total = 0;
        int updated = 0;
        Pageable pageable = PageRequest.of(0, BATCH_SIZE, Sort.by("id"));
        Page<ApiRecord> page;
        do {
            page = RecalcRepoScope.fetchPage(recordRepository, scope, pageable);
            List<ApiRecord> changed = new ArrayList<>();
            for (ApiRecord r : page.getContent()) {
                String next = PathParamPatternUtil.fromApiPath(r.getApiPath());
                if (!Objects.equals(r.getPathParamPattern(), next)) {
                    r.setPathParamPattern(next);
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
        log.info("[경로변수 패턴 재계산] 완료 repo={} 전체={}건 변경={}건 소요={}ms",
                RecalcRepoScope.scopeLabel(scope), total, updated, elapsed);
        return new RecalcResult(total, updated, elapsed, RecalcRepoScope.isAllRepos(scope) ? null : List.copyOf(scope));
    }

    public record RecalcResult(int total, int updated, long elapsedMs, List<String> repoNames) {
    }
}
