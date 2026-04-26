package com.baek.viewer.service;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.model.ApmUrlStat;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApmCallDataRepository;
import com.baek.viewer.repository.ApmUrlStatRepository;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ApmCollectionService {

    private static final Logger log = LoggerFactory.getLogger(ApmCollectionService.class);

    private final ApmCallDataRepository apmRepo;
    private final ApmUrlStatRepository apmUrlStatRepo;
    private final ApiRecordRepository apiRecordRepo;
    private final RepoConfigRepository repoConfigRepo;
    private final WhatapApmService whatapApmService;
    private final JenniferApmService jenniferApmService;

    /** UI 실시간 로그 (extract 로그와 동일 구조) */
    private final List<String> apmLogs = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile boolean apmCollecting = false;

    /** 실시간 진행률 (날짜 단위) */
    private volatile int progressCurrent = 0;
    private volatile int progressTotal = 0;
    private volatile String progressRepo = "";

    public void addApmLog(String level, String msg) {
        String ts = java.time.LocalTime.now().toString().substring(0, 8);
        apmLogs.add(ts + " [" + level + "] " + msg);
        switch (level) {
            case "ERROR" -> log.error("[APM] {}", msg);
            case "WARN"  -> log.warn("[APM] {}", msg);
            default      -> log.info("[APM] {}", msg);
        }
    }

    public List<String> getApmLogs() { return apmLogs; }
    public boolean isApmCollecting() { return apmCollecting; }
    public int getProgressCurrent() { return progressCurrent; }
    public int getProgressTotal() { return progressTotal; }
    public String getProgressRepo() { return progressRepo; }

    /** 자기 자신 프록시 — 내부 @Transactional 메서드 호출 시 새 트랜잭션 생성용 */
    @Autowired @Lazy
    private ApmCollectionService self;

    public ApmCollectionService(ApmCallDataRepository apmRepo, ApmUrlStatRepository apmUrlStatRepo,
                          ApiRecordRepository apiRecordRepo,
                          RepoConfigRepository repoConfigRepo,
                          WhatapApmService whatapApmService, JenniferApmService jenniferApmService) {
        this.apmRepo = apmRepo;
        this.apmUrlStatRepo = apmUrlStatRepo;
        this.apiRecordRepo = apiRecordRepo;
        this.repoConfigRepo = repoConfigRepo;
        this.whatapApmService = whatapApmService;
        this.jenniferApmService = jenniferApmService;
    }

    /** source 기본값 포함 오버로드 */
    @Transactional
    public Map<String, Object> generateMockData(String repoName, int days) {
        return generateMockData(repoName, days, "MOCK");
    }

    /**
     * 지정한 날짜 범위로 APM 데이터 수집.
     * WHATAP/JENNIFER: 각 서비스 내부에서 mockEnabled 여부에 따라 Mock 또는 실제 API 결정.
     * MOCK: 직접 랜덤 데이터 생성 (source="MOCK" 명시 요청 시).
     * source별 최대 기간: WHATAP=365일, JENNIFER=30일, MOCK=365일.
     *
     * ⚠ @Transactional 미적용 — 하루치마다 self-proxy로 REQUIRES_NEW 트랜잭션 생성.
     *   중간 실패 시 해당 날짜만 롤백/스킵하고, 이미 커밋된 날짜는 유지.
     */
    public Map<String, Object> generateMockDataByRange(String repoName, LocalDate from, LocalDate to, String source) {
        final String src = normalizeSource(source);
        if (from == null || to == null) throw new IllegalArgumentException("from/to 날짜가 필요합니다.");
        if (from.isAfter(to)) throw new IllegalArgumentException("from 날짜가 to 날짜보다 늦을 수 없습니다.");
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        int maxDays = "JENNIFER".equals(src) ? 30 : 365;
        if (spanDays > maxDays) {
            throw new IllegalArgumentException(src + "는 최대 " + maxDays + "일까지만 조회 가능합니다. (요청: " + spanDays + "일)");
        }
        boolean isOuterCall = !apmCollecting;
        if (isOuterCall) apmCollecting = true;
        progressTotal = (int) spanDays;
        progressCurrent = 0;
        progressRepo = repoName;
        addApmLog("INFO", String.format("수집 시작 — repo=%s, source=%s, 기간=%s~%s (%d일)", repoName, src, from, to, spanDays));

        try {
            int totalGenerated = 0;
            int failDays = 0;

            if ("MOCK".equals(src)) {
                // MOCK: 컨텍스트 1회 산출 후 일자별 독립 커밋
                MockDayContext ctx = buildMockDayContext(repoName, from, to);
                if (ctx == null) {
                    return Map.of("generated", 0, "message", "해당 레포에 분석된 API가 없습니다.");
                }
                addApmLog("INFO", String.format("MOCK 데이터 생성 시작 — API %d개, 기간 %d일",
                        ctx.records.size(), ctx.totalDays));
                int dayNum = 0;
                LocalDate d = from;
                while (!d.isAfter(to)) {
                    dayNum++;
                    try {
                        totalGenerated += self.generateOneDayMock(repoName, d, src, ctx, dayNum);
                    } catch (Exception e) {
                        addApmLog("WARN", String.format("MOCK %s 일자 실패 (스킵): %s", d, e.getMessage()));
                        failDays++;
                    }
                    progressCurrent = dayNum;
                    d = d.plusDays(1);
                }
            } else {
                // WHATAP / JENNIFER: 일자별 외부 API 호출 + 독립 커밋
                java.util.function.BiConsumer<String, String> logCb = this::addApmLog;
                int dayNum = 0;
                LocalDate d = from;
                while (!d.isAfter(to)) {
                    dayNum++;
                    final LocalDate day = d;
                    try {
                        Map<String, Object> dayResult = self.generateOneDayExternal(repoName, day, src, logCb);
                        Object gen = dayResult.get("generated");
                        if (gen instanceof Number n) totalGenerated += n.intValue();
                    } catch (Exception e) {
                        addApmLog("WARN", String.format("%s %s 일자 실패 (스킵): %s", src, day, e.getMessage()));
                        failDays++;
                    }
                    progressCurrent = dayNum;
                    d = d.plusDays(1);
                }
            }

            addApmLog("OK", String.format("수집 완료 — repo=%s, source=%s, %,d건 저장%s",
                    repoName, src, totalGenerated,
                    failDays > 0 ? String.format(" (실패 %d일 스킵)", failDays) : ""));
            return Map.of("generated", totalGenerated, "from", from.toString(), "to", to.toString(), "source", src);
        } catch (Exception e) {
            addApmLog("ERROR", String.format("수집 실패 - repo=%s, source=%s: %s", repoName, src, e.getMessage()));
            throw e;
        } finally {
            if (isOuterCall) apmCollecting = false;
        }
    }

    /** MOCK 생성 시 루프 바깥에서 1회 산출하는 컨텍스트 (재계산 방지) */
    public static class MockDayContext {
        public final List<ApiRecord> records;
        public final Set<String> noCallApis;
        public final Set<String> lowCallApis;
        public final Map<String, Set<LocalDate>> lowCallDays;
        public final String[] errorMessages;
        public final long totalDays;

        MockDayContext(List<ApiRecord> records, Set<String> noCallApis, Set<String> lowCallApis,
                       Map<String, Set<LocalDate>> lowCallDays, String[] errorMessages, long totalDays) {
            this.records = records;
            this.noCallApis = noCallApis;
            this.lowCallApis = lowCallApis;
            this.lowCallDays = lowCallDays;
            this.errorMessages = errorMessages;
            this.totalDays = totalDays;
        }
    }

    /** MOCK 컨텍스트 생성 (레포 API 목록, noCall/lowCall 선정, lowCallDays 배분) */
    private MockDayContext buildMockDayContext(String repoName, LocalDate from, LocalDate to) {
        List<ApiRecord> records = apiRecordRepo.findByRepositoryName(repoName);
        if (records.isEmpty()) {
            log.warn("[APM 수동수집] 레포에 API 없음: {}", repoName);
            return null;
        }
        String[] errorMessages = {
            null, null, null, null, null,
            "NullPointerException", "IllegalArgumentException",
            "SQLException: Connection timeout", "HttpClientErrorException: 404",
            "TimeoutException: Read timed out"
        };
        Set<String> noCallApis = new HashSet<>();
        Set<String> lowCallApis = new HashSet<>();
        List<ApiRecord> candidates = records.stream()
                .filter(r -> !"①-① 차단완료".equals(r.getStatus()))
                .collect(Collectors.toList());
        Collections.shuffle(candidates);
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            noCallApis.add(candidates.get(i).getApiPath());
        }
        for (int i = 3; i < Math.min(6, candidates.size()); i++) {
            lowCallApis.add(candidates.get(i).getApiPath());
        }
        Map<String, Set<LocalDate>> lowCallDays = new HashMap<>();
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        for (String apiPath : lowCallApis) {
            int numCalls = ThreadLocalRandom.current().nextInt(1, 4);
            Set<LocalDate> days = new HashSet<>();
            for (int j = 0; j < numCalls; j++) {
                long offset = ThreadLocalRandom.current().nextLong(0, totalDays);
                days.add(from.plusDays(offset));
            }
            lowCallDays.put(apiPath, days);
        }
        return new MockDayContext(records, noCallApis, lowCallApis, lowCallDays, errorMessages, totalDays);
    }

    /**
     * 하루치 MOCK 데이터 생성 + 저장. REQUIRES_NEW 트랜잭션으로 해당 날짜만 커밋.
     * 실패 시 이 날짜만 롤백, 이미 저장된 다른 날짜는 영향 없음.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int generateOneDayMock(String repoName, LocalDate day, String src, MockDayContext ctx, int dayNum) {
        apmRepo.deleteByRepoSourceAndDateRange(repoName, src, day, day);
        List<ApmCallData> batch = new ArrayList<>();
        int generated = 0;
        long dayTotal = 0, dayErrors = 0;
        for (ApiRecord rec : ctx.records) {
            boolean isBlocked = "①-① 차단완료".equals(rec.getStatus());
            boolean noCall = ctx.noCallApis.contains(rec.getApiPath());
            boolean isLowCall = ctx.lowCallApis.contains(rec.getApiPath());
            long callCount;
            if (isBlocked || noCall) {
                callCount = 0;
            } else if (isLowCall) {
                callCount = ctx.lowCallDays.get(rec.getApiPath()).contains(day) ? 1L : 0L;
            } else {
                double[] dayWeight = {0.3, 1.1, 1.0, 1.0, 0.95, 1.05, 0.3};
                double weight = dayWeight[day.getDayOfWeek().getValue() % 7];
                int baseLoad = Math.abs(rec.getApiPath().hashCode() % 120) + 10;
                double variation = 0.6 + ThreadLocalRandom.current().nextDouble() * 0.8;
                callCount = Math.max(0, Math.round(baseLoad * weight * variation));
            }
            long errorCount = callCount > 0 ? ThreadLocalRandom.current().nextLong(0, Math.max(1, callCount / 20)) : 0;
            String errorMsg = errorCount > 0 ? ctx.errorMessages[ThreadLocalRandom.current().nextInt(ctx.errorMessages.length)] : null;

            ApmCallData data = new ApmCallData();
            data.setRepositoryName(repoName);
            data.setApiPath(rec.getApiPath());
            data.setCallDate(day);
            data.setCallCount(callCount);
            data.setErrorCount(errorCount);
            data.setErrorMessage(errorMsg);
            data.setClassName(rec.getControllerName());
            data.setSource(src);
            batch.add(data);
            generated++;
            dayTotal += callCount;
            dayErrors += errorCount;

            if (batch.size() >= 1000) {
                apmRepo.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) apmRepo.saveAll(batch);
        addApmLog("OK", String.format("MOCK %s [%d/%d] 호출=%,d건 에러=%,d건 (API %d개)",
                day, dayNum, ctx.totalDays, dayTotal, dayErrors, ctx.records.size()));
        return generated;
    }

    /**
     * 하루치 WHATAP/JENNIFER 데이터 수집 + 저장. REQUIRES_NEW 트랜잭션으로 해당 날짜만 커밋.
     * 각 외부 서비스의 collect(repo, day, day, logCb) 를 1일 범위로 호출.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> generateOneDayExternal(String repoName, LocalDate day, String src,
            java.util.function.BiConsumer<String, String> logCb) {
        apmRepo.deleteByRepoSourceAndDateRange(repoName, src, day, day);
        RepoConfig repo = repoConfigRepo.findByRepoName(repoName).orElseThrow(
                () -> new IllegalArgumentException("레포 설정 없음: " + repoName));
        if ("WHATAP".equals(src)) {
            return whatapApmService.collect(repo, day, day, logCb);
        } else {
            return jenniferApmService.collect(repo, day, day, logCb);
        }
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
                .filter(r -> !"①-① 차단완료".equals(r.getStatus()))
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
                boolean isBlocked = "①-① 차단완료".equals(rec.getStatus());
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
        List<Object[]> yearRows  = apmRepo.sumByRepoAndDateRange(repoName, yearAgo, today);
        List<Object[]> monthRows = apmRepo.sumByRepoAndDateRange(repoName, monthAgo, today);
        List<Object[]> weekRows  = apmRepo.sumByRepoAndDateRange(repoName, weekAgo, today);
        accumulateMaxPerDate(yearRows, totals, 0);   // 1년
        accumulateMaxPerDate(monthRows, totals, 1);  // 1달
        accumulateMaxPerDate(weekRows, totals, 2);   // 1주

        log.info("[APM 집계] 데이터 건수 — 1년:{}, 1달:{}, 1주:{} (기간: {}~{}/{}~{}/{}~{})",
                yearRows.size(), monthRows.size(), weekRows.size(),
                yearAgo, today, monthAgo, today, weekAgo, today);

        // ApiRecord 업데이트 — APM 데이터에 없는 API는 0건으로 세팅
        boolean hasApmData = !totals.isEmpty();
        List<ApiRecord> records = apiRecordRepo.findByRepositoryName(repoName);
        int updated = 0, zeroed = 0;
        for (ApiRecord rec : records) {
            long[] counts = totals.get(rec.getApiPath());
            if (counts != null) {
                rec.setCallCount(counts[0]);
                rec.setCallCountMonth(counts[1]);
                rec.setCallCountWeek(counts[2]);
                apiRecordRepo.save(rec);
                updated++;
            } else if (hasApmData) {
                // APM 데이터가 존재하는 레포인데 이 API만 없음 → 호출 0건 확정
                rec.setCallCount(0L);
                rec.setCallCountMonth(0L);
                rec.setCallCountWeek(0L);
                apiRecordRepo.save(rec);
                zeroed++;
            }
        }

        // 샘플 로그: 첫 3개 API의 집계 결과
        int sc = 0;
        for (var e : totals.entrySet()) {
            if (sc++ >= 3) break;
            log.info("[APM 집계] 샘플: {} → 1년={}, 1달={}, 1주={}",
                    e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        }

        log.info("[APM 집계] 완료: repo={}, 업데이트={}건, 0건세팅={}건", repoName, updated, zeroed);
        return Map.of("updated", updated, "zeroed", zeroed, "totalApis", totals.size());
    }

    /**
     * apm_call_data 전체 기준으로 URL별 호출/에러 건수를 apm_url_stat에 집계.
     * api_record(URL 분석) 여부와 무관하게 APM에 수집된 모든 URL을 대상으로 함.
     * call-stats.html 대시보드 데이터 소스.
     *
     * 6개 기간: 전일(1일) / 1주 / 1달 / 3달 / 6달 / 1년
     * 처리: 레포 기존 행 전체 삭제 → 재삽입 (upsert 대안).
     */
    @Transactional
    public void aggregateToApmUrlStat(String repoName) {
        log.info("[URL 통계 집계] 시작: repo={}", repoName);
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate weekAgo   = today.minusDays(7);
        LocalDate monthAgo  = today.minusDays(30);
        LocalDate month3Ago = today.minusDays(90);
        LocalDate month6Ago = today.minusDays(180);
        LocalDate yearAgo   = today.minusDays(365);

        // apiPath → [call_yesterday, call_week, call_month, call_3month, call_6month, call_year]
        Map<String, long[]> calls = new HashMap<>();
        // apiPath → [err_yesterday, err_week, err_month, err_3month, err_6month, err_year]
        Map<String, long[]> errs  = new HashMap<>();

        // 1년치 한 번만 조회 후 기간별로 나눠 집계 (DB 호출 최소화)
        List<Object[]> yearRows = apmRepo.sumByRepoAndDateRange(repoName, yearAgo, today);
        accumulatePeriodBoth(yearRows, calls, errs, yesterday, today,   0, 6); // 전일
        accumulatePeriodBoth(yearRows, calls, errs, weekAgo,   today,   1, 6); // 1주
        accumulatePeriodBoth(yearRows, calls, errs, monthAgo,  today,   2, 6); // 1달
        accumulatePeriodBoth(yearRows, calls, errs, month3Ago, today,   3, 6); // 3달
        accumulatePeriodBoth(yearRows, calls, errs, month6Ago, today,   4, 6); // 6달
        accumulatePeriodBoth(yearRows, calls, errs, yearAgo,   today,   5, 6); // 1년

        // 기존 레포 행 삭제 후 재삽입
        apmUrlStatRepo.deleteByRepo(repoName);

        // 모든 apiPath 수집 (call+err 두 맵의 합집합)
        Set<String> allPaths = new HashSet<>(calls.keySet());
        allPaths.addAll(errs.keySet());

        LocalDateTime now = LocalDateTime.now();
        List<ApmUrlStat> stats = new ArrayList<>(allPaths.size());
        for (String path : allPaths) {
            long[] c = calls.getOrDefault(path, new long[6]);
            long[] e = errs.getOrDefault(path, new long[6]);
            ApmUrlStat stat = new ApmUrlStat();
            stat.setRepositoryName(repoName);
            stat.setApiPath(path);
            stat.setCallCountYesterday(c[0]);
            stat.setCallCountWeek(c[1]);
            stat.setCallCountMonth(c[2]);
            stat.setCallCount3Month(c[3]);
            stat.setCallCount6Month(c[4]);
            stat.setCallCount(c[5]);
            stat.setErrorCountYesterday(e[0]);
            stat.setErrorCountWeek(e[1]);
            stat.setErrorCountMonth(e[2]);
            stat.setErrorCount3Month(e[3]);
            stat.setErrorCount6Month(e[4]);
            stat.setErrorCountYear(e[5]);
            stat.setUpdatedAt(now);
            stats.add(stat);
        }
        apmUrlStatRepo.saveAll(stats);
        log.info("[URL 통계 집계] 완료: repo={}, URL={}개", repoName, stats.size());
    }

    /**
     * sumByRepoAndDateRange 결과에서 특정 날짜 범위(from~to)에 해당하는 행만 필터해
     * apiPath별 call/error 합산을 calls[idx], errs[idx]에 누적.
     * source별 중복은 날짜별 MAX(callCount) 방식으로 처리.
     */
    private void accumulatePeriodBoth(List<Object[]> rows,
                                       Map<String, long[]> calls, Map<String, long[]> errs,
                                       LocalDate from, LocalDate to,
                                       int idx, int size) {
        // apiPath → date → [maxCall, correspondingErr]
        Map<String, Map<LocalDate, long[]>> maxByPathDate = new HashMap<>();
        for (Object[] row : rows) {
            LocalDate date = (LocalDate) row[1];
            if (date.isBefore(from) || date.isAfter(to)) continue;
            String apiPath = (String) row[0];
            long call = ((Number) row[3]).longValue();
            long err  = ((Number) row[4]).longValue();
            long[] prev = maxByPathDate
                    .computeIfAbsent(apiPath, k -> new HashMap<>())
                    .get(date);
            if (prev == null || call > prev[0]) {
                maxByPathDate.get(apiPath).put(date, new long[]{call, err});
            }
        }
        maxByPathDate.forEach((apiPath, dateMap) -> {
            long callSum = 0, errSum = 0;
            for (long[] v : dateMap.values()) { callSum += v[0]; errSum += v[1]; }
            calls.computeIfAbsent(apiPath, k -> new long[size])[idx] = callSum;
            errs.computeIfAbsent(apiPath,  k -> new long[size])[idx] = errSum;
        });
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
        apmLogs.clear();
        apmCollecting = true;
        LocalDate to = LocalDate.now().minusDays(1);
        int totalGenerated = 0;
        int repoCount = 0;
        int repoIdx = 0;
        List<String> perRepo = new ArrayList<>();
        addApmLog("INFO", String.format("전체 수집 시작 — %d개 레포, 모드=%s", repos.size(), forceMock ? "MOCK" : "AUTO"));
        for (com.baek.viewer.model.RepoConfig r : repos) {
            repoIdx++;
            int beforeTotal = totalGenerated;
            boolean any = false;
            addApmLog("INFO", String.format("── 레포 [%d/%d] %s ──", repoIdx, repos.size(), r.getRepoName()));
            if (forceMock) {
                LocalDate from = to.minusDays(364);
                try {
                    Object o = self.generateMockDataByRange(r.getRepoName(), from, to, "MOCK").get("generated");
                    if (o instanceof Number n) totalGenerated += n.intValue();
                    any = true;
                } catch (Exception e) { addApmLog("ERROR", r.getRepoName() + " MOCK 실패: " + e.getMessage()); }
            } else {
                if ("Y".equalsIgnoreCase(r.getWhatapEnabled())) {
                    try {
                        LocalDate from = to.minusDays(364);
                        Object o = self.generateMockDataByRange(r.getRepoName(), from, to, "WHATAP").get("generated");
                        if (o instanceof Number n) totalGenerated += n.intValue();
                        any = true;
                    } catch (Exception e) { addApmLog("ERROR", r.getRepoName() + " WHATAP 실패: " + e.getMessage()); }
                }
                if ("Y".equalsIgnoreCase(r.getJenniferEnabled())) {
                    try {
                        LocalDate from = to.minusDays(29);
                        Object o = self.generateMockDataByRange(r.getRepoName(), from, to, "JENNIFER").get("generated");
                        if (o instanceof Number n) totalGenerated += n.intValue();
                        any = true;
                    } catch (Exception e) { addApmLog("ERROR", r.getRepoName() + " JENNIFER 실패: " + e.getMessage()); }
                }
            }
            if (any) {
                repoCount++;
                try {
                    addApmLog("INFO", r.getRepoName() + " 집계(aggregate) 실행 중...");
                    self.aggregateToRecords(r.getRepoName());
                    self.aggregateToApmUrlStat(r.getRepoName());
                    addApmLog("OK", r.getRepoName() + " 집계 완료");
                } catch (Exception e) { addApmLog("ERROR", r.getRepoName() + " 집계 실패: " + e.getMessage()); }
                perRepo.add(r.getRepoName() + ":" + (totalGenerated - beforeTotal));
            }
        }
        addApmLog("OK", String.format("전체 수집 완료 — %d개 레포, 총 %,d건", repoCount, totalGenerated));
        apmCollecting = false;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoCount", repoCount);
        result.put("totalGenerated", totalGenerated);
        result.put("mode", forceMock ? "MOCK" : "AUTO");
        result.put("perRepo", perRepo);
        return result;
    }

    /** APM 호출이력 삭제. repoName="ALL"이면 전체, source="ALL"이면 모든 source. bulk DELETE 사용. */
    @Transactional
    public Map<String, Object> deleteMockData(String repoName, String source) {
        log.info("[APM 데이터 삭제] repo={}, source={}", repoName, source);
        boolean allRepos = repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName);
        boolean allSources = source == null || source.isBlank() || "ALL".equalsIgnoreCase(source);

        int deleted;
        if (allRepos && allSources) {
            deleted = (int) apmRepo.count();
            apmRepo.bulkDeleteAll();
            apmUrlStatRepo.deleteAllRows();  // 전체 삭제 시 통계도 함께 초기화
        } else if (allRepos) {
            deleted = apmRepo.bulkDeleteBySource(normalizeSource(source));
            // source별 삭제는 다른 source 데이터가 남으므로 통계는 유지
        } else if (allSources) {
            deleted = apmRepo.bulkDeleteByRepo(repoName);
            apmUrlStatRepo.deleteByRepo(repoName);  // 레포 전체 삭제 시 통계도 삭제
        } else {
            deleted = apmRepo.bulkDeleteByRepoAndSource(repoName, normalizeSource(source));
            // source별 삭제는 다른 source 데이터가 남으므로 통계는 유지
        }
        log.info("[APM 데이터 삭제 완료] {}건 ({})", deleted, (allRepos && allSources) ? "TRUNCATE" : "bulk DELETE");
        return Map.of("deleted", deleted);
    }

    public Map<String, Object> deleteMockData(String repoName) {
        return deleteMockData(repoName, null);
    }

    /** 호출이력 삭제 후 api_record의 callCount/callCountMonth/callCountWeek를 0으로 리셋 */
    @Transactional
    public void resetCallCounts(String repoName) {
        boolean allRepos = repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName);
        List<com.baek.viewer.model.ApiRecord> records = allRepos
                ? apiRecordRepo.findAll()
                : apiRecordRepo.findByRepositoryName(repoName);
        int reset = 0;
        for (var r : records) {
            Long cc = r.getCallCount(), cm = r.getCallCountMonth(), cw = r.getCallCountWeek();
            if ((cc != null && cc != 0) || (cm != null && cm != 0) || (cw != null && cw != 0)) {
                r.setCallCount(0L);
                r.setCallCountMonth(0L);
                r.setCallCountWeek(0L);
                apiRecordRepo.save(r);
                reset++;
            }
        }
        log.info("[호출건수 리셋] repo={}, 리셋={}건", allRepos ? "ALL" : repoName, reset);
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
