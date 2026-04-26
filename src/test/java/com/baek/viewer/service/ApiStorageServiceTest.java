package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ApiStorageService 단위테스트.
 * save/updateBulk/updateCallCounts + 상태 계산 로직 (calculateStatus) 검증.
 * 실제 DB 대신 Repository mock 사용.
 */
@ExtendWith(MockitoExtension.class)
class ApiStorageServiceTest {

    @Mock
    private ApiRecordRepository repository;

    @Mock
    private GlobalConfigRepository globalConfigRepository;

    @Mock
    private RepoConfigRepository repoConfigRepository;

    @Mock
    private TestSuspectMatcher testSuspectMatcher;

    @InjectMocks
    private ApiStorageService service;

    private GlobalConfig defaultConfig() {
        GlobalConfig gc = new GlobalConfig();
        gc.setReviewThreshold(3);
        return gc;
    }

    // ═══════════════════ calculateStatus ═══════════════════

    @Test
    @DisplayName("calculateStatus — hasUrlBlock=Y → '①-① 차단완료'")
    void calculateStatus_allBlocked_returnsBlocked() {
        ApiRecord r = new ApiRecord();
        r.setIsDeprecated("Y");
        r.setHasUrlBlock("Y");
        r.setFullComment("[URL차단작업][2024-01-01] 침해사고");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("①-① 차단완료");
    }

    @Test
    @DisplayName("calculateStatus — 호출 0건 + 커밋 1년 경과 → '①-② 호출0건+변경없음'")
    void calculateStatus_zeroCallOldCommit() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(0L);
        LocalDate oldDate = LocalDate.now().minusYears(2);
        r.setGitHistory("[{\"date\":\"" + oldDate + "\",\"author\":\"a\",\"message\":\"m\"}]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("①-② 호출0건+변경없음");
    }

    @Test
    @DisplayName("calculateStatus — 호출 0건 + 커밋 1년 미만 + 비-로그성 → '②-② 호출0건+변경있음'")
    void calculateStatus_zeroCallRecentCommit() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(0L);
        LocalDate recent = LocalDate.now().minusDays(30);
        r.setGitHistory("[{\"date\":\"" + recent + "\",\"author\":\"a\",\"message\":\"기능 변경\"}]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("②-② 호출0건+변경있음");
    }

    @Test
    @DisplayName("calculateStatus — 호출 1~threshold + 커밋 1년 경과 → '②-③ 호출 1~reviewThreshold건'")
    void calculateStatus_lowCallOldCommit() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(2L);
        LocalDate oldDate = LocalDate.now().minusYears(2);
        r.setGitHistory("[{\"date\":\"" + oldDate + "\",\"author\":\"a\",\"message\":\"m\"}]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("②-③ 호출 1~reviewThreshold건");
    }

    @Test
    @DisplayName("calculateStatus — 호출 많고 커밋 최신이면 '사용'")
    void calculateStatus_normalUse() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(1000L);
        LocalDate recent = LocalDate.now().minusDays(10);
        r.setGitHistory("[{\"date\":\"" + recent + "\",\"author\":\"a\",\"message\":\"m\"}]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("사용");
    }

    @Test
    @DisplayName("calculateStatus — callCount null 도 0건으로 간주")
    void calculateStatus_nullCountTreatedAsZero() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(null);
        LocalDate oldDate = LocalDate.now().minusYears(2);
        r.setGitHistory("[{\"date\":\"" + oldDate + "\",\"author\":\"a\",\"message\":\"m\"}]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("①-② 호출0건+변경없음");
    }

    @Test
    @DisplayName("calculateStatus — git history 없으면 1년 미만 처리 → '②-②'")
    void calculateStatus_noGitHistory() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(0L);
        r.setGitHistory("[]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("②-② 호출0건+변경있음");
    }

    @Test
    @DisplayName("calculateStatus(threshold=3, upper=10) — 호출 5건 + 1년 경과 → '②-④ 호출 reviewThreshold+1건↑'")
    void calculateStatus_callBetweenThresholdAndUpper() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(5L);
        LocalDate oldDate = LocalDate.now().minusYears(2);
        r.setGitHistory("[{\"date\":\"" + oldDate + "\",\"author\":\"a\",\"message\":\"m\"}]");

