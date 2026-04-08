package com.baek.viewer.service;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApmCallDataRepository;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Whatap APM 연동 서비스.
 *
 * whatapMockEnabled=true : Whatap 응답 스키마와 동일한 형태의 Mock 데이터 생성
 * whatapMockEnabled=false: 실제 Whatap API 호출 (URL 필수)
 *
 * 요청 형식 (POST JSON):
 * { type:"stat", path:"ap", pcode:N, stime:epochMs, etime:epochMs,
 *   params:{ stime:"", etime:"", ptotal:100, skip:0, psize:N,
 *            okinds:[...], order:"countTotal", type:"service" } }
 *
 * 응답 형식:
 * { stime:epochMs, etime:epochMs, records:[{ service:"apiPath", count:N, error:N }] }
 */
@Service
public class WhatapApmService {

    private static final Logger log = LoggerFactory.getLogger(WhatapApmService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApmCallDataRepository apmRepo;
    private final ApiRecordRepository apiRecordRepo;
    private final GlobalConfigRepository globalConfigRepo;

    public WhatapApmService(ApmCallDataRepository apmRepo, ApiRecordRepository apiRecordRepo,
                             GlobalConfigRepository globalConfigRepo) {
        this.apmRepo = apmRepo;
        this.apiRecordRepo = apiRecordRepo;
        this.globalConfigRepo = globalConfigRepo;
    }

    /**
     * 날짜 범위를 일별로 수집하여 ApmCallData 저장.
     * whatapMockEnabled=true 이면 Mock, false 이면 실제 API (URL 필수).
     * 트랜잭션은 호출자(MockApmService.generateMockDataByRange)가 관리.
     */
    /**
     * @param logCallback UI 로그 콜백 (nullable) — MockApmService.addApmLog 전달용
     */
    public Map<String, Object> collect(RepoConfig repo, LocalDate from, LocalDate to,
                                        java.util.function.BiConsumer<String, String> logCallback) {
        GlobalConfig gc = globalConfigRepo.findById(1L).orElse(new GlobalConfig());
        boolean useMock = gc.isWhatapMockEnabled();
        String mode = useMock ? "MOCK" : "실제API";

        if (!useMock && (repo.getWhatapUrl() == null || repo.getWhatapUrl().isBlank())) {
            throw new IllegalStateException(
                    "WHATAP URL이 설정되지 않았고 whatapMockEnabled=false 입니다. " +
                    "repos-config.yml의 global.whatapMockEnabled를 true로 설정하거나 Whatap URL을 입력하세요.");
        }

        List<ApiRecord> records = apiRecordRepo.findByRepositoryName(repo.getRepoName());

        // Mock 모드에서만 records 필수 (Mock 데이터 생성 기반)
        if (useMock && records.isEmpty()) {
            emit(logCallback, "WARN", "WHATAP — API 없음, Mock 수집 건너뜀");
            return Map.of("generated", 0, "message", "해당 레포에 분석된 API가 없습니다.");
        }

        List<Integer> okinds = parseOkinds(repo.getWhatapOkinds());
        int psize = useMock ? Math.max(records.size(), 100) : 5000;

        emit(logCallback, "INFO", String.format("WHATAP(%s) 일별 수집 시작 — %s, pcode=%s",
                mode, useMock ? "API " + records.size() + "개" : "응답 전체 적재", repo.getWhatapPcode()));

        int generated = 0;
        int dayCount = 0;
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        List<ApmCallData> batch = new ArrayList<>();
        LocalDate cursor = from;

        while (!cursor.isAfter(to)) {
            long stimeMs = cursor.atStartOfDay(KST).toInstant().toEpochMilli();
            long etimeMs = cursor.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli() - 1;
            dayCount++;

            try {
                Map<String, long[]> dayData = useMock
                        ? buildMockDayData(records, stimeMs, etimeMs)
                        : fetchRealDay(repo, stimeMs, etimeMs, okinds, psize);

                long dayTotal = dayData.values().stream().mapToLong(c -> c[0]).sum();
                long dayErrors = dayData.values().stream().mapToLong(c -> c[1]).sum();

                if (useMock) {
                    // Mock: DB records 기반으로 매핑 (records에 있는 API만)
                    for (ApiRecord rec : records) {
                        long[] counts = dayData.getOrDefault(rec.getApiPath(), new long[]{0L, 0L});
                        batch.add(buildEntry(repo.getRepoName(), rec.getApiPath(), rec.getControllerName(),
                                cursor, counts[0], counts[1]));
                        generated++;
                        if (batch.size() >= 1000) { apmRepo.saveAll(batch); batch.clear(); }
                    }
                } else {
                    // 실제 API: 와탭 응답 JSON 그대로 전체 적재
                    for (var entry : dayData.entrySet()) {
                        batch.add(buildEntry(repo.getRepoName(), entry.getKey(), null,
                                cursor, entry.getValue()[0], entry.getValue()[1]));
                        generated++;
                        if (batch.size() >= 1000) { apmRepo.saveAll(batch); batch.clear(); }
                    }
                }
                // 일별 로그 + 샘플 3개 API 호출건수 (검증용)
                StringBuilder sample = new StringBuilder();
                int sc = 0;
                for (var e : dayData.entrySet()) {
                    if (sc++ >= 3) break;
                    String shortPath = e.getKey().length() > 25 ? e.getKey().substring(e.getKey().length()-25) : e.getKey();
                    sample.append(String.format(" [%s=%d]", shortPath, e.getValue()[0]));
                }
                emit(logCallback, "OK", String.format("WHATAP %s [%d/%d] 호출=%,d건 에러=%,d건 (API %d개)%s",
                        cursor, dayCount, totalDays, dayTotal, dayErrors, dayData.size(), sample));
            } catch (Exception e) {
                emit(logCallback, "WARN", String.format("WHATAP %s [%d/%d] 수집 실패 (스킵): %s",
                        cursor, dayCount, totalDays, e.getMessage()));
            }
            cursor = cursor.plusDays(1);
        }

        if (!batch.isEmpty()) apmRepo.saveAll(batch);
        emit(logCallback, "OK", String.format("WHATAP(%s) 수집 완료 — %,d건 저장 (%s~%s)",
                mode, generated, from, to));
        return Map.of("generated", generated, "from", from.toString(), "to", to.toString(),
                "source", "WHATAP", "mock", useMock);
    }

    /** logCallback 없이 호출하는 기존 호환 메서드 */
    public Map<String, Object> collect(RepoConfig repo, LocalDate from, LocalDate to) {
        return collect(repo, from, to, null);
    }

    private void emit(java.util.function.BiConsumer<String, String> cb, String level, String msg) {
        if (cb != null) cb.accept(level, msg);
        else {
            switch (level) {
                case "ERROR" -> log.error("[WHATAP] {}", msg);
                case "WARN"  -> log.warn("[WHATAP] {}", msg);
                default      -> log.info("[WHATAP] {}", msg);
            }
        }
    }

    /**
     * Whatap 응답 스키마와 동일한 형태로 Mock 데이터 생성 후 동일 파서로 처리.
     *
     * 생성 스키마:
     * { stime:N, etime:N, records:[{ service:"apiPath", count:N, error:N }] }
     */
    /**
     * Whatap Mock: 요일별 가중치 + API별 기본부하 + 랜덤 변동으로 현실적인 데이터 생성.
     * 주말=30%, 월요일=110%, 평일=80~120% 변동.
     */
    private Map<String, long[]> buildMockDayData(List<ApiRecord> records,
                                                   long stime, long etime) {
        // 요일별 가중치 (일~토: 0.3, 1.1, 1.0, 1.0, 0.95, 1.05, 0.3)
        java.time.LocalDate date = java.time.Instant.ofEpochMilli(stime).atZone(KST).toLocalDate();
        double[] dayWeight = {0.3, 1.1, 1.0, 1.0, 0.95, 1.05, 0.3};
        double weight = dayWeight[date.getDayOfWeek().getValue() % 7];

        Map<String, long[]> result = new HashMap<>();
        for (ApiRecord rec : records) {
            if ("차단완료".equals(rec.getStatus())) {
                result.put(rec.getApiPath(), new long[]{0L, 0L});
                continue;
            }
            // API별 기본부하(hash 기반) + 일별 랜덤 변동(±40%)
            int baseLoad = Math.abs(rec.getApiPath().hashCode() % 120) + 10; // 10~129
            double variation = 0.6 + ThreadLocalRandom.current().nextDouble() * 0.8; // 0.6~1.4
            long count = Math.max(0, Math.round(baseLoad * weight * variation));
            long error = count > 0 ? ThreadLocalRandom.current().nextLong(0, Math.max(1, count / 20)) : 0L;
            result.put(rec.getApiPath(), new long[]{count, error});
        }
        return result;
    }

    private Map<String, long[]> fetchRealDay(RepoConfig repo, long stime, long etime,
                                              List<Integer> okinds, int psize) throws Exception {
        boolean debug = globalConfigRepo.findById(1L).map(GlobalConfig::isApmDebug).orElse(false);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("stime", "");
        params.put("etime", "");
        params.put("ptotal", 100);
        params.put("skip", 0);
        params.put("psize", psize);
        params.put("okinds", okinds);
        params.put("order", "countTotal");
        params.put("type", "service");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "stat");
        body.put("path", "ap");
        body.put("pcode", repo.getWhatapPcode());
        body.put("stime", stime);
        body.put("etime", etime);
        body.put("params", params);

        String requestBody = objectMapper.writeValueAsString(body);

        if (debug) {
            log.debug("[WHATAP-REQ] POST {} | body={}", repo.getWhatapUrl(), requestBody);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(repo.getWhatapUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (repo.getWhatapCookie() != null && !repo.getWhatapCookie().isBlank()) {
            builder.header("Cookie", repo.getWhatapCookie());
        }

        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (debug) {
            log.debug("[WHATAP-RES] HTTP {} | length={} | body={}", resp.statusCode(), resp.body().length(),
                    resp.body().length() > 2000 ? resp.body().substring(0, 2000) + "...(truncated)" : resp.body());
        }

        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " — " + resp.body());
        }
        return parseResponse(resp.body());
    }

