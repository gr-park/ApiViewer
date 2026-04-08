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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jennifer APM 연동 서비스. (최대 30일 제약)
 *
 * jenniferMockEnabled=true : Jennifer 응답 스키마와 동일한 형태의 Mock 데이터 생성
 * jenniferMockEnabled=false: 실제 Jennifer API 호출 (URL 필수)
 *
 * 요청 형식 (GET):
 * {url}?domain_id={sid}&instance_id={oid1,oid2,...}&start_time={epochMs}&end_time={epochMs}
 * - start_time/end_time: 시 단위 epoch ms (분/초 = 0)
 *
 * 응답 형식:
 * { result:[{ name:"apiPath", calls:N, badResponses:N, failures:N }] }
 * errorCount = badResponses + failures
 */
@Service
public class JenniferApmService {

    private static final Logger log = LoggerFactory.getLogger(JenniferApmService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApmCallDataRepository apmRepo;
    private final ApiRecordRepository apiRecordRepo;
    private final GlobalConfigRepository globalConfigRepo;

    public JenniferApmService(ApmCallDataRepository apmRepo, ApiRecordRepository apiRecordRepo,
                               GlobalConfigRepository globalConfigRepo) {
        this.apmRepo = apmRepo;
        this.apiRecordRepo = apiRecordRepo;
        this.globalConfigRepo = globalConfigRepo;
    }

    /**
     * 날짜 범위를 일별로 수집하여 ApmCallData 저장.
     * jenniferMockEnabled=true 이면 Mock, false 이면 실제 API (URL 필수).
     * 트랜잭션은 호출자(MockApmService.generateMockDataByRange)가 관리.
     */
    /**
     * @param logCallback UI 로그 콜백 (nullable) — MockApmService.addApmLog 전달용
     */
    public Map<String, Object> collect(RepoConfig repo, LocalDate from, LocalDate to,
                                        java.util.function.BiConsumer<String, String> logCallback) {
        GlobalConfig gc = globalConfigRepo.findById(1L).orElse(new GlobalConfig());
        boolean useMock = gc.isJenniferMockEnabled();
        String mode = useMock ? "MOCK" : "실제API";

        if (!useMock && (repo.getJenniferUrl() == null || repo.getJenniferUrl().isBlank())) {
            throw new IllegalStateException(
                    "Jennifer URL이 설정되지 않았고 jenniferMockEnabled=false 입니다. " +
                    "repos-config.yml의 global.jenniferMockEnabled를 true로 설정하거나 Jennifer URL을 입력하세요.");
        }

        List<ApiRecord> records = apiRecordRepo.findByRepositoryName(repo.getRepoName());

        if (useMock && records.isEmpty()) {
            emit(logCallback, "WARN", "JENNIFER — API 없음, Mock 수집 건너뜀");
            return Map.of("generated", 0, "message", "해당 레포에 분석된 API가 없습니다.");
        }

        emit(logCallback, "INFO", String.format("JENNIFER(%s) 일별 수집 시작 — %s, sid=%s",
                mode, useMock ? "API " + records.size() + "개" : "응답 전체 적재", repo.getJenniferSid()));

        String instanceId = buildInstanceId(repo.getJenniferOids());

        int generated = 0;
        int dayCount = 0;
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        List<ApmCallData> batch = new ArrayList<>();
        LocalDate cursor = from;

        while (!cursor.isAfter(to)) {
            long startTime = cursor.atStartOfDay(KST).toInstant().toEpochMilli();
            long endTime   = cursor.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli();
            dayCount++;

            try {
                Map<String, long[]> dayData = useMock
                        ? buildMockDayData(records, cursor)
                        : fetchRealDay(repo, instanceId, startTime, endTime);

                long dayTotal = dayData.values().stream().mapToLong(c -> c[0]).sum();
                long dayErrors = dayData.values().stream().mapToLong(c -> c[1]).sum();

                if (useMock) {
                    // Mock: DB records 기반으로 매핑
                    for (ApiRecord rec : records) {
                        long[] counts = dayData.getOrDefault(rec.getApiPath(), new long[]{0L, 0L});
                        batch.add(buildEntry(repo.getRepoName(), rec.getApiPath(), rec.getControllerName(),
                                cursor, counts[0], counts[1]));
                        generated++;
                        if (batch.size() >= 1000) { apmRepo.saveAll(batch); batch.clear(); }
                    }
                } else {
                    // 실제 API: 제니퍼 응답 JSON 그대로 전체 적재
                    for (var entry : dayData.entrySet()) {
                        batch.add(buildEntry(repo.getRepoName(), entry.getKey(), null,
                                cursor, entry.getValue()[0], entry.getValue()[1]));
                        generated++;
                        if (batch.size() >= 1000) { apmRepo.saveAll(batch); batch.clear(); }
                    }
                }
                StringBuilder sample = new StringBuilder();
                int sc = 0;
                for (var e : dayData.entrySet()) {
                    if (sc++ >= 3) break;
                    String shortPath = e.getKey().length() > 25 ? e.getKey().substring(e.getKey().length()-25) : e.getKey();
                    sample.append(String.format(" [%s=%d]", shortPath, e.getValue()[0]));
                }
                emit(logCallback, "OK", String.format("JENNIFER %s [%d/%d] 호출=%,d건 에러=%,d건 (API %d개)%s",
                        cursor, dayCount, totalDays, dayTotal, dayErrors, dayData.size(), sample));
            } catch (Exception e) {
                emit(logCallback, "WARN", String.format("JENNIFER %s [%d/%d] 수집 실패 (스킵): %s",
                        cursor, dayCount, totalDays, e.getMessage()));
            }
            cursor = cursor.plusDays(1);
        }

        if (!batch.isEmpty()) apmRepo.saveAll(batch);
        emit(logCallback, "OK", String.format("JENNIFER(%s) 수집 완료 — %,d건 저장 (%s~%s)",
                mode, generated, from, to));
        return Map.of("generated", generated, "from", from.toString(), "to", to.toString(),
                "source", "JENNIFER", "mock", useMock);
    }

    /** logCallback 없이 호출하는 기존 호환 메서드 */
    public Map<String, Object> collect(RepoConfig repo, LocalDate from, LocalDate to) {
        return collect(repo, from, to, null);
    }

    private void emit(java.util.function.BiConsumer<String, String> cb, String level, String msg) {
        if (cb != null) cb.accept(level, msg);
        else {
            switch (level) {
                case "ERROR" -> log.error("[JENNIFER] {}", msg);
                case "WARN"  -> log.warn("[JENNIFER] {}", msg);
                default      -> log.info("[JENNIFER] {}", msg);
            }
        }
    }

    /**
     * Jennifer 응답 스키마와 동일한 형태로 Mock 데이터 생성 후 동일 파서로 처리.
     *
     * 생성 스키마:
     * { result:[{ name:"apiPath", calls:N, badResponses:N, failures:N }] }
     */
    /**
     * Jennifer Mock: 요일별 가중치 + API별 기본부하 + 랜덤 변동으로 현실적인 데이터 생성.
     */
    private Map<String, long[]> buildMockDayData(List<ApiRecord> records, LocalDate date) {
        double[] dayWeight = {0.3, 1.1, 1.0, 1.0, 0.95, 1.05, 0.3};
        double weight = dayWeight[date.getDayOfWeek().getValue() % 7];

        Map<String, long[]> result = new HashMap<>();
        for (ApiRecord rec : records) {
            if ("차단완료".equals(rec.getStatus())) {
                result.put(rec.getApiPath(), new long[]{0L, 0L});
                continue;
            }
            int baseLoad = Math.abs(rec.getApiPath().hashCode() % 120) + 10;
            double variation = 0.6 + ThreadLocalRandom.current().nextDouble() * 0.8;
            long calls = Math.max(0, Math.round(baseLoad * weight * variation));
            long errors = calls > 0 ? ThreadLocalRandom.current().nextLong(0, Math.max(1, calls / 20)) : 0L;
            result.put(rec.getApiPath(), new long[]{calls, errors});
        }
        return result;
    }

    private Map<String, long[]> fetchRealDay(RepoConfig repo, String instanceId,
                                              long startTime, long endTime) throws Exception {
        boolean debug = globalConfigRepo.findById(1L).map(GlobalConfig::isApmDebug).orElse(false);

        StringBuilder url = new StringBuilder(repo.getJenniferUrl())
                .append("?domain_id=").append(repo.getJenniferSid())
                .append("&start_time=").append(startTime)
                .append("&end_time=").append(endTime);
        if (!instanceId.isBlank()) {
            url.append("&instance_id=").append(URLEncoder.encode(instanceId, StandardCharsets.UTF_8));
        }
        if (repo.getJenniferFilter() != null && !repo.getJenniferFilter().isBlank()) {
            url.append("&filter=").append(URLEncoder.encode(repo.getJenniferFilter(), StandardCharsets.UTF_8));
        }

        if (debug) {
            log.debug("[JENNIFER-REQ] GET {}", url);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (repo.getJenniferBearerToken() != null && !repo.getJenniferBearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + repo.getJenniferBearerToken());
        }

        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (debug) {
            log.debug("[JENNIFER-RES] HTTP {} | length={} | body={}", resp.statusCode(), resp.body().length(),
                    resp.body().length() > 2000 ? resp.body().substring(0, 2000) + "...(truncated)" : resp.body());
        }

        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " — " + resp.body());
        }
        return parseResponse(resp.body());
    }

    /** Jennifer 응답 JSON 파싱 (실제/Mock 공통) */
    private Map<String, long[]> parseResponse(String responseBody) throws Exception {
        Map<String, long[]> result = new HashMap<>();
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode resultNode = root.path("result");
        if (resultNode.isArray()) {
            for (JsonNode r : resultNode) {
                String name = r.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                long calls  = r.path("calls").asLong(0);
                long errors = r.path("badResponses").asLong(0) + r.path("failures").asLong(0);
                result.put(name, new long[]{calls, errors});
            }
        }
        return result;
    }

    /** jennifer_oids JSON ([{"oid":10021,"shortName":"..."},...]) → "10021,10022" */
    private String buildInstanceId(String jenniferOids) {
        if (jenniferOids == null || jenniferOids.isBlank()) return "";
        try {
            StringJoiner sj = new StringJoiner(",");
            for (JsonNode node : objectMapper.readTree(jenniferOids)) {
                String oid = node.path("oid").asText(null);
                if (oid != null) sj.add(oid);
            }
            return sj.toString();
        } catch (Exception e) {
            log.warn("[JENNIFER] OID 파싱 실패: {}", e.getMessage());
            return "";
        }
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
        d.setSource("JENNIFER");
        return d;
    }
}
