package com.baek.viewer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TestDataSeedService 단위테스트.
 * JdbcTemplate 는 mock — 실제 INSERT는 수행하지 않고, 호출 파라미터/횟수/SQL 내용만 검증.
 */
@ExtendWith(MockitoExtension.class)
class TestDataSeedServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private TestDataSeedService service;

    @Test
    @DisplayName("buildApiRows — 지정 개수만큼 생성되고, 상태 분포/prefix/unique 키가 올바름")
    void buildApiRows_generatesCorrectDistribution() {
        List<TestDataSeedService.ApiRow> rows = service.buildApiRows(100);

        assertThat(rows).hasSize(100);
        // 분포 — 사용 60 / (2)-(1) 3 / (2)-(2) 5 / (2)-(3) 4 / (2)-(4) 3 / (1)-(2) 5 / (1)-(3) 5 / (1)-(4) 8 / (1)-(1) 7
        assertThat(rows.stream().filter(r -> "사용".equals(r.status)).count()).isEqualTo(60);
        assertThat(rows.stream().filter(r -> r.status.startsWith("(2)-")).count()).isEqualTo(15);
        assertThat(rows.stream().filter(r -> "(1)-(2) 호출0건+변경없음".equals(r.status)).count()).isEqualTo(5);
        assertThat(rows.stream().filter(r -> "(1)-(3) 호출0건+변경있음(로그)".equals(r.status)).count()).isEqualTo(5);
        assertThat(rows.stream().filter(r -> "(1)-(4) 업무종료".equals(r.status)).count()).isEqualTo(8);
        assertThat(rows.stream().filter(r -> "(1)-(1) 차단완료".equals(r.status)).count()).isEqualTo(7);

        // 모든 레포 prefix 일치
        assertThat(rows).allMatch(r -> r.repo.startsWith(TestDataSeedService.TEST_REPO_PREFIX));
        // (1)-(1) 차단완료는 deprecated + hasUrlBlock 양성
        assertThat(rows.stream().filter(r -> "(1)-(1) 차단완료".equals(r.status)))
                .allMatch(r -> "Y".equals(r.isDeprecated) && "Y".equals(r.hasUrlBlock));
        // api_path unique
        assertThat(rows.stream().map(r -> r.apiPath).distinct().count()).isEqualTo(100);
    }

    @Test
    @DisplayName("seed(clean=true) — 기존 test-repo-* DELETE 2회 + api/apm INSERT batch + 집계 UPDATE 호출")
    void seed_cleanTrue_performsDeleteInsertUpdate() {
        // cleanFirst DELETE + 집계 UPDATE 모두 jdbc.update(String, Object...) 경로
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        // 단일 인자 오버로드도 호환
        when(jdbc.update(startsWith("DELETE"), any(Object.class))).thenReturn(0);

        Map<String, Object> result = service.seed(10, 2, true);

        // cleanFirst로 인해 DELETE 2회
        verify(jdbc, times(1)).update(startsWith("DELETE FROM apm_call_data"), eq(TestDataSeedService.TEST_REPO_PREFIX + "%"));
        verify(jdbc, times(1)).update(startsWith("DELETE FROM api_record"), eq(TestDataSeedService.TEST_REPO_PREFIX + "%"));

        // api_record batch insert — 10건은 한 batch
        verify(jdbc, times(1)).batchUpdate(contains("INSERT INTO api_record"), any(BatchPreparedStatementSetter.class));

        // apm_call_data batch insert — 10 × 2 = 20건 < APM_BATCH(5000)이므로 flush 1회
        verify(jdbc, atLeastOnce()).batchUpdate(contains("INSERT INTO apm_call_data"), any(List.class));

        // 집계 UPDATE 1회 (SQL 본문에 UPDATE api_record)
        verify(jdbc, times(1)).update(contains("UPDATE api_record"), any(), any(), eq(TestDataSeedService.TEST_REPO_PREFIX + "%"));

        // 응답 통계 검증
        assertThat(result).containsEntry("apiRecordInserted", 10);
        assertThat(result).containsEntry("apmCallDataInserted", 20L);
        assertThat(result).containsEntry("days", 2);
        assertThat(result).containsKeys("apiInsertMs", "apmInsertMs", "aggregateMs", "totalMs", "reposCount");
    }

    @Test
    @DisplayName("seed(clean=false) — 사전 DELETE는 수행되지 않음")
    void seed_cleanFalse_skipsDelete() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        service.seed(5, 1, false);

        // cleanFirst=false → DELETE 호출 없음
        verify(jdbc, times(0)).update(startsWith("DELETE FROM apm_call_data"), eq(TestDataSeedService.TEST_REPO_PREFIX + "%"));
        verify(jdbc, times(0)).update(startsWith("DELETE FROM api_record"), eq(TestDataSeedService.TEST_REPO_PREFIX + "%"));
    }

    @Test
    @DisplayName("cleanTestData — test-repo-* DELETE 2건 수행 후 결과 맵 반환")
    void cleanTestData_deletesBothTables() {
        when(jdbc.update(startsWith("DELETE FROM apm_call_data"), eq(TestDataSeedService.TEST_REPO_PREFIX + "%"))).thenReturn(1500);
        when(jdbc.update(startsWith("DELETE FROM api_record"), eq(TestDataSeedService.TEST_REPO_PREFIX + "%"))).thenReturn(300);

        Map<String, Object> result = service.cleanTestData();

        assertThat(result).containsEntry("apiRecordDeleted", 300);
        assertThat(result).containsEntry("apmCallDataDeleted", 1500);
    }

    @Test
    @DisplayName("seed — APM 배치 플러시: 5000건을 넘으면 batchUpdate 2회 이상 호출")
    void seed_apmBatchFlush() {
        lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        // 서비스는 버퍼 List 를 재사용/clear 하므로, 호출 시점의 size 를 스냅샷으로 기록해야 한다.
        // Mockito 의 batchUpdate(String, List) 과 batchUpdate(String, BatchPreparedStatementSetter) 오버로드
        // 모호성 회피를 위해 doAnswer + lenient 사용.
        List<Integer> sizes = new ArrayList<>();
        AtomicInteger apmCalls = new AtomicInteger();
        lenient().doAnswer(inv -> {
            List<?> arg = inv.getArgument(1);
            sizes.add(arg.size());
            apmCalls.incrementAndGet();
            return new int[arg.size()];
        }).when(jdbc).batchUpdate(contains("INSERT INTO apm_call_data"), anyList());

        // 10 api × 600 days = 6000건 — APM_BATCH(5000) 초과 → batchUpdate 2회 이상
        Map<String, Object> result = service.seed(10, 600, false);

        int total = sizes.stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo(6000);
        assertThat(apmCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(result).containsEntry("apmCallDataInserted", 6000L);
    }
}
