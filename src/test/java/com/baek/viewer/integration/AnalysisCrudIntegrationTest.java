package com.baek.viewer.integration;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분석(추출) CRUD 통합 테스트.
 *
 * 검증:
 *   - 신규 레코드 INSERT
 *   - 기존 레코드 추출 시 필드 업데이트 (필요한 필드만)
 *   - umbrella sticky: ① 차단대상 / ② 추가검토대상 leaf 보존
 *   - 차단완료 레코드는 SKIP (수정 없음)
 *   - DB에 있지만 추출 결과에 없는 건 → '삭제' 마킹
 *   - statusOverridden=true 레코드는 자동 재계산 미적용
 */
@SpringBootTest
@Transactional
class AnalysisCrudIntegrationTest {

    private static final String REPO = "test-repo-A";

    @Autowired ApiStorageService storage;
    @Autowired ApiRecordRepository recordRepo;
    @Autowired GlobalConfigRepository globalConfigRepo;

    @BeforeEach
    void cleanup() {
        recordRepo.deleteByRepositoryName(REPO);
    }

    private ApiInfo info(String path, String method) {
        ApiInfo a = new ApiInfo();
        a.setApiPath(path);
        a.setHttpMethod(method);
        a.setMethodName("handle");
        a.setControllerName("TestController");
        a.setRepoPath("src/main/java/Test.java");
        a.setIsDeprecated("N");
        // git1: 1년 경과 커밋 (callZero + fullOld → ①-②)
        LocalDate old = LocalDate.now().minusYears(2);
        a.setGit1(new String[]{old.toString(), "alice", "old commit"});
        return a;
    }

    @Test
    @DisplayName("save — 신규 API 1건 INSERT")
    void save_newRecord_inserts() {
        ApiInfo a = info("/api/x", "GET");
        int[] result = storage.save(REPO, List.of(a), "1.1.1.1");
        assertThat(result[0]).isEqualTo(1);

        Optional<ApiRecord> saved = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/x", "GET");
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo("①-① 차단대상");
        assertThat(saved.get().isNew()).isTrue();
    }

    @Test
    @DisplayName("save — 동일 API 재추출 시 신규 플래그 해제 + 추출 필드만 업데이트")
    void save_existingRecord_updatesExtractedFields() {
        // 1차 분석
        storage.save(REPO, List.of(info("/api/y", "GET")), "1.1.1.1");

        // 사용자가 비고 등 수정
        ApiRecord existing = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/y", "GET").orElseThrow();
        existing.setMemo("사용자 메모");
        existing.setBlockCriteria("미사용");
        recordRepo.save(existing);

        // 2차 분석
        storage.save(REPO, List.of(info("/api/y", "GET")), "1.1.1.1");

        ApiRecord after = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/y", "GET").orElseThrow();
        assertThat(after.isNew()).isFalse();
        // 사용자 입력 필드는 보존
        assertThat(after.getMemo()).isEqualTo("사용자 메모");
        assertThat(after.getBlockCriteria()).isEqualTo("미사용");
    }

    @Test
    @DisplayName("save — 차단완료 레코드는 재추출 시 SKIP")
    void save_blockedRecord_skipped() {
        // 차단완료 상태 레코드 사전 입력
        ApiRecord blocked = new ApiRecord();
        blocked.setRepositoryName(REPO);
        blocked.setApiPath("/api/blocked");
        blocked.setHttpMethod("GET");
        blocked.setStatus("차단완료");
        blocked.setMemo("차단 메모");
        recordRepo.save(blocked);

        // 동일 path 재추출
        storage.save(REPO, List.of(info("/api/blocked", "GET")), "1.1.1.1");

        ApiRecord after = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/blocked", "GET").orElseThrow();
        assertThat(after.getStatus()).isEqualTo("차단완료");
        assertThat(after.getMemo()).isEqualTo("차단 메모");  // 변경 없음
    }

    @Test
    @DisplayName("save — DB에 있지만 추출 결과에 없는 건은 '삭제' 마킹")
    void save_missingApi_markedDeleted() {
        // 1차 — 2건 INSERT
        storage.save(REPO, List.of(info("/api/keep", "GET"), info("/api/gone", "GET")), "1.1.1.1");

        // 2차 — 1건만 추출
        storage.save(REPO, List.of(info("/api/keep", "GET")), "1.1.1.1");

        ApiRecord gone = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/gone", "GET").orElseThrow();
        assertThat(gone.getStatus()).isEqualTo("삭제");
        assertThat(gone.isStatusOverridden()).isTrue();
    }

