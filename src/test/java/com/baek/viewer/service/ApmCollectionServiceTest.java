package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.ApmCallDataRepository;
import com.baek.viewer.repository.ApmUrlStatRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ApmCollectionService 단위테스트.
 * self-proxy 필드 때문에 @InjectMocks 대신 수동 생성자 주입.
 */
@ExtendWith(MockitoExtension.class)
class ApmCollectionServiceTest {

    @Mock
    private ApmCallDataRepository apmRepo;

    @Mock
    private ApmUrlStatRepository apmUrlStatRepo;

    @Mock
    private ApiRecordRepository apiRecordRepo;

    @Mock
    private RepoConfigRepository repoConfigRepo;

    // WhatapApmService / JenniferApmService 는 HttpClient final 필드 때문에
    // Mockito inline mock 이 Java 25 환경에서 실패 — 실제 인스턴스 주입 후 테스트에서는 호출하지 않음
    private WhatapApmService whatapApmService;
    private JenniferApmService jenniferApmService;

    private ApmCollectionService service;

    @BeforeEach
    void setUp() {
        whatapApmService = new WhatapApmService(apmRepo, null);
        jenniferApmService = new JenniferApmService(apmRepo, null);
        service = new ApmCollectionService(apmRepo, apmUrlStatRepo, apiRecordRepo, repoConfigRepo,
                whatapApmService, jenniferApmService);
    }

    // ═══════════════════ 파라미터 검증 ═══════════════════

