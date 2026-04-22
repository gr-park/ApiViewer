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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * URL 차단 모니터링 — 와탭 트랜잭션 검색 호출 전용 서비스.
 *
 * 와탭 콘솔 API는 모두 공통 엔드포인트 {base}/yard/api/flush 로 POST하며,
 * 서버는 Referer 헤더로 요청 맥락(어떤 화면의 어느 pcode)을 판별한다.
 *
 * 요청 패턴 (POST JSON):
 *   URL     : {base}/yard/api/flush                                       — {base}는 RepoConfig.whatapUrl 에서 scheme+host+port 추출
 *   Referer : {base}{GlobalConfig.blockMonitorWhatapReferer, {pcode}치환}  — 기본 "/v2/project/apm/{pcode}/new/tx_profile" (화면 URL)
 *   Body    : { type:"profiles", path:"/v2/txsearch", pcode:"<pcode>",
 *               params:{ okinds:0, stime:<ms>, etime:<ms>, option:"forward",
 *                        ptotal:100, filter:{ error:"차단" } } }
 *
 * 와탭 flush는 body의 {type}+{pcode}+{path} 를 합쳐 "/profiles/pcode/{pcode}/v2/txsearch" 로 내부 라우팅하므로
 * path는 화면 URL이 아니라 실제 API 경로("/v2/txsearch")여야 한다.
 *
 * Cookie는 RepoConfig.whatapCookie (프로필 폴백값) 사용.
 * DB 저장 X — 매 조회마다 실시간 fetch.
 */
@Service
public class WhatapTxSearchService {