    @Test
    @DisplayName("save sticky — 현재 ①-② 인 레코드에 호출건수 증가해도 leaf 보존")
    void save_umbrellaStickyBlock_preserved() {
        // 1차 — ①-② 분류
        storage.save(REPO, List.of(info("/api/sticky", "GET")), "1.1.1.1");
        ApiRecord r1 = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/sticky", "GET").orElseThrow();
        assertThat(r1.getStatus()).isEqualTo("①-① 차단대상");

        // 호출건수 발생 (umbrella 내부에 머무는 한 leaf 보존)
        r1.setCallCount(2L);
        recordRepo.save(r1);

        // 2차 분석 — 조건상 ②-③ 매칭이지만, 현재가 ①-② 이므로 보존
        storage.save(REPO, List.of(info("/api/sticky", "GET")), "1.1.1.1");
        ApiRecord r2 = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/sticky", "GET").orElseThrow();
        assertThat(r2.getStatus()).isEqualTo("①-① 차단대상");
    }

    @Test
    @DisplayName("save sticky — 현재 ①-② 인 레코드에 호출 다수 + 최신 커밋 → '사용' 으로 전이 허용")
    void save_umbrellaStickyToUse() {
        storage.save(REPO, List.of(info("/api/use", "GET")), "1.1.1.1");
        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/use", "GET").orElseThrow();
        r.setCallCount(1000L);
        recordRepo.save(r);

        // 1년 미만 커밋 + 호출 다수 → target=USE → '사용' 전이
        ApiInfo recent = info("/api/use", "GET");
        recent.setGit1(new String[]{LocalDate.now().minusDays(10).toString(), "a", "recent"});
        storage.save(REPO, List.of(recent), "1.1.1.1");

        ApiRecord after = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/use", "GET").orElseThrow();
        assertThat(after.getStatus()).isEqualTo("사용");
    }

    @Test
    @DisplayName("save — statusOverridden=true 레코드는 자동 재계산 적용 안 됨")
    void save_statusOverridden_preserved() {
        storage.save(REPO, List.of(info("/api/manual", "GET")), "1.1.1.1");
        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/manual", "GET").orElseThrow();
        r.setStatus("①-② 담당자 판단");
        r.setStatusOverridden(true);
        recordRepo.save(r);

        // 재분석
        storage.save(REPO, List.of(info("/api/manual", "GET")), "1.1.1.1");
        ApiRecord after = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/manual", "GET").orElseThrow();
        assertThat(after.getStatus()).isEqualTo("①-② 담당자 판단");
        assertThat(after.isStatusOverridden()).isTrue();
    }

    @Test
    @DisplayName("save — 테스트 키워드 매칭 시 testSuspectReason 자동 세팅")
    void save_testSuspectKeywordMatched() {
        ApiInfo a = info("/api/test/foo", "GET");
        a.setMethodName("getSampleUser");
        storage.save(REPO, List.of(a), "1.1.1.1");

        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/test/foo", "GET").orElseThrow();
        assertThat(r.getTestSuspectReason()).isNotNull();
        assertThat(r.getTestSuspectReason()).contains("URL-test");
        assertThat(r.getTestSuspectReason()).contains("메소드-sample");
    }

    @Test
    @DisplayName("save — 테스트 키워드 미매칭 시 testSuspectReason null")
    void save_testSuspectNotMatched() {
        // 명시적으로 모든 필드를 키워드 미포함으로 세팅 (info() 헬퍼는 Test.java 컨트롤러 사용)
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/users/profile");
        a.setHttpMethod("GET");
        a.setMethodName("getUserProfile");
        a.setControllerName("UserController");
        a.setRepoPath("src/main/java/UserController.java");
        a.setIsDeprecated("N");
        a.setGit1(new String[]{LocalDate.now().minusYears(2).toString(), "alice", "old commit"});

        storage.save(REPO, List.of(a), "1.1.1.1");

        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/users/profile", "GET").orElseThrow();
        assertThat(r.getTestSuspectReason()).isNull();
    }

    @Test
    @DisplayName("updateBulk — 수동 leaf 선택 시 statusOverridden 자동 ON")
    void updateBulk_manualLeaf_setsOverridden() {
        storage.save(REPO, List.of(info("/api/bulk", "GET")), "1.1.1.1");
        ApiRecord r = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO, "/api/bulk", "GET").orElseThrow();

        int updated = storage.updateBulk(List.of(r.getId()), Map.of("status", "①-④ 사용으로 변경"), "1.1.1.1");
        assertThat(updated).isEqualTo(1);

        ApiRecord after = recordRepo.findById(r.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("①-④ 사용으로 변경");
        assertThat(after.isStatusOverridden()).isTrue();
    }
}