    /** Whatap 응답 JSON 파싱 (실제/Mock 공통) */
    private Map<String, long[]> parseResponse(String responseBody) throws Exception {
        Map<String, long[]> result = new HashMap<>();
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode recordsNode = root.path("records");
        if (recordsNode.isArray()) {
            for (JsonNode r : recordsNode) {
                String service = r.path("service").asText(null);
                if (service == null || service.isBlank()) continue;
                result.put(service, new long[]{r.path("count").asLong(0), r.path("error").asLong(0)});
            }
        }
        return result;
    }

    /** whatapOkinds 컬럼 값(콤마구분 또는 JSON 배열) → Integer 리스트 */
    private List<Integer> parseOkinds(String okindsStr) {
        List<Integer> result = new ArrayList<>();
        if (okindsStr == null || okindsStr.isBlank()) return result;
        String s = okindsStr.trim();
        if (s.startsWith("[")) {
            try {
                for (JsonNode n : objectMapper.readTree(s)) result.add(n.asInt());
                return result;
            } catch (Exception ignored) {}
        }
        for (String tok : s.split(",")) {
            try { result.add(Integer.parseInt(tok.trim())); } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private ApmCallData buildEntry(String repoName, String apiPath, String className,
                                    LocalDate date, long callCount, long errorCount) {
        ApmCallData d = new ApmCallData();
        d.setRepositoryName(repoName);
        d.setApiPath(apiPath);
        d.setCallDate(date);
        d.setCallCount(callCount);
        d.setErrorCount(errorCount);
        d.setClassName(className);
        d.setSource("WHATAP");
        return d;
    }
}