        String status = service.calculateStatus(r, 3, 10);
        assertThat(status).isEqualTo("②-④ 호출 reviewThreshold+1건↑");
    }

    @Test
    @DisplayName("calculateStatus — 호출 0건 + 1년 미만 + 모든 커밋 로그성 → '②-① 호출0건+로그건'")
    void calculateStatus_recentLogOnlyTrue() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(0L);
        LocalDate recent = LocalDate.now().minusMonths(6);
        r.setGitHistory("[{\"date\":\"" + recent + "\",\"author\":\"a\",\"message\":\"침해사고 로그 패치\"},"
                + "{\"date\":\"" + recent + "\",\"author\":\"b\",\"message\":\"불필요 코드 정리\"}]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("②-① 호출0건+로그건");
        assertThat(r.isRecentLogOnly()).isTrue();
    }

    @Test
    @DisplayName("calculateStatus — 호출 0건 + 1년 미만 + 비-로그성 커밋 1건 → '②-② 호출0건+변경있음'")
    void calculateStatus_recentLogOnlyFalseWhenAnyBizCommit() {
        ApiRecord r = new ApiRecord();
        r.setCallCount(0L);
        LocalDate recent = LocalDate.now().minusMonths(6);
        r.setGitHistory("[{\"date\":\"" + recent + "\",\"author\":\"a\",\"message\":\"기능 추가\"},"
                + "{\"date\":\"" + recent + "\",\"author\":\"b\",\"message\":\"로그 추가\"}]");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("②-② 호출0건+변경있음");
        assertThat(r.isRecentLogOnly()).isFalse();
    }

    @Test
    @DisplayName("calculateStatus sticky — 현재 ①-② 인 상태에서 호출 1건 발생 → 보존 (①-② 유지)")
    void calculateStatus_stickyBlockUmbrella() {
        ApiRecord r = new ApiRecord();
        r.setStatus("①-② 호출0건+변경없음");
        r.setCallCount(1L);  // 1년경과 → 원래라면 ②-③
        LocalDate oldDate = LocalDate.now().minusYears(2);
        r.setGitHistory("[{\"date\":\"" + oldDate + "\",\"author\":\"a\",\"message\":\"m\"}]");

        String status = service.calculateStatus(r, 3, 10);
        assertThat(status).isEqualTo("①-② 호출0건+변경없음");  // umbrella 내 보존
    }

    @Test
    @DisplayName("calculateStatus sticky — 현재 ①-② 인데 호출 100건 발생 → '사용' 으로 전이")
    void calculateStatus_stickyBlockToUse() {
        ApiRecord r = new ApiRecord();
        r.setStatus("①-② 호출0건+변경없음");
        r.setCallCount(100L);  // 충분 → target=USE
        LocalDate recent = LocalDate.now().minusDays(10);
        r.setGitHistory("[{\"date\":\"" + recent + "\",\"author\":\"a\",\"message\":\"m\"}]");

        String status = service.calculateStatus(r, 3, 10);
        assertThat(status).isEqualTo("사용");
    }

    @Test
    @DisplayName("calculateStatus — reviewResult='차단대상 제외' → '①-⑤ 현업요청 차단제외'")
    void calculateStatus_reviewExcluded() {
        ApiRecord r = new ApiRecord();
        r.setStatus("사용");
        r.setReviewResult("차단대상 제외");

        String status = service.calculateStatus(r, 3);
        assertThat(status).isEqualTo("①-⑤ 현업요청 차단제외");
    }

    // ═══════════════════ save ═══════════════════

    @Test
    @DisplayName("save — 신규 API 는 INSERT")
    void save_newApi_inserts() {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repoConfigRepository.findByRepoName(anyString())).thenReturn(Optional.empty());
        when(repository.findByRepositoryName("repo")).thenReturn(List.of());
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        ApiInfo info = new ApiInfo();
        info.setApiPath("/api/hello");
        info.setHttpMethod("GET");
        info.setMethodName("hello");
        info.setRepoPath("src/Hello.java");
        info.setIsDeprecated("N");
        info.setHasUrlBlock("N");

        int saved = service.save("repo", List.of(info), "127.0.0.1")[0];

        assertThat(saved).isEqualTo(1);
        verify(repository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    @DisplayName("save — 기존 + 차단완료 건은 SKIP")
    void save_existingBlocked_skipped() {
        ApiRecord blocked = new ApiRecord();
        blocked.setRepositoryName("repo");
        blocked.setApiPath("/api/x");
        blocked.setHttpMethod("GET");
        blocked.setStatus("①-① 차단완료");
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repoConfigRepository.findByRepoName(anyString())).thenReturn(Optional.empty());
        // allInRepo: 같은 건을 차단완료 상태로 반환
        when(repository.findByRepositoryName("repo")).thenReturn(List.of(blocked));

        ApiInfo info = new ApiInfo();
        info.setApiPath("/api/x");
        info.setHttpMethod("GET");
        info.setIsDeprecated("Y");
        info.setHasUrlBlock("Y");

        int saved = service.save("repo", List.of(info), "127.0.0.1")[0];

        // 차단완료 건은 save 건너뜀
        assertThat(saved).isEqualTo(0);
        // 변경 저장 없음 (insert/update/delete 모두 없음)
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("save — 소스에서 사라진 API 는 '삭제' 상태로 전환")
    void save_missingApi_markedAsDeleted() {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repoConfigRepository.findByRepoName(anyString())).thenReturn(Optional.empty());

        ApiRecord oldRec = new ApiRecord();
        oldRec.setRepositoryName("repo");
        oldRec.setApiPath("/api/old");
        oldRec.setHttpMethod("GET");
        oldRec.setStatus("사용");
        when(repository.findByRepositoryName("repo")).thenReturn(List.of(oldRec));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // 빈 리스트로 save → 기존 /api/old 는 소스에 없음 → 삭제 처리
        service.save("repo", List.of(), "1.1.1.1");

        assertThat(oldRec.getStatus()).isEqualTo("삭제");
        assertThat(oldRec.isStatusOverridden()).isTrue();
        verify(repository).saveAll(anyList());
    }

    // ═══════════════════ updateBulk ═══════════════════

    @Test
    @DisplayName("updateBulk — 확정완료(statusOverridden=true) 건은 일체 수정 불가")
    void updateBulk_blocked_notModified() {
        ApiRecord blocked = new ApiRecord();
        blocked.setStatus("사용");
        blocked.setStatusOverridden(true);
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repository.findAllById(anyList())).thenReturn(List.of(blocked));

        int updated = service.updateBulk(List.of(1L), Map.of("blockTarget", "최우선"), "1.1.1.1");

        assertThat(updated).isEqualTo(0);
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("updateBulk — 차단완료 건도 statusOverridden=false이면 수정 가능")
    void updateBulk_blockedStatusEditable() {
        ApiRecord r = new ApiRecord();
        r.setStatus("①-① 차단완료");
        r.setStatusOverridden(false);
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repository.findAllById(anyList())).thenReturn(List.of(r));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int updated = service.updateBulk(List.of(1L), Map.of("blockTarget", "①-② 호출0건+변경없음"), "1.1.1.1");

        assertThat(updated).isEqualTo(1);
        assertThat(r.getBlockTarget()).isEqualTo("①-② 호출0건+변경없음");
    }

    @Test
    @DisplayName("updateBulk — blockTarget 설정")
    void updateBulk_setBlockTarget() {
        ApiRecord r = new ApiRecord();
        r.setStatus("사용");
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repository.findAllById(anyList())).thenReturn(List.of(r));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int updated = service.updateBulk(List.of(1L), Map.of("blockTarget", "①-② 호출0건+변경없음"), "1.1.1.1");

        assertThat(updated).isEqualTo(1);
        assertThat(r.getBlockTarget()).isEqualTo("①-② 호출0건+변경없음");
    }

    @Test
    @DisplayName("updateBulk — statusOverridden=true 인 건은 status 필드 무시")
    void updateBulk_overridden_ignoresStatus() {
        ApiRecord r = new ApiRecord();
        r.setStatus("사용");
        r.setStatusOverridden(true);
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repository.findAllById(anyList())).thenReturn(List.of(r));

        service.updateBulk(List.of(1L), Map.of("status", "②-③ 호출 1~reviewThreshold건"), "ip");

        // statusOverridden=true 이고 statusOverridden 필드도 함께 오지 않았으므로 변경 skip
        assertThat(r.getStatus()).isEqualTo("사용");
    }

    // ═══════════════════ updateCallCounts ═══════════════════

    @Test
    @DisplayName("updateCallCounts — 매핑된 apiPath 의 callCount 갱신")
    void updateCallCounts_updatesMatchingRecords() {
        ApiRecord r1 = new ApiRecord();
        r1.setApiPath("/api/a");
        r1.setCallCount(0L);
        r1.setStatus("사용");
        ApiRecord r2 = new ApiRecord();
        r2.setApiPath("/api/b");
        r2.setCallCount(5L);
        r2.setStatus("사용");
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repository.findByRepositoryName("repo")).thenReturn(List.of(r1, r2));
        when(repository.save(any(ApiRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateCallCounts("repo", Map.of("/api/a", 100L, "/api/b", 200L));

        assertThat(r1.getCallCount()).isEqualTo(100L);
        assertThat(r2.getCallCount()).isEqualTo(200L);
    }

    @Test
    @DisplayName("updateCallCounts — 차단완료 건은 건드리지 않음")
    void updateCallCounts_blockedSkipped() {
        ApiRecord blocked = new ApiRecord();
        blocked.setApiPath("/api/b");
        blocked.setStatus("①-① 차단완료");
        blocked.setCallCount(0L);
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(defaultConfig()));
        when(repository.findByRepositoryName("repo")).thenReturn(List.of(blocked));

        service.updateCallCounts("repo", Map.of("/api/b", 999L));

        assertThat(blocked.getCallCount()).isZero();
        verify(repository, never()).save(any(ApiRecord.class));
    }

    // ═══════════════════ statusChangeLog FIFO trimming ═══════════════════

    @Test
    @DisplayName("appendChangeLog FIFO — 50개 초과 시 가장 오래된 항목부터 제거")
    void appendChangeLog_trimsToMax() throws Exception {
        ApiRecord r = new ApiRecord();
        // 60개 push → 가장 최근 50개만 보존
        java.lang.reflect.Method m = ApiStorageService.class.getDeclaredMethod("appendChangeLog", ApiRecord.class, String.class);
        m.setAccessible(true);
        for (int i = 1; i <= 60; i++) {
            m.invoke(service, r, "msg-" + i);
        }
        String log = r.getStatusChangeLog();
        String[] parts = log.split("\\s\\|\\s");
        assertThat(parts).hasSize(ApiStorageService.MAX_CHANGE_LOG_ENTRIES);
        // 가장 오래된 (msg-1 ~ msg-10) 은 제거되고 msg-11 ~ msg-60 만 남음
        assertThat(parts[0]).isEqualTo("msg-11");
        assertThat(parts[parts.length - 1]).isEqualTo("msg-60");
        assertThat(r.isStatusChanged()).isTrue();
    }

    @Test
    @DisplayName("appendChangeLog — 50개 미만은 그대로 보존")
    void appendChangeLog_keepsAllUnderLimit() throws Exception {
        ApiRecord r = new ApiRecord();
        java.lang.reflect.Method m = ApiStorageService.class.getDeclaredMethod("appendChangeLog", ApiRecord.class, String.class);
        m.setAccessible(true);
        for (int i = 1; i <= 10; i++) {
            m.invoke(service, r, "msg-" + i);
        }
        String[] parts = r.getStatusChangeLog().split("\\s\\|\\s");
        assertThat(parts).hasSize(10);
        assertThat(parts[0]).isEqualTo("msg-1");
        assertThat(parts[9]).isEqualTo("msg-10");
    }
}