    @Test
    @DisplayName("generateMockDataByRange — from/to null 이면 IllegalArgumentException")
    void generateMockDataByRange_nullDate_throws() {
        assertThatThrownBy(() -> service.generateMockDataByRange("repo", null, LocalDate.now(), "MOCK"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from/to");
    }

    @Test
    @DisplayName("generateMockDataByRange — from > to 이면 IllegalArgumentException")
    void generateMockDataByRange_fromAfterTo_throws() {
        assertThatThrownBy(() ->
                service.generateMockDataByRange("repo", LocalDate.now(), LocalDate.now().minusDays(1), "MOCK"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("generateMockDataByRange — JENNIFER 30일 초과 시 예외")
    void generateMockDataByRange_jennifer_over30days_throws() {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(31);
        assertThatThrownBy(() -> service.generateMockDataByRange("repo", from, to, "JENNIFER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30");
    }

    @Test
    @DisplayName("generateMockDataByRange — MOCK 365일 초과 시 예외")
    void generateMockDataByRange_mock_over365days_throws() {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(366);
        assertThatThrownBy(() -> service.generateMockDataByRange("repo", from, to, "MOCK"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("365");
    }

    // ═══════════════════ addApmLog / logs ═══════════════════

    @Test
    @DisplayName("addApmLog — 레벨별로 로그가 누적")
    void addApmLog_accumulates() {
        service.addApmLog("INFO", "hello");
        service.addApmLog("WARN", "careful");

        List<String> logs = service.getApmLogs();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0)).contains("[INFO]").contains("hello");
        assertThat(logs.get(1)).contains("[WARN]").contains("careful");
    }

    @Test
    @DisplayName("isApmCollecting — 초기값 false")
    void isApmCollecting_initiallyFalse() {
        assertThat(service.isApmCollecting()).isFalse();
    }

    // ═══════════════════ generateMockData (레포 empty 분기) ═══════════════════

    @Test
    @DisplayName("generateMockData — 레포에 API 없으면 generated=0 즉시 반환")
    void generateMockData_emptyRepo_returnsZero() {
        when(apiRecordRepo.findByRepositoryName("empty")).thenReturn(List.of());

        Map<String, Object> result = service.generateMockData("empty", 7);

        assertThat(result).containsEntry("generated", 0);
        verify(apmRepo, never()).save(any());
    }

    // ═══════════════════ aggregateToRecords ═══════════════════

    @Test
    @DisplayName("aggregateToRecords — APM 데이터가 없고 레코드만 있을 때 zero 처리 안 함")
    void aggregateToRecords_noApmDataKeepsUntouched() {
        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/a");
        r.setCallCount(100L);
        when(apmRepo.sumByRepoAndDateRange(anyString(), any(), any())).thenReturn(List.of());
        when(apiRecordRepo.findByRepositoryName("repo")).thenReturn(List.of(r));

        Map<String, Object> result = service.aggregateToRecords("repo");

        assertThat(result).containsEntry("updated", 0);
        assertThat(result).containsEntry("zeroed", 0);
        // callCount 변경 없음
        assertThat(r.getCallCount()).isEqualTo(100L);
    }

    @Test
    @DisplayName("aggregateToRecords — APM 데이터 존재 + 매칭되는 레코드 업데이트")
    void aggregateToRecords_updatesMatching() {
        LocalDate today = LocalDate.now();
        // [apiPath, callDate, source, callCount, errorCount]
        Object[] row = new Object[]{"/api/a", today, "MOCK", 50L, 1L};
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);
        when(apmRepo.sumByRepoAndDateRange(anyString(), any(), any())).thenReturn(rows);

        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/a");
        r.setCallCount(0L);
        when(apiRecordRepo.findByRepositoryName("repo")).thenReturn(List.of(r));
        when(apiRecordRepo.save(any(ApiRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.aggregateToRecords("repo");

        assertThat(result).containsEntry("updated", 1);
        assertThat(r.getCallCount()).isEqualTo(50L);
    }

    // ═══════════════════ deleteMockData ═══════════════════

    @Test
    @DisplayName("deleteMockData — repo/source ALL 이면 bulkDeleteAll (TRUNCATE)")
    void deleteMockData_all_callsBulkDeleteAll() {
        when(apmRepo.count()).thenReturn(1000L);
        org.mockito.Mockito.doNothing().when(apmRepo).bulkDeleteAll();

        Map<String, Object> result = service.deleteMockData("ALL", "ALL");

        assertThat(result).containsEntry("deleted", 1000);
        verify(apmRepo).count();
        verify(apmRepo).bulkDeleteAll();
    }

    @Test
    @DisplayName("deleteMockData — repo 지정 + source ALL 이면 bulkDeleteByRepo")
    void deleteMockData_byRepoOnly() {
        when(apmRepo.bulkDeleteByRepo("repo1")).thenReturn(50);

        Map<String, Object> result = service.deleteMockData("repo1", null);

        assertThat(result).containsEntry("deleted", 50);
        verify(apmRepo).bulkDeleteByRepo("repo1");
    }

    @Test
    @DisplayName("deleteMockData — repo+source 모두 지정")
    void deleteMockData_byRepoAndSource() {
        when(apmRepo.bulkDeleteByRepoAndSource("repo1", "WHATAP")).thenReturn(20);

        Map<String, Object> result = service.deleteMockData("repo1", "whatap");

        assertThat(result).containsEntry("deleted", 20);
        verify(apmRepo).bulkDeleteByRepoAndSource("repo1", "WHATAP");
    }

    // ═══════════════════ resetCallCounts ═══════════════════

    @Test
    @DisplayName("resetCallCounts — 0이 아닌 레코드만 리셋")
    void resetCallCounts_resetsNonZero() {
        ApiRecord r1 = new ApiRecord();
        r1.setCallCount(10L);
        r1.setCallCountMonth(5L);
        r1.setCallCountWeek(1L);
        ApiRecord r2 = new ApiRecord();
        r2.setCallCount(0L); // 변경 필요 없음
        r2.setCallCountMonth(0L);
        r2.setCallCountWeek(0L);

        when(apiRecordRepo.findByRepositoryName("repo")).thenReturn(List.of(r1, r2));

        service.resetCallCounts("repo");

        assertThat(r1.getCallCount()).isZero();
        verify(apiRecordRepo, times(1)).save(any(ApiRecord.class));
    }

    // ═══════════════════ getCallData ═══════════════════

    @Test
    @DisplayName("getCallData — source 'ALL' 지정 시 전체 기간 조회")
    void getCallData_all_callsFindByRepoAndDate() {
        LocalDate from = LocalDate.now().minusDays(7);
        LocalDate to = LocalDate.now();
        when(apmRepo.findByRepositoryNameAndCallDateBetweenOrderByCallDateDesc("repo", from, to))
                .thenReturn(List.of());

        List<ApmCallData> result = service.getCallData("repo", from, to, "ALL");

        assertThat(result).isEmpty();
        verify(apmRepo).findByRepositoryNameAndCallDateBetweenOrderByCallDateDesc("repo", from, to);
    }

    @Test
    @DisplayName("getCallData — source 지정 시 정규화 후 조회")
    void getCallData_withSource() {
        LocalDate from = LocalDate.now().minusDays(7);
        LocalDate to = LocalDate.now();
        when(apmRepo.findByRepositoryNameAndSourceAndCallDateBetweenOrderByCallDateDesc("repo", "WHATAP", from, to))
                .thenReturn(List.of());

        service.getCallData("repo", from, to, "whatap");

        verify(apmRepo).findByRepositoryNameAndSourceAndCallDateBetweenOrderByCallDateDesc("repo", "WHATAP", from, to);
    }

    // ═══════════════════ getChartData ═══════════════════

    @Test
    @DisplayName("getChartData — daily 버킷 생성")
    void getChartData_daily() {
        when(apmRepo.findByRepositoryNameAndApiPathAndCallDateBetweenOrderByCallDateAsc(anyString(), anyString(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = service.getChartData("repo", "/api/x", "daily", 7);

        assertThat(result).containsEntry("repoName", "repo");
        assertThat(result).containsEntry("apiPath", "/api/x");
        assertThat(result).containsEntry("bucket", "daily");
        assertThat(result).containsKey("buckets");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) result.get("buckets");
        assertThat(buckets).hasSize(7);
    }
}
