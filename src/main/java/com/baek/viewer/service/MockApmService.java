package com.baek.viewer.service;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApmCallDataRepository;
import com.baek.viewer.repository.ApiRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class MockApmService {

    private static final Logger log = LoggerFactory.getLogger(MockApmService.class);

    private final ApmCallDataRepository apmRepo;
    private final ApiRecordRepository apiRecordRepo;

    public MockApmService(ApmCallDataRepository apmRepo, ApiRecordRepository apiRecordRepo) {
        this.apmRepo = apmRepo;
        this.apiRecordRepo = apiRecordRepo;
    }

    /**
     * Mock 데이터 생성: 해당 레포의 모든 API에 대해 지정 기간 일별 호출 데이터 생성
     */
    @Transactional
    public Map<String, Object> generateMockData(String repoName, int days) {
        log.info("[Mock APM] 데이터 생성 시작: repo={}, days={}", repoName, days);
        List<ApiRecord> records = apiRecordRepo.findByRepositoryName(repoName);
        if (records.isEmpty()) {
            log.warn("[Mock APM] 레포에 API 없음: {}", repoName);
            return Map.of("generated", 0, "message", "해당 레포에 분석된 API가 없습니다.");
        }

        LocalDate today = LocalDate.now();
        int generated = 0;
        String[] errorMessages = {
            null, null, null, null, null, // 대부분 에러 없음
            "NullPointerException", "IllegalArgumentException",
            "SQLException: Connection timeout", "HttpClientErrorException: 404",
            "TimeoutException: Read timed out"
        };

        for (ApiRecord rec : records) {
            for (int d = 0; d < days; d++) {
                LocalDate date = today.minusDays(d);

                // 이미 데이터가 있으면 건너뜀
                if (!apmRepo.findByRepositoryNameAndApiPathAndCallDate(repoName, rec.getApiPath(), date).isEmpty()) {
                    continue;
                }

                // 차단완료면 호출 0건
                boolean isBlocked = "차단완료".equals(rec.getStatus());
                long callCount = isBlocked ? 0 : ThreadLocalRandom.current().nextLong(0, 150);
                long errorCount = callCount > 0 ? ThreadLocalRandom.current().nextLong(0, Math.max(1, callCount / 20)) : 0;
                String errorMsg = errorCount > 0 ? errorMessages[ThreadLocalRandom.current().nextInt(errorMessages.length)] : null;

                ApmCallData data = new ApmCallData();
                data.setRepositoryName(repoName);
                data.setApiPath(rec.getApiPath());
                data.setCallDate(date);
                data.setCallCount(callCount);
                data.setErrorCount(errorCount);
                data.setErrorMessage(errorMsg);
                data.setClassName(rec.getControllerName());
                data.setSource("MOCK");
                apmRepo.save(data);
                generated++;
            }
        }

        log.info("[Mock APM] 데이터 생성 완료: {}건", generated);
        return Map.of("generated", generated, "apis", records.size(), "days", days);
    }

    /**
     * APM 데이터를 집계하여 ApiRecord의 callCount/callCountMonth/callCountWeek 업데이트
     */
    @Transactional
    public Map<String, Object> aggregateToRecords(String repoName) {
        log.info("[APM 집계] 시작: repo={}", repoName);
        LocalDate today = LocalDate.now();
        LocalDate monthAgo = today.minusDays(30);
        LocalDate weekAgo = today.minusDays(7);

        // 전체 기간 합계
        Map<String, long[]> totals = new HashMap<>(); // apiPath → [total, month, week]

        List<Object[]> allData = apmRepo.sumByRepoAndDateRange(repoName, LocalDate.of(2000, 1, 1), today);
        for (Object[] row : allData) {
            String apiPath = (String) row[0];
            long count = ((Number) row[1]).longValue();
            totals.computeIfAbsent(apiPath, k -> new long[3])[0] = count;
        }

        List<Object[]> monthData = apmRepo.sumByRepoAndDateRange(repoName, monthAgo, today);
        for (Object[] row : monthData) {
            String apiPath = (String) row[0];
            long count = ((Number) row[1]).longValue();
            totals.computeIfAbsent(apiPath, k -> new long[3])[1] = count;
        }

        List<Object[]> weekData = apmRepo.sumByRepoAndDateRange(repoName, weekAgo, today);
        for (Object[] row : weekData) {
            String apiPath = (String) row[0];
            long count = ((Number) row[1]).longValue();
            totals.computeIfAbsent(apiPath, k -> new long[3])[2] = count;
        }

        // ApiRecord 업데이트
        List<ApiRecord> records = apiRecordRepo.findByRepositoryName(repoName);
        int updated = 0;
        for (ApiRecord rec : records) {
            long[] counts = totals.get(rec.getApiPath());
            if (counts != null) {
                rec.setCallCount(counts[0]);
                rec.setCallCountMonth(counts[1]);
                rec.setCallCountWeek(counts[2]);
                apiRecordRepo.save(rec);
                updated++;
            }
        }

        log.info("[APM 집계] 완료: repo={}, 업데이트={}건", repoName, updated);
        return Map.of("updated", updated, "totalApis", totals.size());
    }

    /** 레포별 APM 일별 데이터 조회 */
    public List<ApmCallData> getCallData(String repoName, LocalDate from, LocalDate to) {
        return apmRepo.findByRepositoryNameAndCallDateBetweenOrderByCallDateDesc(repoName, from, to);
    }
}
