package com.baek.viewer.service;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApmCallDataRepository;
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

/**
 * Whatap APM 연동 서비스 — 실제 API 호출 전용.
 * Mock 데이터가 필요하면 source=MOCK 사용 (ApmCollectionService.doGenerate).
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
    private final GlobalConfigRepository globalConfigRepo;

    public WhatapApmService(ApmCallDataRepository apmRepo, GlobalConfigRepository globalConfigRepo) {
        this.apmRepo = apmRepo;
        this.globalConfigRepo = globalConfigRepo;
    }
    /**
     * @param logCallback UI 로그 콜백 (nullable) — ApmCollectionService.addApmLog 전달용
     */
    /**
     * 실제 Whatap API 호출로 일별 데이터 수집. Mock 로직 없음.
     * Mock 데이터가 필요하면 source=MOCK 사용 (ApmCollectionService.doGenerate).
     */
    public Map<String, Object> collect(RepoConfig repo, LocalDate from, LocalDate to,
                                        java.util.function.BiConsumer<String, String> logCallback) {
        if (repo.getWhatapUrl() == null || repo.getWhatapUrl().isBlank()) {
            throw new IllegalStateException(
                    "WHATAP URL이 설정되지 않았습니다. " +
                    "레포 설정에서 Whatap URL을 입력하거나, Mock 데이터가 필요하면 source=MOCK을 사용하세요.");
        }

        GlobalConfig gc = globalConfigRepo.findById(1L).orElse(new GlobalConfig());
        List<Integer> okinds = parseOkinds(repo.getWhatapOkinds());
        int ptotal = gc.getWhatapPtotal();
        int psize = gc.getWhatapPsize();

        emit(logCallback, "INFO", String.format("WHATAP(실제API) 일별 수집 시작 — pcode=%s, ptotal=%d, psize=%d",
                repo.getWhatapPcode(), ptotal, psize));

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
                Map<String, long[]> dayData = fetchRealDay(repo, stimeMs, etimeMs, okinds, ptotal, psize);

                long dayTotal = dayData.values().stream().mapToLong(c -> c[0]).sum();
                long dayErrors = dayData.values().stream().mapToLong(c -> c[1]).sum();

                // 와탭 응답 JSON 그대로 전체 적재
                for (var entry : dayData.entrySet()) {
                    batch.add(buildEntry(repo.getRepoName(), entry.getKey(), null,
                            cursor, entry.getValue()[0], entry.getValue()[1]));
                    generated++;
                    if (batch.size() >= 1000) { apmRepo.saveAll(batch); batch.clear(); }
                }
                // 일별 로그 + 샘플 3개 API 호출건수
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
        emit(logCallback, "OK", String.format("WHATAP(실제API) 수집 완료 — %,d건 저장 (%s~%s)",
                generated, from, to));
        return Map.of("generated", generated, "from", from.toString(), "to", to.toString(),
                "source", "WHATAP", "mock", false);
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

    private Map<String, long[]> fetchRealDay(RepoConfig repo, long stime, long etime,
                                              List<Integer> okinds, int ptotal, int psize) throws Exception {
        boolean debug = globalConfigRepo.findById(1L).map(GlobalConfig::isApmDebug).orElse(false);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("stime", stime);       // 일별 시작 epoch ms
        params.put("etime", etime);       // 일별 종료 epoch ms
        params.put("ptotal", ptotal);     // 전체 건수 상한
        params.put("skip", 0);
        params.put("psize", psize);       // 한 페이지 조회 건수
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
            log.debug("[WHATAP-RES] HTTP {} | length={}", resp.statusCode(), resp.body().length());
            log.debug("[WHATAP-RES-BODY] {}", resp.body());
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