    private static final Logger log = LoggerFactory.getLogger(WhatapTxSearchService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST);
    private static final String BLOCK_PREFIX = "차단";
    /** 와탭 flush body.path — 트랜잭션 검색 API 경로 (화면 URL과 다름). type+pcode와 합쳐 /profiles/pcode/{pcode}/v2/txsearch 로 라우팅 */
    private static final String TX_SEARCH_API_PATH = "/v2/txsearch";

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
            repo.put("source", "WHATAP");
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
     * 와탭 연결 테스트 (keepalive 목적).
     * 가장 가벼운 payload(어제 10분 범위, filter 없음, ptotal=1)로 {base}/yard/api/flush 에 POST.
     * 조회 결과는 중요하지 않고 HTTP 200 응답 유지 여부만 판단 — 쿠키 세션 살아있는지 확인하는 용도.
     *
     * @return {ok, status, httpCode, timeMs, bodyLength, url, message}
     */
    public Map<String, Object> testConnection(String repoName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repoName", repoName);
        RepoConfig repo = repoRepo.findByRepoName(repoName).orElse(null);
        if (repo == null || !"Y".equalsIgnoreCase(repo.getWhatapEnabled()) || repo.getWhatapPcode() == null) {
            out.put("ok", false);
            out.put("message", "활성 와탭 레포 아님 (whatapEnabled/whatapPcode 확인)");
            return out;
        }
        String base = extractBase(repo.getWhatapUrl());
        if (base == null) {
            out.put("ok", false);
            out.put("message", "RepoConfig.whatapUrl 미설정/파싱 실패");
            return out;
        }
        GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
        String refererPath = gc.getBlockMonitorWhatapReferer().replace("{pcode}", String.valueOf(repo.getWhatapPcode()));
        String url = base + "/yard/api/flush";
        String referer = base + refererPath;

        // 가벼운 payload: 어제 10분 구간, filter 없음, ptotal=1
        long etime = java.time.LocalDate.now(KST).atStartOfDay(KST).toInstant().toEpochMilli();
        long stime = etime - 10 * 60 * 1000L;

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("okinds", 0);
        params.put("stime", stime);
        params.put("etime", etime);
        params.put("option", "forward");
        params.put("ptotal", 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "profiles");
        body.put("path", TX_SEARCH_API_PATH);
        body.put("pcode", String.valueOf(repo.getWhatapPcode()));
        body.put("params", params);

        long t0 = System.currentTimeMillis();
        try {
            String reqBody = om.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", referer)
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody));
            if (repo.getWhatapCookie() != null && !repo.getWhatapCookie().isBlank()) {
                builder.header("Cookie", repo.getWhatapCookie());
            }
            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            int code = resp.statusCode();
            boolean ok = (code == 200);
            out.put("ok", ok);
            out.put("httpCode", code);
            out.put("timeMs", ms);
            out.put("bodyLength", resp.body() != null ? resp.body().length() : 0);
            out.put("url", url);
            out.put("referer", referer);
            if (ok) {
                out.put("message", "연결 OK — 쿠키 세션 유효");
            } else if (code == 401 || code == 403) {
                out.put("message", "인증 실패 (" + code + ") — 쿠키 만료/무효. 재발급 필요");
            } else {
                String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length()));
                out.put("message", "HTTP " + code + " — " + snippet);
            }
            log.info("[URL차단모니터] 연결 테스트 repo={} code={} ms={} ok={}", repoName, code, ms, ok);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            out.put("ok", false);
            out.put("timeMs", ms);
            out.put("url", url);
            out.put("message", "요청 실패: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            log.warn("[URL차단모니터] 연결 테스트 실패 repo={} err={}", repoName, e.getMessage());
        }
        return out;
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
        String refererTpl = gc.getBlockMonitorWhatapReferer();
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
            String base = extractBase(r.getWhatapUrl());
            if (base == null) {
                log.warn("[URL차단모니터] {} whatapUrl 미설정/파싱 실패 — 스킵", r.getRepoName());
                continue;
            }
            String refererPath = refererTpl.replace("{pcode}", String.valueOf(r.getWhatapPcode()));
            LocalDate cursor = from;
            while (!cursor.isAfter(to)) {
                long stime = cursor.atStartOfDay(KST).toInstant().toEpochMilli();
                long etime = cursor.plusDays(1).atStartOfDay(KST).toInstant().toEpochMilli() - 1;
                try {
                    List<BlockedTxRow> day = fetchOneDay(base, refererPath, r, stime, etime, ptotal);
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

    /** RepoConfig.whatapUrl 에서 scheme+host+port 만 추출. 실패 시 null */
    private String extractBase(String whatapUrl) {
        if (whatapUrl == null || whatapUrl.isBlank()) return null;
        try {
            URI u = URI.create(whatapUrl.trim());
            if (u.getScheme() == null || u.getAuthority() == null) return null;
            return u.getScheme() + "://" + u.getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<BlockedTxRow> fetchOneDay(String base, String refererPath, RepoConfig repo,
                                            long stime, long etime, int ptotal) throws Exception {
        boolean debug = globalRepo.findById(1L).map(GlobalConfig::isApmDebug).orElse(false);

        // 페이로드: 사용자 캡쳐 형식 그대로
        Map<String, Object> params = new LinkedHashMap<>();
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
        body.put("path", TX_SEARCH_API_PATH);
        body.put("pcode", String.valueOf(repo.getWhatapPcode()));
        body.put("params", params);

        String reqBody = om.writeValueAsString(body);

        String url = base + "/yard/api/flush";
        String referer = base + refererPath;

        if (debug) {
            log.debug("[WHATAP-TX-REQ] POST {} | Referer={} | body={}", url, referer, reqBody);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", referer)
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

    private List<BlockedTxRow> parseRows(String body, String repoName) throws Exception {
        List<BlockedTxRow> out = new ArrayList<>();
        JsonNode root = om.readTree(body);
        JsonNode arr = root.isArray() ? root : root.path("data").isArray() ? root.path("data") :
                root.path("records").isArray() ? root.path("records") : null;
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            BlockedTxRow row = new BlockedTxRow();
            row.setSource("WHATAP");
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
            // 와탭 응답 필드명 후보: endtime / endTime / end_time / etime / time / startTime
            long endMs = firstLong(n, "endtime", "endTime", "end_time", "etime", "time", "startTime");
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

    /** 여러 필드명 후보 중 첫 번째로 발견되는 숫자 값 반환. 없으면 0 */
    private static long firstLong(JsonNode n, String... fields) {
        for (String f : fields) {
            JsonNode v = n.path(f);
            if (v.isMissingNode() || v.isNull()) continue;
            if (v.isNumber()) return v.asLong();
            if (v.isTextual()) {
                try { return Long.parseLong(v.asText().trim()); } catch (NumberFormatException ignored) {}
            }
        }
        return 0L;
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
