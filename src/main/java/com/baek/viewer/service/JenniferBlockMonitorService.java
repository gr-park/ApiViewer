package com.baek.viewer.service;

import com.baek.viewer.model.BlockedTxRow;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
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
 * URL 차단 모니터링 — Jennifer 에러 검색 호출 전용 서비스.
 *
 * 요청 형식 (GET):
 *   URL: {jenniferBase}/api/dbsearch/error
 *        ?domain_id={sid}&instance_id={oid1,oid2,...}&stime={epochMs}&etime={epochMs}
 *   Header: Authorization: Bearer {bearerToken}
 *
 * 응답 형식:
 *   { "result": [{ "applicationName":..., "domainId":..., "domainName":..., "errorType":...,
 *                  "instanceId":..., "instanceName":..., "instanceOid":..., "message":...,
 *                  "profileIndex":..., "time":..., "txid":..., "value":... }] }
 *
 * 필터: result 배열에서 message 에 "차단" 문자열이 포함된 항목만 반환.
 * DB 저장 X — 매 조회마다 실시간 fetch.
 */
@Service
public class JenniferBlockMonitorService {

    private static final Logger log = LoggerFactory.getLogger(JenniferBlockMonitorService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String BLOCK_KEYWORD = "차단";
    private static final String ERROR_SEARCH_PATH = "/api/dbsearch/error";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper om = new ObjectMapper();

    private final GlobalConfigRepository globalRepo;
    private final RepoConfigRepository repoRepo;

    public JenniferBlockMonitorService(GlobalConfigRepository globalRepo, RepoConfigRepository repoRepo) {
        this.globalRepo = globalRepo;
        this.repoRepo = repoRepo;
    }

    /** 활성 Jennifer 레포 목록 (드롭다운용). */
    public List<Map<String, Object>> listActiveRepos() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RepoConfig r : repoRepo.findAllByOrderByRepoNameAsc()) {
            if (!"Y".equalsIgnoreCase(r.getJenniferEnabled())) continue;
            if (r.getJenniferSid() == null) continue;
            List<Map<String, Object>> oidList = parseOids(r.getJenniferOids());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("repoName", r.getRepoName());
            entry.put("source", "JENNIFER");
            entry.put("sid", r.getJenniferSid());
            entry.put("oids", oidList);
            out.add(entry);
        }
        return out;
    }

    /**
     * Jennifer 에러 검색.
     *
     * @param repoName        null/blank = 전체 활성 Jennifer 레포
     * @param serviceLike     null/blank = 전체. 입력 시 applicationName contains (대소문자 구분 X)
     * @param from..to        포함 일자 범위
     * @param excludeBot      현재 Jennifer 응답에는 봇 판단 필드 없음 — 항상 false (파라미터 일관성 유지)
     * @param extraBotKeywords 현재 미사용 (Jennifer 응답에 userAgent 없음)
     */
    public List<BlockedTxRow> search(String repoName, String serviceLike,
                                      LocalDate from, LocalDate to,
                                      boolean excludeBot, List<String> extraBotKeywords) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to 필수");
        if (to.isBefore(from)) throw new IllegalArgumentException("종료일이 시작일보다 이전입니다");

        List<RepoConfig> repos = pickRepos(repoName);
        if (repos.isEmpty()) {
            log.debug("[URL차단모니터][JENNIFER] 활성 Jennifer 레포 없음 (repoName={})", repoName);
            return List.of();
        }

        String svc = (serviceLike == null) ? "" : serviceLike.trim().toLowerCase(Locale.ROOT);
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;

        log.info("[URL차단모니터][JENNIFER] 검색 시작 repos={} days={} serviceLike='{}'",
                repos.size(), days, svc);

        List<BlockedTxRow> result = new ArrayList<>();
        for (RepoConfig r : repos) {
            String base = extractBase(r.getJenniferUrl());
            if (base == null) {
                log.warn("[URL차단모니터][JENNIFER] {} jenniferUrl 미설정/파싱 실패 — 스킵", r.getRepoName());
                continue;
            }
            String instanceId = buildInstanceId(r.getJenniferOids());
            long stime = from.atStartOfDay(KST).toInstant().toEpochMilli();
            long etime = to.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli() - 1;

            try {
                List<BlockedTxRow> rows = fetchRows(base, r, instanceId, stime, etime);
                log.debug("[URL차단모니터][JENNIFER] {} 응답={}건", r.getRepoName(), rows.size());
                for (BlockedTxRow row : rows) {
                    if (!svc.isEmpty() && (row.getApplicationName() == null
                            || !row.getApplicationName().toLowerCase(Locale.ROOT).contains(svc))) continue;
                    result.add(row);
                }
            } catch (Exception e) {
                log.warn("[URL차단모니터][JENNIFER] {} 호출 실패: {}", r.getRepoName(), e.getMessage());
            }
        }

        result.sort((a, b) -> {
            String ta = a.getEndtime() == null ? "" : a.getEndtime();
            String tb = b.getEndtime() == null ? "" : b.getEndtime();
            return tb.compareTo(ta);
        });

        log.info("[URL차단모니터][JENNIFER] 검색 완료 총={}건", result.size());
        return result;
    }

    private List<RepoConfig> pickRepos(String repoName) {
        if (repoName == null || repoName.isBlank()) {
            List<RepoConfig> out = new ArrayList<>();
            for (RepoConfig r : repoRepo.findAllByOrderByRepoNameAsc()) {
                if ("Y".equalsIgnoreCase(r.getJenniferEnabled()) && r.getJenniferSid() != null) out.add(r);
            }
            return out;
        }
        return repoRepo.findByRepoName(repoName)
                .filter(r -> "Y".equalsIgnoreCase(r.getJenniferEnabled()) && r.getJenniferSid() != null)
                .map(List::of).orElse(List.of());
    }

    private List<BlockedTxRow> fetchRows(String base, RepoConfig repo, String instanceId,
                                          long stime, long etime) throws Exception {
        boolean debug = globalRepo.findById(1L).map(GlobalConfig::isApmDebug).orElse(false);

        StringBuilder url = new StringBuilder(base).append(ERROR_SEARCH_PATH)
                .append("?domain_id=").append(repo.getJenniferSid())
                .append("&stime=").append(stime)
                .append("&etime=").append(etime);
        if (!instanceId.isBlank()) {
            url.append("&instance_id=").append(instanceId);
        }
        if (repo.getJenniferFilter() != null && !repo.getJenniferFilter().isBlank()) {
            url.append("&filter=").append(java.net.URLEncoder.encode(repo.getJenniferFilter(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        if (debug) {
            log.debug("[JENNIFER-BLOCK-REQ] GET {}", url);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET();
        if (repo.getJenniferBearerToken() != null && !repo.getJenniferBearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + repo.getJenniferBearerToken());
        }

        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (debug) {
            log.debug("[JENNIFER-BLOCK-RES] HTTP {} length={}", resp.statusCode(), resp.body().length());
            log.debug("[JENNIFER-BLOCK-RES-BODY] {}", resp.body());
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " — " + resp.body());
        }
        return parseRows(resp.body(), repo.getRepoName());
    }

    private List<BlockedTxRow> parseRows(String body, String repoName) throws Exception {
        List<BlockedTxRow> out = new ArrayList<>();
        JsonNode root = om.readTree(body);
        JsonNode arr = root.path("result");
        if (!arr.isArray()) return out;

        for (JsonNode n : arr) {
            String message = text(n, "message");
            // "차단" 포함 여부로 필터링
            if (message == null || !message.contains(BLOCK_KEYWORD)) continue;

            BlockedTxRow row = new BlockedTxRow();
            row.setSource("JENNIFER");
            row.setRepoName(repoName);
            row.setApplicationName(text(n, "applicationName"));
            row.setDomainId(textNum(n, "domainId"));
            row.setDomainName(text(n, "domainName"));
            row.setInstanceId(textNum(n, "instanceId"));
            row.setInstanceName(text(n, "instanceName"));
            row.setErrorType(text(n, "errorType"));
            row.setMessage(message);
            row.setBot(false); // Jennifer 응답에 botg 판단 필드 없음

            // time 필드: 에포크 ms 또는 문자열로 올 수 있음
            String timeStr = text(n, "time");
            if (timeStr != null && !timeStr.isBlank()) {
                try {
                    long timeMs = Long.parseLong(timeStr.trim());
                    row.setEndtime(java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(KST)
                            .format(java.time.Instant.ofEpochMilli(timeMs)));
                } catch (NumberFormatException e) {
                    row.setEndtime(timeStr);
                }
            }
            out.add(row);
        }
        return out;
    }

    /** JSON 노드에서 문자열 추출 */
    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    /** 숫자 노드를 문자열로 추출 (정수 표시용) */
    private static String textNum(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return String.valueOf(v.asLong());
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    /** jennifer_oids JSON → "10021,10022" */
    private String buildInstanceId(String jenniferOids) {
        if (jenniferOids == null || jenniferOids.isBlank()) return "";
        try {
            StringJoiner sj = new StringJoiner(",");
            for (JsonNode node : om.readTree(jenniferOids)) {
                String oid = node.path("oid").asText(null);
                if (oid != null) sj.add(oid);
            }
            return sj.toString();
        } catch (Exception e) {
            log.warn("[JENNIFER] OID 파싱 실패: {}", e.getMessage());
            return "";
        }
    }

    /** jennifer_oids JSON → [{"oid":..., "shortName":...}, ...] 형태로 변환 */
    private List<Map<String, Object>> parseOids(String jenniferOids) {
        if (jenniferOids == null || jenniferOids.isBlank()) return List.of();
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode node : om.readTree(jenniferOids)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("oid", node.path("oid").asText(""));
                m.put("shortName", node.path("shortName").asText(""));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("[JENNIFER] OID 목록 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /** jenniferUrl 에서 scheme+host+port 만 추출. 실패 시 null */
    private String extractBase(String jenniferUrl) {
        if (jenniferUrl == null || jenniferUrl.isBlank()) return null;
        try {
            URI u = URI.create(jenniferUrl.trim());
            if (u.getScheme() == null || u.getAuthority() == null) return null;
            return u.getScheme() + "://" + u.getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
