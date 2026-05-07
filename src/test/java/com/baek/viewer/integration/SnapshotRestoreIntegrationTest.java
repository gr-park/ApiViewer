package com.baek.viewer.integration;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ApiRecordSnapshot;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.ApiRecordSnapshotRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.SnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스냅샷 → 라이브 api_record 복구: 기존 행 삭제 후 스냅샷 행만 남는지 검증.
 */
@SpringBootTest
@Transactional
class SnapshotRestoreIntegrationTest {

    private static final String REPO = "test-repo-snap-restore";

    @Autowired
    private SnapshotService snapshotService;
    @Autowired
    private ApiRecordRepository recordRepo;
    @Autowired
    private RepoConfigRepository repoConfigRepo;
    @Autowired
    private ApiRecordSnapshotRepository snapshotRepository;

    @Test
    @DisplayName("restoreLiveFromSnapshot — 라이브 전체를 스냅샷 내용으로 교체")
    void restoreLive_replacesAllApiRecords() {
        recordRepo.deleteByRepositoryName(REPO);
        repoConfigRepo.findByRepoName(REPO).ifPresent(rc -> repoConfigRepo.deleteById(rc.getId()));
        RepoConfig rc = new RepoConfig();
        rc.setRepoName(REPO);
        rc.setBusinessName(REPO);
        rc.setTeamName("IT카드개발팀");
        rc.setWhatapEnabled("N");
        rc.setJenniferEnabled("N");
        repoConfigRepo.save(rc);

        ApiRecord a = new ApiRecord();
        a.setRepositoryName(REPO);
        a.setApiPath("/keep-a");
        a.setHttpMethod("GET");
        a.setStatus("사용");
        recordRepo.save(a);

        ApiRecord b = new ApiRecord();
        b.setRepositoryName(REPO);
        b.setApiPath("/keep-b");
        b.setHttpMethod("POST");
        b.setStatus("②-① 호출0건+변경있음");
        recordRepo.save(b);

        ApiRecordSnapshot snap = snapshotService.createSnapshot("TEST", "restore-test", null, "127.0.0.1");
        assertThat(recordRepo.count()).isEqualTo(2);

        ApiRecord extra = new ApiRecord();
        extra.setRepositoryName(REPO);
        extra.setApiPath("/extra-live-only");
        extra.setHttpMethod("GET");
        extra.setStatus("사용");
        recordRepo.save(extra);
        assertThat(recordRepo.count()).isEqualTo(3);

        var res = snapshotService.restoreLiveFromSnapshot(snap.getId());
        assertThat(res.get("insertedRows")).isEqualTo(2);
        assertThat(res.get("previousLiveRowsDeleted")).isEqualTo(3);

        assertThat(recordRepo.count()).isEqualTo(2);
        assertThat(recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/extra-live-only", "GET")).isEmpty();
        assertThat(recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/keep-a", "GET")).isPresent();
    }

    @Test
    @DisplayName("restoreLiveFromSnapshot — 스냅샷 행 0건이면 예외")
    void restoreLive_emptySnapshot_throws() {
        ApiRecordSnapshot s = new ApiRecordSnapshot();
        s.setSnapshotAt(LocalDateTime.now());
        s.setSnapshotType("EMPTY");
        s.setLabel("no-rows");
        s.setRecordCount(0L);
        s = snapshotRepository.save(s);
        final long emptySnapId = s.getId();

        assertThatThrownBy(() -> snapshotService.restoreLiveFromSnapshot(emptySnapId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("복구할 행이 없습니다");
    }
}
