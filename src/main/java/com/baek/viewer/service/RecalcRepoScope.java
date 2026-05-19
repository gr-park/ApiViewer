package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 일괄 재계산 API 공통 — 레포 스코프 파싱·페이지 조회 */
public final class RecalcRepoScope {

    private RecalcRepoScope() {
    }

    /**
     * @return 빈 리스트 = 전체 레포. 비어 있지 않으면 지정 레포만.
     */
    public static List<String> parseRepoNames(String repoName, String repoNames) {
        List<String> out = new ArrayList<>();
        if (repoNames != null && !repoNames.isBlank()) {
            Arrays.stream(repoNames.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !"ALL".equalsIgnoreCase(s))
                    .forEach(s -> {
                        if (!out.contains(s)) {
                            out.add(s);
                        }
                    });
        }
        if (repoName != null && !repoName.isBlank()) {
            String one = repoName.trim();
            if (!one.isEmpty() && !"ALL".equalsIgnoreCase(one) && !out.contains(one)) {
                out.add(one);
            }
        }
        return out;
    }

    public static boolean isAllRepos(List<String> repoNames) {
        return repoNames == null || repoNames.isEmpty();
    }

    public static String scopeLabel(List<String> repoNames) {
        return isAllRepos(repoNames) ? "전체" : String.join(",", repoNames);
    }

    public static Page<ApiRecord> fetchPage(ApiRecordRepository repository,
                                            List<String> repoNames,
                                            Pageable pageable) {
        if (isAllRepos(repoNames)) {
            return repository.findAll(pageable);
        }
        if (repoNames.size() == 1) {
            return repository.findByRepositoryName(repoNames.get(0), pageable);
        }
        return repository.findByRepositoryNameIn(repoNames, pageable);
    }
}
