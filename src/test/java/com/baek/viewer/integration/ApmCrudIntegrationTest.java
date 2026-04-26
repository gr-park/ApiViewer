package com.baek.viewer.integration;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.repository.ApmCallDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApmCallData CRUD + 집계 통합 테스트.
 *
 * 검증:
 *   - INSERT (단건/다건)
 *   - sumByRepoAndDateRange — 레포+기간 집계
 *   - bulkDeleteByRepo / bulkDeleteBySource
 */
@SpringBootTest
@Transactional
class ApmCrudIntegrationTest {

    private static final String REPO_A = "test-apm-A";
    private static final String REPO_B = "test-apm-B";

    @Autowired ApmCallDataRepository apmRepo;

    @BeforeEach
    void cleanup() {
        apmRepo.bulkDeleteByRepo(REPO_A);
        apmRepo.bulkDeleteByRepo(REPO_B);
    }

    private ApmCallData row(String repo, String path, LocalDate date, long count, String source) {
        ApmCallData d = new ApmCallData();
        d.setRepositoryName(repo);
        d.setApiPath(path);
        d.setCallDate(date);
        d.setCallCount(count);
        d.setErrorCount(0);
        d.setSource(source);
        return d;
    }

    @Test
    @DisplayName("INSERT — 단건 저장 후 ID 발급")
    void insertSingle() {
        ApmCallData saved = apmRepo.save(row(REPO_A, "/api/x", LocalDate.now(), 100, "MOCK"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCallCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("INSERT — 다건 저장 후 saveAll")
    void insertBatch() {
        LocalDate today = LocalDate.now();
        List<ApmCallData> rows = List.of(
                row(REPO_A, "/api/x", today, 10, "MOCK"),
                row(REPO_A, "/api/y", today, 20, "MOCK"),
                row(REPO_A, "/api/z", today, 30, "WHATAP")
        );
        apmRepo.saveAll(rows);
        // 3건 저장 확인
        long count = apmRepo.findAll().stream()
                .filter(r -> REPO_A.equals(r.getRepositoryName())).count();
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("sumByRepoAndDateRange — 레포+기간 합계 그룹화")
    void aggregateByRepoAndDateRange() {
        LocalDate d1 = LocalDate.now().minusDays(2);
        LocalDate d2 = LocalDate.now().minusDays(1);
        LocalDate d3 = LocalDate.now();

        apmRepo.saveAll(List.of(
                row(REPO_A, "/api/x", d1, 10, "MOCK"),
                row(REPO_A, "/api/x", d2, 20, "MOCK"),
                row(REPO_A, "/api/x", d3, 30, "MOCK"),
                row(REPO_A, "/api/y", d3, 5,  "MOCK"),
                // 다른 레포는 집계 제외
                row(REPO_B, "/api/x", d3, 999, "MOCK")
        ));

        // d1~d3 전체 합산 — 5 행 (api+date+source 그룹)
        List<Object[]> rows = apmRepo.sumByRepoAndDateRange(REPO_A, d1, d3);
        long totalCount = rows.stream()
                .mapToLong(r -> ((Number) r[3]).longValue())
                .sum();
        // /api/x: 60건, /api/y: 5건 — 합계 65
        assertThat(totalCount).isEqualTo(65);
    }

    @Test
    @DisplayName("sumByRepoAndDateRange — 기간 외 데이터 제외")
    void aggregateExcludesOutOfRange() {
        LocalDate d1 = LocalDate.now().minusDays(10);
        LocalDate d2 = LocalDate.now();

        apmRepo.saveAll(List.of(
                row(REPO_A, "/api/x", d1.minusDays(1), 100, "MOCK"),  // 기간 전
                row(REPO_A, "/api/x", d1,              10,  "MOCK"),
                row(REPO_A, "/api/x", d2,              20,  "MOCK"),
                row(REPO_A, "/api/x", d2.plusDays(1),  100, "MOCK")   // 기간 후
        ));

        List<Object[]> rows = apmRepo.sumByRepoAndDateRange(REPO_A, d1, d2);
        long totalCount = rows.stream()
                .mapToLong(r -> ((Number) r[3]).longValue())
                .sum();
        assertThat(totalCount).isEqualTo(30);
    }

    @Test
    @DisplayName("bulkDeleteByRepo — 지정 레포 데이터만 삭제")
    void bulkDeleteByRepo() {
        LocalDate today = LocalDate.now();
        apmRepo.saveAll(List.of(
                row(REPO_A, "/api/x", today, 10, "MOCK"),
                row(REPO_A, "/api/y", today, 20, "MOCK"),
                row(REPO_B, "/api/x", today, 30, "MOCK")
        ));

        int deleted = apmRepo.bulkDeleteByRepo(REPO_A);
        assertThat(deleted).isEqualTo(2);

        // REPO_B 데이터는 유지
        long bCount = apmRepo.findAll().stream()
                .filter(r -> REPO_B.equals(r.getRepositoryName())).count();
        assertThat(bCount).isEqualTo(1);
    }

    @Test
    @DisplayName("bulkDeleteByRepoAndSource — 레포+소스 정밀 삭제")
    void bulkDeleteByRepoAndSource() {
        LocalDate today = LocalDate.now();
        apmRepo.saveAll(List.of(
                row(REPO_A, "/api/x", today, 10, "MOCK"),
                row(REPO_A, "/api/y", today, 20, "WHATAP"),
                row(REPO_A, "/api/z", today, 30, "MOCK")
        ));

        int deleted = apmRepo.bulkDeleteByRepoAndSource(REPO_A, "MOCK");
        assertThat(deleted).isEqualTo(2);

        // WHATAP 만 남음
        long remaining = apmRepo.findAll().stream()
                .filter(r -> REPO_A.equals(r.getRepositoryName())).count();
        assertThat(remaining).isEqualTo(1);
    }
}
