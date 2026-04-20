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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * URL 차단 모니터링 — 와탭 /v2/txsearch (트랜잭션 검색) 호출 전용 서비스.
 *
 * 요청 패턴 (POST JSON, 사용자 캡쳐 페이로드 기반):
 * URL : {GlobalConfig.whatapTxsearchBaseUrl}/v2/txsearch?stime=...&max=200&okinds=...&...
 * Body:
 *   { type:"profiles", path:"/v2/txsearch", pcode:"<pcode>",
 *     params:{ okinds:0, stime:<ms>, etime:<ms>, option:"forward",
 *              ptotal:100, filter:{ error:"차단" } } }
 *
 * DB 저장 X — 매 조회마다 실시간 fetch.
 */
@Service
public class WhatapTxSearchService {

    private static final Logger log = LoggerFactory.getLogger(WhatapTxSearchService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST);
    private static final String BLOCK_PREFIX = "차단";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper om = new ObjectMapper();

    private final GlobalConfigRepository globalRepo;
    private final RepoConfigRepository repoRepo;

    public WhatapTxSearchService(GlobalConfigRepository globalRepo, RepoConfigRepository repoRepo) {
        this.globalRepo = globalRepo;
        this.repoRepo = repoRepo;
    }

    /** 활성 와탭 레포 목록 (드롭다운용). okindName/okinds 인덱스 매칭 결과 함께 반환 */
    public List<Map<String, Object>> listActiveRepos() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RepoConfig r : repoRepo.findAllByOrderByRepoNameAsc()) {
            if (!"Y".equalsIgnoreCase(r.getWhatapEnabled())) continue;
            if (r.getWhatapPcode() == null) continue;
            List<Integer> ids = parseOkinds(r.getWhatapOkinds());
            List<String> names = splitCsv(r.getWhatapOkindsName());
            List<Map<String, Object>> okindList = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ids.get(i));
                m.put("name", i < names.size() ? names.get(i) : String.valueOf(ids.get(i)));
                okindList.add(m);
            }
            Map<String, Object> repo = new LinkedHashMap<>();
            repo.put("repoName", r.getRepoName());
            repo.put("pcode", r.getWhatapPcode());
            repo.put("okinds", okindList);
            out.add(repo);
        }
        return out;
    }

    /** GlobalConfig 기본 봇 키워드 */
    public List<String> defaultBotKeywords() {
        GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
        return splitCsv(gc.getBotKeywords());
    }

    /**
     * 검색.
     * @param repoName       null/blank = 전체 활성 레포
     * @param okindNames     null/empty = 해당 레포의 모든 okind. 입력 시 인덱스 매칭으로 id 추출
     * @param serviceLike    null/blank = 전체. 입력 시 service contains (대소문자 구분 X)
     * @param from..to       포함 일자 범위, 최대 7일
     * @param excludeBot     true = 봇 키워드 매칭된 row 제외
     * @param extraBotKeywords 사용자가 추가 입력한 봇 키워드 (GlobalConfig 기본값에 합산)
     */
    public List<BlockedTxRow> search(String repoName, List<String> okindNames, String serviceLike,
                                     LocalDate from, LocalDate to,
                                     boolean excludeBot, List<String> extraBotKeywords) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to 필수");
        if (to.isBefore(from)) throw new IllegalArgumentException("종료일이 시작일보다 이전입니다");
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;

        GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
        String baseUrl = gc.getWhatapTxsearchBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("GlobalConfig.whatapTxsearchBaseUrl 미설정 — 설정 화면에서 입력하세요");
        }
        int ptotal = gc.getWhatapPtotal();

        List<RepoConfig> repos = pickRepos(repoName);
        if (repos.isEmpty()) {
            log.info("[URL차단모니터] 활성 와탭 레포 없음 (repoName={})", repoName);
            return List.of();
        }

        // 봇 키워드: GlobalConfig 자동 합산 제거 — 프론트에서 초기값으로 세팅하여 사용자가 직접 제어
        Set<String> botSet = buildBotSet(extraBotKeywords);
        String svc = (serviceLike == null) ? "" : serviceLike.trim().toLowerCase(Locale.ROOT);

        log.info("[URL차단모니터] 검색 시작 repos={} days={} excludeBot={} botSize={} serviceLike='{}'",
                repos.size(), days, excludeBot, botSet.size(), svc);

        List<BlockedTxRow> result = new ArrayList<>();
        for (RepoConfig r : repos) {
            List<Integer> okinds = resolveOkinds(r, okindNames);
            log.debug("[URL차단모니터] repo={} okinds={}", r.getRepoName(), okinds);
            LocalDate cursor = from;
            while (!cursor.isAfter(to)) {
                long stime = cursor.atStartOfDay(KST).toInstant().toEpochMilli();
                long etime = cursor.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli() - 1;
                try {
                    List<BlockedTxRow> day = fetchOneDay(baseUrl, r, okinds, stime, etime, ptotal);
                    log.debug("[URL차단모니터] {} {} 응답={}건", r.getRepoName(), cursor, day.size());
                    for (BlockedTxRow row : day) {
                        if (row.getErrMessage() == null || !row.getErrMessage().startsWith(BLOCK_PREFIX)) continue;
                        if (!svc.isEmpty() && (row.getService() == null
                                || !row.getService().toLowerCase(Locale.ROOT).contains(svc))) continue;
                        boolean isBot = matchBot(row, botSet);
                        row.setBot(isBot);
                        if (excludeBot && isBot) continue;
                        result.add(row);
                    }
                } catch (Exception e) {
                    log.warn("[URL차단모니터] {} {} 호출 실패: {}", r.getRepoName(), cursor, e.getMessage());
                }
                cursor = cursor.plusDays(1);
            }
        }
        result.sort((a, b) -> {
            String ea = a.getEndtime() == null ? "" : a.getEndtime();
            String eb = b.getEndtime() == null ? "" : b.getEndtime();
            return eb.compareTo(ea);
        });
        log.info("[URL차단모니터] 검색 완료 총={}건", result.size());
        return result;
    }

    private List<RepoConfig> pickRepos(String repoName) {
        if (repoName == null || repoName.isBlank()) {
            List<RepoConfig> out = new ArrayList<>();
            for (RepoConfig r : repoRepo.findAllByOrderByRepoNameAsc()) {
                if ("Y".equalsIgnoreCase(r.getWhatapEnabled()) && r.getWhatapPcode() != null) out.add(r);
            }
            return out;
        }
        return repoRepo.findByRepoName(repoName)
                .filter(r -> "Y".equalsIgnoreCase(r.getWhatapEnabled()) && r.getWhatapPcode() != null)
                .map(List::of).orElse(List.of());
    }

    private List<Integer> resolveOkinds(RepoConfig repo, List<String> okindNames) {
        List<Integer> ids = parseOkinds(repo.getWhatapOkinds());
        if (okindNames == null || okindNames.isEmpty()) return ids;
        List<String> names = splitCsv(repo.getWhatapOkindsName());
        Set<String> wanted = new HashSet<>(okindNames);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < ids.size() && i < names.size(); i++) {
            if (wanted.contains(names.get(i))) out.add(ids.get(i));
        }
        return out;
    }

    private List<BlockedTxRow> fetchOneDay(String baseUrl, RepoConfig repo, List<Integer> okinds,
                                            long stime, long etime, int ptotal) throws Exception {
        boolean debug = globalRepo.findById(1L).map(GlobalConfig::isApmDebug).orElse(false);

        // 페이로드: 사용자 캡쳐 형식 그대로
        Map<String, Object> params = new LinkedHashMap<>();
        // okinds는 단일/0 — 다중 okinds는 query string으로
        params.put("okinds", 0);
        params.put("stime", stime);
        params.put("etime", etime);
        params.put("option", "forward");
        params.put("ptotal", ptotal);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("error", BLOCK_PREFIX);
        params.put("filter", filter);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "profiles");
        body.put("path", "/v2/txsearch");
        body.put("pcode", String.valueOf(repo.getWhatapPcode()));
        body.put("params", params);

        String reqBody = om.writeValueAsString(body);

        String url = buildUrl(baseUrl, stime, etime, okinds, ptotal);

        if (debug) {
            log.debug("[WHATAP-TX-REQ] POST {} | body={}", url, reqBody);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody));
        if (repo.getWhatapCookie() != null && !repo.getWhatapCookie().isBlank()) {
            builder.header("Cookie", repo.getWhatapCookie());
        }

        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (debug) {
            log.debug("[WHATAP-TX-RES] HTTP {} length={}", resp.statusCode(), resp.body().length());
            log.debug("[WHATAP-TX-RES-BODY] {}", resp.body());
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " — " + resp.body());
        }
        return parseRows(resp.body(), repo.getRepoName());
    }

    private String buildUrl(String baseUrl, long stime, long etime, List<Integer> okinds, int ptotal) {
        StringBuilder sb = new StringBuilder();
        sb.append(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl);
        sb.append("/v2/txsearch?");
        sb.append("interval=3600");
        sb.append("&max=200");
        sb.append("&skip=0");
        sb.append("&stime=").append(stime);
        sb.append("&etime=").append(etime);
        sb.append("&ptotal=").append(ptotal);
        sb.append("&option=forward");
        sb.append("&filterKey=error");
        sb.append("&filterValue=").append(URLEncoder.encode(BLOCK_PREFIX, StandardCharsets.UTF_8));
        for (Integer id : okinds) {
            sb.append("&okinds=").append(id);
        }
        return sb.toString();
    }

    private List<BlockedTxRow> parseRows(String body, String repoName) throws Exception {
        List<BlockedTxRow> out = new ArrayList<>();
        JsonNode root = om.readTree(body);
        JsonNode arr = root.isArray() ? root : root.path("data").isArray() ? root.path("data") :
                root.path("records").isArray() ? root.path("records") : null;
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            BlockedTxRow row = new BlockedTxRow();
            row.setRepoName(repoName);
            row.setOkindName(text(n, "okindName"));
            row.setService(text(n, "service"));
            row.setMethod(text(n, "method"));
            row.setDomain(text(n, "domain"));
            row.setPodName(firstNonBlank(text(n, "podName"), text(n, "podNanme")));
            row.setCountry(text(n, "country"));
            row.setClientType(text(n, "clientType"));
            row.setClientName(text(n, "clientName"));
            row.setClientOs(text(n, "clientOs"));
            row.setOName(firstNonBlank(text(n, "oName"), text(n, "onodeName")));
            row.setErrClass(text(n, "errClass"));
            row.setErrMessage(text(n, "errMessage"));
            row.setIpAddr(text(n, "ipAddr"));
            row.setUserAgent(text(n, "userAgent"));
            long endMs = n.path("endtime").asLong(0);
            if (endMs > 0) {
                row.setEndtime(TS_FMT.format(Instant.ofEpochMilli(endMs)));
            }
            out.add(row);
        }
        return out;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** 대소문자 구분하여 clientType / clientName / userAgent 중 키워드 포함 여부 판정 */
    private boolean matchBot(BlockedTxRow row, Set<String> botSet) {
        if (botSet.isEmpty()) return false;
        String hay = (row.getClientType() == null ? "" : row.getClientType()) + " "
                + (row.getClientName() == null ? "" : row.getClientName()) + " "
                + (row.getUserAgent() == null ? "" : row.getUserAgent());
        for (String kw : botSet) {
            if (hay.contains(kw)) return true;
        }
        return false;
    }

    /** extraBotKeywords만으로 봇 키워드 Set 구성 (GlobalConfig 자동 합산 없음) */
    private Set<String> buildBotSet(List<String> extra) {
        Set<String> out = new HashSet<>();
        if (extra != null) {
            for (String s : extra) {
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    private List<Integer> parseOkinds(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        String t = s.trim();
        if (t.startsWith("[")) {
            try {
                for (JsonNode n : om.readTree(t)) out.add(n.asInt());
                return out;
            } catch (Exception ignored) {}
        }
        for (String tok : t.split(",")) {
            try { out.add(Integer.parseInt(tok.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : s.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
