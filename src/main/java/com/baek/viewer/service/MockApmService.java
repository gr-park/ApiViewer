package com.baek.viewer.service;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApmCallDataRepository;
import com.baek.viewer.repository.ApiRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class MockApmService {

    private static final Logger log = LoggerFactory.getLogger(MockApmService.class);

    private final ApmCallDataRepository apmRepo;
    private final ApiRecordRepository apiRecordRepo;

    /** 자기 자신 프록시 — 내부 @Transactional 메서드 호출 시 새 트랜잭션 생성용 */
    @Autowired @Lazy
    private MockApmService self;

    public MockApmService(ApmCallDataRepository apmRepo, ApiRecordRepository apiRecordRepo) {
        this.apmRepo = apmRepo;
        this.apiRecordRepo = apiRecordRepo;
    }

    /** source 기본값 포함 오버로드 */
    @Transactional
    public Map<String, Object> generateMockData(String repoName, int days) {
        return generateMockData(repoName, days, "MOCK");
    }

    /**
     * 지정한 날짜 범위로 mock 데이터 생성 (와탭/제니퍼 수동 수집용).
     * source별 최대 기간: WHATAP=365일, JENNIFER=30일, MOCK=365일.
     */
    @Transactional
    public Map<String, Object> generateMockDataByRange(String repoName, LocalDate from, LocalDate to, String source) {
        final String src = normalizeSource(source);
        if (from == null || to == null) throw new IllegalArgumentException("from/to 날짜가 필요합니다.");
        if (from.isAfter(to)) throw new IllegalArgumentException("from 날짜가 to 날짜보다 늦을 수 없습니다.");
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        int maxDays = "JENNIFER".equals(src) ? 30 : 365;
        if (spanDays > maxDays) {
            throw new IllegalArgumentException(src + "는 최대 " + maxDays + "일까지만 조회 가능합니다. (요청: " + spanDays + "일)");
        }
        log.info("[APM 수동수집] repo={}, from={}, to={}, source={}, 기간={}일", repoName, from, to, src, spanDays);
        return doGenerate(repoName, from, to, src);
    }

    /** 실제 mock 생성 로직 (지정 날짜 범위 기반) */
    private Map<String, Object> doGenerate(String repoName, LocalDate from, LocalDate to, String src) {
        List<ApiRecord> records = apiRecordRepo.findByRepositoryName(repoName);
        if (records.isEmpty()) {
            log.warn("[APM 수동수집] 레포에 API 없음: {}", repoName);
            return Map.of("generated", 0, "message", "해당 레포에 분석된 API가 없습니다.");
        }
        int generated = 0;
        String[] errorMessages = {
            null, null, null, null, null,
            "NullPointerException", "IllegalArgumentException",
            "SQLException: Connection timeout", "HttpClientErrorException: 404",
            "TimeoutException: Read timed out"
        };
        // 일부 API는 호출이력 없음 (테스트용): 랜덤 3개 선택
        // 일부 API는 저사용(1~3건) 테스트용: 랜덤 3개 선택 — 검토필요 차단대상 후보
        Set<String> noCallApis = new HashSet<>();
        Set<String> lowCallApis = new HashSet<>();
        List<ApiRecord> candidates = records.stream()
                .filter(r -> !"차단완료".equals(r.getStatus()))
                .collect(Collectors.toList());
        Collections.shuffle(candidates);
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            noCallApis.add(candidates.get(i).getApiPath());
        }
        for (int i = 3; i < Math.min(6, candidates.size()); i++) {
            lowCallApis.add(candidates.get(i).getApiPath());
        }
        // 각 lowCall API에 대해 전체 기간에서 1~3건 정도만 발생하도록 호출 발생 날짜 미리 선정
        Map<String, Set<LocalDate>> lowCallDays = new HashMap<>();
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        for (String apiPath : lowCallApis) {
            int numCalls = ThreadLocalRandom.current().nextInt(1, 4); // 1~3건
            Set<LocalDate> days = new HashSet<>();
            for (int j = 0; j < numCalls; j++) {
                long offset = ThreadLocalRandom.current().nextLong(0, totalDays);
                days.add(from.plusDays(offset));
            }
            lowCallDays.put(apiPath, days);
        }
        // 기존 데이터 일괄 삭제 (재수집 시 중복 방지) — 단일 DELETE 쿼리
        int deletedOld = apmRepo.deleteByRepoSourceAndDateRange(repoName, src, from, to);
        if (deletedOld > 0) log.info("[APM 수동수집] 기존 {}건 선삭제 후 재수집", deletedOld);

        // 모든 레코드를 메모리에 모아서 saveAll() 일괄 저장
        List<ApmCallData> batch = new ArrayList<>();
        for (ApiRecord rec : records) {
            boolean isBlocked = "차단완료".equals(rec.getStatus());
            boolean noCall = noCallApis.contains(rec.getApiPath());
            boolean isLowCall = lowCallApis.contains(rec.getApiPath());
            LocalDate d = from;
            while (!d.isAfter(to)) {
                long callCount;
                if (isBlocked || noCall) {
                    callCount = 0;
                } else if (isLowCall) {
                    callCount = lowCallDays.get(rec.getApiPath()).contains(d) ? 1L : 0L;
                } else {
                    callCount = ThreadLocalRandom.current().nextLong(0, 150);
                }
                long errorCount = callCount > 0 ? ThreadLocalRandom.current().nextLong(0, Math.max(1, callCount / 20)) : 0;
                String errorMsg = errorCount > 0 ? errorMessages[ThreadLocalRandom.current().nextInt(errorMessages.length)] : null;

                ApmCallData data = new ApmCallData();
                data.setRepositoryName(repoName);
                data.setApiPath(rec.getApiPath());
                data.setCallDate(d);
                data.setCallCount(callCount);
                data.setErrorCount(errorCount);
                data.setErrorMessage(errorMsg);
                data.setClassName(rec.getControllerName());
                data.setSource(src);
                batch.add(data);
                generated++;
                d = d.plusDays(1);

                // 1000건 단위로 flush (메모리 + 트랜잭션 부하 분산)
                if (batch.size() >= 1000) {
                    apmRepo.saveAll(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) apmRepo.saveAll(batch);

        log.info("[APM 수동수집] 완료: {}건 (source={}, 삭제후재생성)", generated, src);
        return Map.of("generated", generated, "apis", records.size(),
                "from", from.toString(), "to", to.toString(), "source", src);
    }

    /**
     * Mock 데이터 생성: 해당 레포의 모든 API에 대해 지정 기간 일별 호출 데이터 생성.
     * source: MOCK / WHATAP / JENNIFER (대소문자 자동 변환)
     */
    @Transactional
    public Map<String, Object> generateMockData(String repoName, int days, String source) {
        final String src = normalizeSource(source);
        log.info("[Mock APM] 데이터 생성 시작: repo={}, days={}, source={}", repoName, days, src);
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

        // 일부 API는 호출이력 없음, 일부는 저사용(1~3건) — 테스트용 랜덤 선택
        Set<String> noCallApis = new HashSet<>();
        Set<String> lowCallApis = new HashSet<>();
        List<ApiRecord> candidates = records.stream()
                .filter(r -> !"차단완료".equals(r.getStatus()))
                .collect(java.util.stream.Collectors.toList());
        Collections.shuffle(candidates);
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            noCallApis.add(candidates.get(i).getApiPath());
        }
        for (int i = 3; i < Math.min(6, candidates.size()); i++) {
            lowCallApis.add(candidates.get(i).getApiPath());
        }
        log.info("[Mock APM] 호출이력 없는 API {}개, 저사용(1~3건) API {}개", noCallApis.size(), lowCallApis.size());

        // 저사용 API별로 1~3건의 호출이 발생할 날짜 미리 선정
        Map<String, Set<LocalDate>> lowCallDays = new HashMap<>();
        for (String apiPath : lowCallApis) {
            int numCalls = ThreadLocalRandom.current().nextInt(1, 4);
            Set<LocalDate> daySet = new HashSet<>();
            for (int j = 0; j < numCalls; j++) {
                int offset = ThreadLocalRandom.current().nextInt(0, days);
                daySet.add(today.minusDays(offset));
            }
            lowCallDays.put(apiPath, daySet);
        }

        for (ApiRecord rec : records) {
            for (int d = 0; d < days; d++) {
                LocalDate date = today.minusDays(d);

                // source별로 1건씩만 허용 (같은 날짜에 WHATAP/JENNIFER 병존 가능)
                if (!apmRepo.findByRepositoryNameAndApiPathAndCallDateAndSource(repoName, rec.getApiPath(), date, src).isEmpty()) {
                    continue;
                }

                // 차단완료/이력없음/저사용 대상별 호출건수 계산
                boolean isBlocked = "차단완료".equals(rec.getStatus());
                boolean noCall = noCallApis.contains(rec.getApiPath());
                boolean isLowCall = lowCallApis.contains(rec.getApiPath());
                long callCount;
                if (isBlocked || noCall) {
                    callCount = 0;
                } else if (isLowCall) {
                    callCount = lowCallDays.get(rec.getApiPath()).contains(date) ? 1L : 0L;
                } else {
                    callCount = ThreadLocalRandom.current().nextLong(0, 150);
                }
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
                data.setSource(src);
                apmRepo.save(data);
                generated++;
            }
        }

        log.info("[Mock APM] 데이터 생성 완료: {}건", generated);
        return Map.of("generated", generated, "apis", records.size(), "days", days);
    }

    /**
     * APM 데이터를 집계하여 ApiRecord의 callCount/callCountMonth/callCountWeek 업데이트.
     * callCount = 최근 1년 합계 (상태 판단 로직의 기준).
     * 같은 (apiPath, date)에 여러 source 데이터가 있으면 MAX를 사용해 중복 집계 방지.
     */
    @Transactional
    public Map<String, Object> aggregateToRecords(String repoName) {
        log.info("[APM 집계] 시작: repo={}", repoName);
        LocalDate today = LocalDate.now();
        LocalDate yearAgo = today.minusDays(365);
        LocalDate monthAgo = today.minusDays(30);
        LocalDate weekAgo = today.minusDays(7);

        Map<String, long[]> totals = new HashMap<>(); // apiPath → [year, month, week]
        accumulateMaxPerDate(apmRepo.sumByRepoAndDateRange(repoName, yearAgo, today), totals, 0);  // 1년
        accumulateMaxPerDate(apmRepo.sumByRepoAndDateRange(repoName, monthAgo, today), totals, 1); // 1달
        accumulateMaxPerDate(apmRepo.sumByRepoAndDateRange(repoName, weekAgo, today), totals, 2);  // 1주

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

    /** 레포별 APM 일별 데이터 조회 (source 지정 시 해당 source만) */
    public List<ApmCallData> getCallData(String repoName, LocalDate from, LocalDate to, String source) {
        if (source == null || source.isBlank() || "ALL".equalsIgnoreCase(source)) {
            return apmRepo.findByRepositoryNameAndCallDateBetweenOrderByCallDateDesc(repoName, from, to);
        }
        return apmRepo.findByRepositoryNameAndSourceAndCallDateBetweenOrderByCallDateDesc(
                repoName, normalizeSource(source), from, to);
    }

    public List<ApmCallData> getCallData(String repoName, LocalDate from, LocalDate to) {
        return getCallData(repoName, from, to, null);
    }

    /**
     * 단일 API의 기간별 차트 데이터 조회 (일단위/주단위 버킷팅).
     * @param bucket "daily" 또는 "weekly"
     * @param days 기간 (1달=30, 3달=90, 1년=365)
     */
    public Map<String, Object> getChartData(String repoName, String apiPath, String bucket, int days) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days - 1);
        List<ApmCallData> data = apmRepo.findByRepositoryNameAndApiPathAndCallDateBetweenOrderByCallDateAsc(
                repoName, apiPath, from, to);

        // (date) 단위로 여러 source 중 MAX 취하기 (중복 집계 방지)
        Map<LocalDate, long[]> dailyMax = new LinkedHashMap<>(); // date → [callMax, errMax]
        for (ApmCallData d : data) {
            long[] prev = dailyMax.get(d.getCallDate());
            if (prev == null) {
                dailyMax.put(d.getCallDate(), new long[]{d.getCallCount(), d.getErrorCount()});
            } else {
                prev[0] = Math.max(prev[0], d.getCallCount());
                prev[1] = Math.max(prev[1], d.getErrorCount());
            }
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        if ("weekly".equalsIgnoreCase(bucket)) {
            // 월요일 기준 주단위 버킷팅
            LocalDate cursor = from;
            while (!cursor.isAfter(to)) {
                LocalDate weekStart = cursor;
                LocalDate weekEnd = weekStart.plusDays(6);
                if (weekEnd.isAfter(to)) weekEnd = to;
                long sumCall = 0, sumErr = 0;
                LocalDate di = weekStart;
                while (!di.isAfter(weekEnd)) {
                    long[] v = dailyMax.get(di);
                    if (v != null) { sumCall += v[0]; sumErr += v[1]; }
                    di = di.plusDays(1);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", weekStart.toString().substring(5) + "~" + weekEnd.toString().substring(5));
                m.put("callCount", sumCall);
                m.put("errorCount", sumErr);
                buckets.add(m);
                cursor = weekEnd.plusDays(1);
            }
        } else {
            // daily
            LocalDate cursor = from;
            while (!cursor.isAfter(to)) {
                long[] v = dailyMax.getOrDefault(cursor, new long[]{0, 0});
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", cursor.toString()); // YYYY-MM-DD
                m.put("callCount", v[0]);
                m.put("errorCount", v[1]);
                buckets.add(m);
                cursor = cursor.plusDays(1);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoName", repoName);
        result.put("apiPath", apiPath);
        result.put("bucket", bucket);
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("buckets", buckets);
        return result;
    }

    /**
     * 전체 레포 대상 APM 수집.
     * forceMock=true면 모든 레포에 MOCK으로 수집. false면 각 레포의 whatapEnabled/jenniferEnabled에 따름.
     * 각 source별 최대 기간까지 수집 (WHATAP=365일, JENNIFER=30일).
     *
     * ⚠ 트랜잭션 의도적으로 미적용 — 각 레포의 수집/집계를 self-proxy로 호출해 레포별 독립 트랜잭션 생성.
     * (단일 거대 트랜잭션으로 인한 테이블 락 타임아웃 방지)
     */
    public Map<String, Object> collectAll(List<com.baek.viewer.model.RepoConfig> repos, boolean forceMock) {
        LocalDate to = LocalDate.now().minusDays(1);
        int totalGenerated = 0;
        int repoCount = 0;
        List<String> perRepo = new ArrayList<>();
        for (com.baek.viewer.model.RepoConfig r : repos) {
            int beforeTotal = totalGenerated;
            boolean any = false;
            if (forceMock) {
                LocalDate from = to.minusDays(364);
                try {
                    Object o = self.generateMockDataByRange(r.getRepoName(), from, to, "MOCK").get("generated");
                    if (o instanceof Number n) totalGenerated += n.intValue();
                    any = true;
                } catch (Exception e) { log.warn("[전체수집] {} MOCK 실패: {}", r.getRepoName(), e.getMessage()); }
            } else {
                if ("Y".equalsIgnoreCase(r.getWhatapEnabled())) {
                    try {
                        LocalDate from = to.minusDays(364);
                        Object o = self.generateMockDataByRange(r.getRepoName(), from, to, "WHATAP").get("generated");
                        if (o instanceof Number n) totalGenerated += n.intValue();
                        any = true;
                    } catch (Exception e) { log.warn("[전체수집] {} WHATAP 실패: {}", r.getRepoName(), e.getMessage()); }
                }
                if ("Y".equalsIgnoreCase(r.getJenniferEnabled())) {
                    try {
                        LocalDate from = to.minusDays(29);
                        Object o = self.generateMockDataByRange(r.getRepoName(), from, to, "JENNIFER").get("generated");
                        if (o instanceof Number n) totalGenerated += n.intValue();
                        any = true;
                    } catch (Exception e) { log.warn("[전체수집] {} JENNIFER 실패: {}", r.getRepoName(), e.getMessage()); }
                }
            }
            if (any) {
                repoCount++;
                try { self.aggregateToRecords(r.getRepoName()); }
                catch (Exception e) { log.warn("[전체수집] {} 집계 실패: {}", r.getRepoName(), e.getMessage()); }
                perRepo.add(r.getRepoName() + ":" + (totalGenerated - beforeTotal));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoCount", repoCount);
        result.put("totalGenerated", totalGenerated);
        result.put("mode", forceMock ? "MOCK" : "AUTO");
        result.put("perRepo", perRepo);
        return result;
    }

    /** APM 호출이력 삭제. repoName="ALL"이면 전체, source="ALL"이면 모든 source. MOCK 포함. */
    @Transactional
    public Map<String, Object> deleteMockData(String repoName, String source) {
        log.info("[APM 데이터 삭제] repo={}, source={}", repoName, source);
        long before = apmRepo.count();
        boolean allRepos = repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName);
        boolean allSources = source == null || source.isBlank() || "ALL".equalsIgnoreCase(source);

        if (allRepos && allSources) {
            apmRepo.deleteAll();
        } else if (allRepos) {
            // 모든 레포에서 특정 source만 삭제 — JPA로 직접 처리
            String src = normalizeSource(source);
            apmRepo.findAll().stream()
                    .filter(d -> src.equals(d.getSource()))
                    .forEach(apmRepo::delete);
        } else if (allSources) {
            apmRepo.deleteByRepositoryName(repoName);
        } else {
            apmRepo.deleteByRepositoryNameAndSource(repoName, normalizeSource(source));
        }
        long deleted = before - apmRepo.count();
        log.info("[APM 데이터 삭제 완료] {}건", deleted);
        return Map.of("deleted", deleted);
    }

    public Map<String, Object> deleteMockData(String repoName) {
        return deleteMockData(repoName, null);
    }

    /** source 문자열 정규화 (MOCK/WHATAP/JENNIFER) */
    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "MOCK";
        String s = source.trim().toUpperCase();
        if ("WHATAP".equals(s) || "JENNIFER".equals(s) || "MOCK".equals(s)) return s;
        return "MOCK";
    }

    /**
     * 쿼리 결과([apiPath, callDate, source, callCount, errorCount])를 받아서
     * (apiPath, date)별로 여러 source 중 MAX를 취해 totals[idx]에 누적.
     */
    private void accumulateMaxPerDate(List<Object[]> rows, Map<String, long[]> totals, int idx) {
        // (apiPath → (date → maxCallCount))
        Map<String, Map<LocalDate, Long>> maxByPathDate = new HashMap<>();
        for (Object[] row : rows) {
            String apiPath = (String) row[0];
            LocalDate date = (LocalDate) row[1];
            long count = ((Number) row[3]).longValue();
            Long prev = maxByPathDate
                    .computeIfAbsent(apiPath, k -> new HashMap<>())
                    .get(date);
            if (prev == null || count > prev) {
                maxByPathDate.get(apiPath).put(date, count);
            }
        }
        maxByPathDate.forEach((apiPath, dateMap) -> {
            long sum = dateMap.values().stream().mapToLong(Long::longValue).sum();
            totals.computeIfAbsent(apiPath, k -> new long[3])[idx] = sum;
        });
    }
}
