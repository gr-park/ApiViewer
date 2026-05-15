package com.baek.viewer.integration;

import com.baek.viewer.model.ApiRecordSnapshot;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.ApiRecordSnapshotRowRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 풀 스냅샷에 status='삭제' api_record 행이 snapshot_row로 복제되는지 검증.
 */
@SpringBootTest
@Transactional
class SnapshotDeletedRowsIntegrationTest {

    private static final String REPO = "test-repo-snapshot-del";

    @Autowired
    private SnapshotService snapshotService;
    @Autowired
    private ApiRecordRepository recordRepo;
    @Autowired
    private RepoConfigRepository repoConfigRepo;
    @Autowired
    private ApiRecordSnapshotRowRepository snapshotRowRepo;

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

    @Test
    @DisplayName("createSnapshot — status=삭제 행도 snapshot_row에 포함")
    void createSnapshot_includesDeletedStatusRows() {
        ApiRecord deleted = new ApiRecord();
        deleted.setRepositoryName(REPO);
        deleted.setApiPath("/api/removed-endpoint");
        deleted.setHttpMethod("POST");
        deleted.setStatus("삭제");
        recordRepo.save(deleted);

        ApiRecordSnapshot snap = snapshotService.createSnapshot("TEST", "unit-test snapshot", null, "127.0.0.1");

        long deletedRows = snapshotRowRepo.pageByFilters(
                snap.getId(),
                null,
                "삭제",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 50)
        ).getTotalElements();

        assertThat(deletedRows).isGreaterThanOrEqualTo(1L);
        assertThat(snap.getRecordCount()).isGreaterThanOrEqualTo(1L);
    }
}
