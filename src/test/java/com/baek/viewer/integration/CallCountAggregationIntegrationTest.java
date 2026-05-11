package com.baek.viewer.integration;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.ApiStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 호출건수 재집계 통합 테스트.
 *
 * 검증:
 *   - updateCallCounts: 단순 호출 count 필드 갱신
 *   - 호출건수 변경에 따른 status 자동 재계산 (umbrella sticky 포함)
 *   - 미매칭 path 는 호출수 0 으로 리셋되지 않음
 *   - statusOverridden=true 레코드는 status 변경 안 됨
 */
@SpringBootTest
@Transactional
class CallCountAggregationIntegrationTest {

    private static final String REPO = "test-repo-call";

    @Autowired ApiStorageService storage;
    @Autowired ApiRecordRepository recordRepo;
    @Autowired RepoConfigRepository repoConfigRepo;

    @BeforeEach
    void cleanup() {
        recordRepo.deleteByRepositoryName(REPO);
        repoConfigRepo.findByRepoName(REPO).ifPresent(rc -> repoConfigRepo.deleteById(rc.getId()));
        RepoConfig rc = new RepoConfig();
        rc.setRepoName(REPO);
        rc.setBusinessName(REPO);
        rc.setTeamName("IT카드개발팀");
        rc.setWhatapEnabled("N");
        rc.setJenniferEnabled("N");
        repoConfigRepo.save(rc);
    }

    private ApiInfo info(String path, String[] git) {
        ApiInfo a = new ApiInfo();
        a.setApiPath(path);
        a.setHttpMethod("GET");
        a.setMethodName("h");
        a.setControllerName("C");
        a.setRepoPath("X.java");
        a.setIsDeprecated("N");
        a.setGit1(git);
        return a;
    }
    private static String[] oldCommit() {
        return new String[]{LocalDate.now().minusYears(2).toString(), "a", "m"};
    }
    private static String[] recentCommit() {
        return new String[]{LocalDate.now().minusDays(10).toString(), "a", "m"};
    }

    @Test
    @DisplayName("updateCallCounts — 호출건수만 매핑된 경우 call_count 필드 갱신")
    void updateCallCounts_simpleUpdate() {
        // 1년 미만 커밋 → 사용 분류 (호출 0 + 1년 미만 → ②-②)
        // 호출 100 발생 시 → 사용 (target USE) 전이
        storage.save(REPO, List.of(info("/api/a", recentCommit())), "ip");
        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/a", "GET").orElseThrow();
        // 신규 + 1년 미만 커밋 + call=0 → ②-② (umbrella REVIEW)
        assertThat(r.getStatus()).isEqualTo("②-① 호출0건+변경있음");

        // 호출건수 100 부여
        storage.updateCallCounts(REPO, Map.of("/api/a", 100L));
        ApiRecord after = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/a", "GET").orElseThrow();
        assertThat(after.getCallCount()).isEqualTo(100L);
        assertThat(after.getStatus()).isEqualTo("②-① 호출0건+변경있음");
        assertThat(after.getAutoAnalyzedStatus()).isEqualTo("사용");
        assertThat(after.isStatusChanged()).isTrue();
    }

    @Test
    @DisplayName("updateCallCounts — 1년 경과 + 호출 1~3 → 자동은 ②-②, 공식 ①-① 승계")
    void updateCallCounts_stickyBlock() {
        storage.save(REPO, List.of(info("/api/b", oldCommit())), "ip");
        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/b", "GET").orElseThrow();
        assertThat(r.getStatus()).isEqualTo("①-① 차단대상");

        storage.updateCallCounts(REPO, Map.of("/api/b", 2L));
        ApiRecord after = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/b", "GET").orElseThrow();
        assertThat(after.getCallCount()).isEqualTo(2L);
        assertThat(after.getStatus()).isEqualTo("①-① 차단대상");
        assertThat(after.getAutoAnalyzedStatus()).isEqualTo("②-② 호출 3건 이하+변경없음");
        assertThat(after.isStatusChanged()).isTrue();
    }

    @Test
    @DisplayName("updateCallCounts — statusOverridden=true 레코드는 status 변경되지 않음")
    void updateCallCounts_statusOverridden_preserved() {
        storage.save(REPO, List.of(info("/api/c", oldCommit())), "ip");
        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/c", "GET").orElseThrow();
        r.setStatus("①-④ 사용으로 변경");
        r.setStatusOverridden(true);
        recordRepo.save(r);

        storage.updateCallCounts(REPO, Map.of("/api/c", 50L));
        ApiRecord after = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/c", "GET").orElseThrow();
        assertThat(after.getCallCount()).isEqualTo(50L);
        assertThat(after.getStatus()).isEqualTo("①-④ 사용으로 변경");  // 보존
    }

    @Test
    @DisplayName("updateCallCounts — 매핑에 없는 레코드는 0으로 리셋되지 않음")
    void updateCallCounts_unmappedRecord_unchanged() {
        // 2건 INSERT
        storage.save(REPO, List.of(
                info("/api/x", oldCommit()),
                info("/api/y", oldCommit())
        ), "ip");

        // /api/x 만 호출건수 2건 매핑 — callLow + fullOld → target=REVIEW, sticky 로 ①-① 보존
        storage.updateCallCounts(REPO, Map.of("/api/x", 2L));

        ApiRecord rx = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/x", "GET").orElseThrow();
        ApiRecord ry = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/y", "GET").orElseThrow();
        assertThat(rx.getCallCount()).isEqualTo(2L);
        assertThat(rx.getStatus()).isEqualTo("①-① 차단대상");
        assertThat(rx.getAutoAnalyzedStatus()).isEqualTo("②-② 호출 3건 이하+변경없음");
        assertThat(rx.isStatusChanged()).isTrue();
        assertThat(ry.getStatus()).isEqualTo("①-① 차단대상");
        assertThat(ry.getAutoAnalyzedStatus()).isEqualTo("①-① 차단대상");
        assertThat(ry.isStatusChanged()).isFalse();
    }
}
