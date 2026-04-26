package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.JiraConfig;
import com.baek.viewer.model.JiraUserMapping;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.JiraConfigRepository;
import com.baek.viewer.repository.JiraUserMappingRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class JiraService {

    private static final Logger log = LoggerFactory.getLogger(JiraService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Epic Name 필드 ID 캐시 (baseUrl → customfield_XXXXX). "" = 필드 없음/사용 불가. */
    private static final Map<String, String> EPIC_NAME_FIELD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Epic Link 필드 ID 캐시 (baseUrl → customfield_XXXXX 또는 "parent"). "" = 필드 없음 → 레이블 폴백. */
    private static final Map<String, String> EPIC_LINK_FIELD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 차단근거 내 이슈 키 패턴: [CSR-123] 또는 단독 CSR-123 / OP-456 형태 */
    private static final java.util.regex.Pattern TICKET_KEY_PATTERN =
            java.util.regex.Pattern.compile("\\[((?:[A-Z][A-Z0-9]+-\\d+))\\]|\\b([A-Z][A-Z0-9]+-\\d+)\\b");

    /** 내부 SmartWay 서버의 사설 인증서 수용용: 모든 인증서를 신뢰하는 SSLSocketFactory. */
    private static final SSLSocketFactory TRUST_ALL_SSL_FACTORY = buildTrustAllSslFactory();
    /** 내부망 호스트명 검증 우회 HostnameVerifier. */
    private static final HostnameVerifier TRUST_ALL_HOSTNAME = (hostname, session) -> true;

    private static SSLSocketFactory buildTrustAllSslFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            log.error("[Jira] Trust-All SSLContext 초기화 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    private final ApiRecordRepository recordRepo;
    private final RepoConfigRepository repoConfigRepo;
    private final JiraConfigRepository jiraConfigRepo;
    private final JiraUserMappingRepository userMappingRepo;

    public JiraService(ApiRecordRepository recordRepo,
                       RepoConfigRepository repoConfigRepo,
                       JiraConfigRepository jiraConfigRepo,
                       JiraUserMappingRepository userMappingRepo) {
        this.recordRepo = recordRepo;
        this.repoConfigRepo = repoConfigRepo;
        this.jiraConfigRepo = jiraConfigRepo;
        this.userMappingRepo = userMappingRepo;
    }

    // ========================================================================
    //  Jira REST API 래핑
    // ========================================================================

    /**
     * Bearer Token + JSON + 디버그 모드 시 요청/응답 전문 로깅 인터셉터 적용.
     * BufferingClientHttpRequestFactory 를 사용해 응답 바디를 두 번 읽을 수 있도록 한다.
     */
    private RestTemplate buildRestTemplate(JiraConfig config) {
        // BufferingFactory: 응답 스트림을 버퍼링해 인터셉터에서 바디를 읽어도 이후 역직렬화 가능
        // 망분리 내부 SmartWay 서버는 사설/내부 CA 인증서를 사용하므로 HTTPS 연결에 한해
        // SSL 검증을 우회한다 (해당 RestTemplate 인스턴스 범위로만 적용, JVM 전역 영향 없음).
        SimpleClientHttpRequestFactory inner = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                if (connection instanceof HttpsURLConnection https) {
                    SSLSocketFactory sf = TRUST_ALL_SSL_FACTORY;
                    if (sf != null) {
                        https.setSSLSocketFactory(sf);
                        https.setHostnameVerifier(TRUST_ALL_HOSTNAME);
                    }
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        inner.setConnectTimeout(10_000);
        inner.setReadTimeout(30_000);
        RestTemplate rt = new RestTemplate(new BufferingClientHttpRequestFactory(inner));

        String token = config.getApiToken();

        // 인증 + Content-Type + 요청/응답 상세 로깅 인터셉터
        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));

            String bodyStr = body.length > 0 ? new String(body, StandardCharsets.UTF_8) : "(empty)";
            log.info("[SmartWay →] {} {} | body={}",
                    request.getMethod(), request.getURI(),
                    bodyStr.length() > 1000 ? bodyStr.substring(0, 1000) + "…(truncated)" : bodyStr);

            var response = execution.execute(request, body);

            try {
                byte[] respBody = response.getBody().readAllBytes();
                String respStr = new String(respBody, StandardCharsets.UTF_8);
                log.info("[SmartWay ←] HTTP {} {} {} | body={}",
                        response.getStatusCode().value(),
                        request.getMethod(), request.getURI(),
                        respStr.length() > 1000 ? respStr.substring(0, 1000) + "…(truncated)" : respStr);
            } catch (IOException ignored) {}

            return response;
        };

        rt.setInterceptors(List.of(authInterceptor));
        return rt;
    }

    /**
     * POST /rest/api/2/issue — 이슈 생성
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createIssue(RestTemplate rt, JiraConfig cfg, Map<String, Object> fields) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/issue";
        Map<String, Object> body = Map.of("fields", fields);
        log.info("[SmartWay] createIssue → {}", url);
        try {
            Map<String, Object> result = rt.postForObject(url, body, Map.class);
            String key = result != null ? (String) result.get("key") : "null";
            log.info("[SmartWay] 이슈 생성 완료: key={}, self={}", key,
                    result != null ? result.get("self") : "-");
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.error("[SmartWay] 이슈 생성 실패: {} | url={}", e.getMessage(), url);
            throw new RuntimeException("Jira 이슈 생성 실패: " + e.getMessage(), e);
        }
    }

    /**
     * PUT /rest/api/2/issue/{issueKey} — 이슈 업데이트
     */
    private void updateIssue(RestTemplate rt, JiraConfig cfg, String issueKey, Map<String, Object> fields) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/issue/" + issueKey;
        Map<String, Object> body = Map.of("fields", fields);
        log.info("[SmartWay] updateIssue → {} | fields={}", url, fields.keySet());
        try {
            rt.put(url, body);
            log.info("[SmartWay] 이슈 업데이트 완료: {}", issueKey);
        } catch (Exception e) {
            log.error("[SmartWay] 이슈 업데이트 실패 {}: {} | url={}", issueKey, e.getMessage(), url);
            throw new RuntimeException("Jira 이슈 업데이트 실패: " + e.getMessage(), e);
        }
    }

    /**
     * GET /rest/api/2/issue/{issueKey} — 이슈 조회
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getIssue(RestTemplate rt, JiraConfig cfg, String issueKey) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/issue/" + issueKey;
        log.info("[SmartWay] getIssue → {}", url);
        try {
            Map<String, Object> result = rt.getForObject(url, Map.class);
            if (result != null && result.containsKey("fields")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> f = (Map<String, Object>) result.get("fields");
                @SuppressWarnings("unchecked")
                Map<String, Object> st = f != null ? (Map<String, Object>) f.get("status") : null;
                log.info("[SmartWay] getIssue ← issueKey={}, status={}", issueKey,
                        st != null ? st.get("name") : "?");
            }
            return result != null ? result : Map.of();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("[SmartWay] 이슈 미존재: {} | url={}", issueKey, url);
                return Map.of();
            }
            log.error("[SmartWay] 이슈 조회 실패 {}: {} | url={}", issueKey, e.getMessage(), url);
            throw new RuntimeException("Jira 이슈 조회 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[SmartWay] 이슈 조회 실패 {}: {} | url={}", issueKey, e.getMessage(), url);
            throw new RuntimeException("Jira 이슈 조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * POST /rest/api/2/issueLink — 이슈 연결 (Relates)
     * inwardIssue: 새로 생성한 이슈, outwardIssue: 차단근거 티켓
     */
    private void createIssueLink(RestTemplate rt, JiraConfig cfg, String newIssueKey, String linkedIssueKey) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/issueLink";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", Map.of("name", "Relates"));
        body.put("inwardIssue", Map.of("key", newIssueKey));
        body.put("outwardIssue", Map.of("key", linkedIssueKey));
        log.debug("[SmartWay] issueLink → {} ↔ {} | url={}", newIssueKey, linkedIssueKey, url);
        try {
            rt.postForObject(url, body, Map.class);
            log.info("[SmartWay] 이슈 연결 완료: {} ↔ {}", newIssueKey, linkedIssueKey);
        } catch (Exception e) {
            log.warn("[SmartWay] 이슈 연결 실패 ({} ↔ {}): {} | url={}", newIssueKey, linkedIssueKey, e.getMessage(), url);
        }
    }

    /**
     * 차단근거(blockedReason)에서 이슈 키를 추출해 issueLink API로 연결.
     * 링크 실패 시 WARN 로그 후 계속 진행 (이슈 생성 자체는 영향 없음).
     */
    private void linkBlockedReasonTickets(RestTemplate rt, JiraConfig cfg, String newIssueKey, String blockedReason) {
        if (blockedReason == null || blockedReason.isBlank()) {
            log.debug("[SmartWay] linkBlockedReasonTickets: 차단근거 없음 — 스킵");
            return;
        }
        java.util.regex.Matcher m = TICKET_KEY_PATTERN.matcher(blockedReason);
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            String key = m.group(1) != null ? m.group(1) : m.group(2);
            if (!keys.contains(key)) keys.add(key);
        }
        if (keys.isEmpty()) {
            log.debug("[SmartWay] linkBlockedReasonTickets: 티켓 키 미발견 — 차단근거={}", blockedReason);
            return;
        }
        log.info("[SmartWay] linkBlockedReasonTickets: {} → 연결 대상={}", newIssueKey, keys);
        for (String linkedKey : keys) {
            createIssueLink(rt, cfg, newIssueKey, linkedKey);
        }
    }

    /**
     * POST /rest/api/2/search — JQL 검색
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searchByJql(RestTemplate rt, JiraConfig cfg, String jql, int maxResults) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/search";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        body.put("fields", List.of("summary", "status", "resolution", "assignee", "components", "labels", "priority"));
        log.info("[SmartWay] searchByJql → {} | jql={} | maxResults={}", url, jql, maxResults);
        try {
            Map<String, Object> result = rt.postForObject(url, body, Map.class);
            if (result != null && result.get("issues") instanceof List) {
                List<Map<String, Object>> issues = (List<Map<String, Object>>) result.get("issues");
                log.info("[SmartWay] searchByJql ← total={}, returned={}",
                        result.getOrDefault("total", "?"), issues.size());
                return issues;
            }
            log.info("[SmartWay] searchByJql ← 결과 없음");
            return List.of();
        } catch (Exception e) {
            log.error("[SmartWay] JQL 검색 실패: jql={}, error={}", jql, e.getMessage());
            return List.of();
        }
    }

    /**
     * Epic Name 필드 ID 동적 조회 (예: customfield_10011, customfield_10105 등 인스턴스마다 다름).
     * GET /rest/api/2/field 응답에서 schema.custom == "com.pyxis.greenhopper.jira:gh-epic-label" 인 필드 ID 를 반환.
     * 못 찾으면 빈 문자열 반환. 결과는 baseUrl 기준으로 캐싱.
     */
    @SuppressWarnings("unchecked")
    private String resolveEpicNameFieldId(RestTemplate rt, JiraConfig cfg) {
        String baseUrl = cfg.getJiraBaseUrl();
        String cached = EPIC_NAME_FIELD_CACHE.get(baseUrl);
        if (cached != null) return cached;

        String url = baseUrl + "/rest/api/2/field";
        try {
            List<Map<String, Object>> fields = rt.getForObject(url, List.class);
            if (fields != null) {
                for (Map<String, Object> f : fields) {
                    Map<String, Object> schema = (Map<String, Object>) f.get("schema");
                    String custom = schema != null ? (String) schema.get("custom") : null;
                    if ("com.pyxis.greenhopper.jira:gh-epic-label".equals(custom)) {
                        String id = (String) f.get("id");
                        log.info("[Jira] Epic Name 필드 동적 해석: {} (name={})", id, f.get("name"));
                        EPIC_NAME_FIELD_CACHE.put(baseUrl, id);
                        return id;
                    }
                }
                // 폴백: 이름 기반 ("Epic Name") 매칭
                for (Map<String, Object> f : fields) {
                    String name = (String) f.get("name");
                    if ("Epic Name".equalsIgnoreCase(name)) {
                        String id = (String) f.get("id");
                        log.info("[Jira] Epic Name 필드 이름 매칭 해석: {}", id);
                        EPIC_NAME_FIELD_CACHE.put(baseUrl, id);
                        return id;
                    }
                }
            }
            log.warn("[Jira] Epic Name 필드 미발견 → Epic 생성 시 스킵");
        } catch (Exception e) {
            log.warn("[Jira] 필드 메타데이터 조회 실패: {} | url={}", e.getMessage(), url);
        }
        EPIC_NAME_FIELD_CACHE.put(baseUrl, "");
        return "";
    }

    /**
     * Epic Link 필드 ID 동적 조회.
     * 구형(Classic): schema.custom == "com.pyxis.greenhopper.jira:gh-epic-link" → customfield_XXXXX
     * 신형(Next-gen/Team-managed): "parent" 반환
     * 미발견: "" 반환 (호출부는 레이블 폴백 사용)
     */
    @SuppressWarnings("unchecked")
    private String resolveEpicLinkFieldId(RestTemplate rt, JiraConfig cfg) {
        String baseUrl = cfg.getJiraBaseUrl();
        String cached = EPIC_LINK_FIELD_CACHE.get(baseUrl);
        if (cached != null) return cached;

        String url = baseUrl + "/rest/api/2/field";
        try {
            List<Map<String, Object>> fields = rt.getForObject(url, List.class);
            if (fields != null) {
                for (Map<String, Object> f : fields) {
                    Map<String, Object> schema = (Map<String, Object>) f.get("schema");
                    String custom = schema != null ? (String) schema.get("custom") : null;
                    if ("com.pyxis.greenhopper.jira:gh-epic-link".equals(custom)) {
                        String id = (String) f.get("id");
                        log.info("[Jira] Epic Link 필드 동적 해석: {} (name={})", id, f.get("name"));
                        EPIC_LINK_FIELD_CACHE.put(baseUrl, id);
                        return id;
                    }
                }
                for (Map<String, Object> f : fields) {
                    String name = (String) f.get("name");
                    if ("Epic Link".equalsIgnoreCase(name)) {
                        String id = (String) f.get("id");
                        log.info("[Jira] Epic Link 필드 이름 매칭 해석: {}", id);
                        EPIC_LINK_FIELD_CACHE.put(baseUrl, id);
                        return id;
                    }
                }
            }
            log.info("[Jira] Epic Link 커스텀 필드 미발견 → Next-gen 가정하여 'parent' 사용");
            EPIC_LINK_FIELD_CACHE.put(baseUrl, "parent");
            return "parent";
        } catch (Exception e) {
            log.warn("[Jira] Epic Link 필드 메타 조회 실패: {} | url={}", e.getMessage(), url);
        }
        EPIC_LINK_FIELD_CACHE.put(baseUrl, "");
        return "";
    }

    /**
     * Epic 검색 또는 생성
     */
    @SuppressWarnings("unchecked")
    private String getOrCreateEpic(RestTemplate rt, JiraConfig cfg, String epicName) {
        String jql = "project = " + cfg.getProjectKey()
                + " AND issuetype = Epic AND summary ~ \"" + epicName.replace("\"", "\\\"") + "\"";
        log.info("[SmartWay] Epic 검색: epicName={}", epicName);
        List<Map<String, Object>> results = searchByJql(rt, cfg, jql, 1);
        if (!results.isEmpty()) {
            String existingKey = (String) results.get(0).get("key");
            log.info("[SmartWay] Epic 기존 사용: {} → {}", epicName, existingKey);
            return existingKey;
        }

        log.info("[SmartWay] Epic 신규 생성: {}", epicName);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", cfg.getProjectKey()));
        fields.put("issuetype", Map.of("name", "Epic"));
        fields.put("summary", epicName);

        // Epic Name 필드는 인스턴스마다 custom field ID 가 다르므로 동적 조회.
        // 인스턴스가 차세대(Next-gen)/이름 미사용 구성이면 빈 값이 반환되어 필드를 추가하지 않는다.
        String epicNameFieldId = resolveEpicNameFieldId(rt, cfg);
        if (epicNameFieldId != null && !epicNameFieldId.isEmpty()) {
            fields.put(epicNameFieldId, epicName);
        }

        Map<String, Object> created;
        try {
            created = createIssue(rt, cfg, fields);
        } catch (RuntimeException ex) {
            // 해석한 Epic Name 필드가 Create 화면에 없거나 unknown 인 경우: 해당 필드만 제거 후 1회 재시도.
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (epicNameFieldId != null && !epicNameFieldId.isEmpty()
                    && (msg.contains(epicNameFieldId)
                        || msg.contains("not on the appropriate screen")
                        || msg.contains("cannot be set"))) {
                log.warn("[Jira] Epic Name 필드({}) 설정 거부됨 — 필드 제외 후 재시도. 원인: {}", epicNameFieldId, msg);
                fields.remove(epicNameFieldId);
                EPIC_NAME_FIELD_CACHE.put(cfg.getJiraBaseUrl(), ""); // 캐시 무효화(스킵 상태로)
                created = createIssue(rt, cfg, fields);
            } else {
                throw ex;
            }
        }
        String epicKey = (String) created.get("key");
        log.info("[SmartWay] Epic 생성 완료: {} → {}", epicName, epicKey);
        return epicKey;
    }

    /**
     * Epic Link 필드 거부 시 반대 모드(parent ↔ customfield_XXXX)로 1회 재시도.
     * 여전히 실패하면 레이블 폴백(epic-<레포명>)으로 최후 재시도.
     */
    @SuppressWarnings("unchecked")
    private String retryOnEpicLinkError(RestTemplate rt, JiraConfig cfg, String issueKey,
                                         Map<String, Object> fields, RuntimeException origEx, boolean isCreate) {
        String msg = origEx.getMessage() == null ? "" : origEx.getMessage();
        boolean epicLinkIssue = msg.contains("parent")
                || msg.contains("Epic Link")
                || msg.contains("customfield_10014")
                || msg.contains("not on the appropriate screen")
                || msg.contains("cannot be set");
        if (!epicLinkIssue) {
            throw origEx;
        }

        String currentField = fields.containsKey("parent") ? "parent"
                : fields.keySet().stream().filter(k -> k.startsWith("customfield_")).findFirst().orElse(null);
        Object currentVal = currentField != null ? fields.get(currentField) : null;
        String epicKey = null;
        if (currentVal instanceof Map<?, ?> m && m.get("key") instanceof String s) epicKey = s;
        else if (currentVal instanceof String s) epicKey = s;

        log.warn("[SmartWay] Epic Link 필드({}) 거부 → 반대 모드 재시도. 원인: {}", currentField, msg);
        if (currentField != null) fields.remove(currentField);

        // 반대 모드 시도
        if ("parent".equals(currentField)) {
            // parent 실패 → customfield(Epic Link) 시도
            EPIC_LINK_FIELD_CACHE.remove(cfg.getJiraBaseUrl());
            String alt = resolveEpicLinkFieldId(rt, cfg);
            if (alt != null && !alt.isEmpty() && !"parent".equals(alt) && epicKey != null) {
                fields.put(alt, epicKey);
            }
        } else if (currentField != null && epicKey != null) {
            // customfield 실패 → parent 시도
            fields.put("parent", Map.of("key", epicKey));
            EPIC_LINK_FIELD_CACHE.put(cfg.getJiraBaseUrl(), "parent");
        }

        try {
            if (isCreate) {
                Map<String, Object> created = createIssue(rt, cfg, fields);
                return (String) created.get("key");
            } else {
                updateIssue(rt, cfg, issueKey, fields);
                return issueKey;
            }
        } catch (RuntimeException ex2) {
            log.warn("[SmartWay] Epic Link 재시도 실패 → 레이블 폴백. 원인: {}", ex2.getMessage());
            fields.remove("parent");
            fields.keySet().removeIf(k -> k.startsWith("customfield_") && !k.equals("customfield_10011"));
            List<String> labels = new ArrayList<>();
            Object existingLabels = fields.get("labels");
            if (existingLabels instanceof List<?> l) {
                for (Object o : l) if (o instanceof String s) labels.add(s);
            }
            Object repoObj = fields.getOrDefault("project", Map.of());
            // repositoryName 은 labels[0] 으로 이미 들어있음
            if (!labels.isEmpty() && !labels.contains("epic-" + labels.get(0))) {
                labels.add("epic-" + labels.get(0));
            }
            fields.put("labels", labels);
            EPIC_LINK_FIELD_CACHE.put(cfg.getJiraBaseUrl(), "");
            if (isCreate) {
                Map<String, Object> created = createIssue(rt, cfg, fields);
                return (String) created.get("key");
            } else {
                updateIssue(rt, cfg, issueKey, fields);
                return issueKey;
            }
        }
    }

    /**
     * 레포 단위 Epic 확보.
     * 1) repoCfg.jiraEpicKey 가 유효하면 재사용 (JQL 스킵)
     * 2) 아니면 정확 구문 JQL 검색으로 탐색
     * 3) 없으면 신규 생성
     * 결과 키는 repoCfg.jiraEpicKey 에 저장 후 커밋.
     */
    @SuppressWarnings("unchecked")
    private String getOrCreateEpicForRepo(RestTemplate rt, JiraConfig cfg, RepoConfig repoCfg, String epicName) {
        // 1) DB 캐시된 epicKey 검증
        if (repoCfg != null) {
            String cachedKey = repoCfg.getJiraEpicKey();
            if (cachedKey != null && !cachedKey.isBlank()) {
                String getUrl = cfg.getJiraBaseUrl() + "/rest/api/2/issue/" + cachedKey + "?fields=summary,issuetype";
                try {
                    Map<String, Object> issue = rt.getForObject(getUrl, Map.class);
                    if (issue != null && issue.get("key") != null) {
                        log.info("[SmartWay] Epic DB 재사용: repo={} → {}", repoCfg.getRepoName(), cachedKey);
                        return cachedKey;
                    }
                } catch (Exception e) {
                    log.warn("[SmartWay] Epic DB 캐시 무효({}): {} → 재탐색", cachedKey, e.getMessage());
                }
            }
        }

        // 2) 정확 구문 JQL 검색
        String jql = "project = " + cfg.getProjectKey()
                + " AND issuetype = Epic AND summary ~ \"\\\"" + epicName.replace("\"", "\\\"") + "\\\"\"";
        log.info("[SmartWay] Epic 검색(레포단위): epicName={}", epicName);
        List<Map<String, Object>> results = searchByJql(rt, cfg, jql, 1);
        String epicKey;
        if (!results.isEmpty()) {
            epicKey = (String) results.get(0).get("key");
            log.info("[SmartWay] Epic 기존 사용(검색): {} → {}", epicName, epicKey);
        } else {
            // 3) 신규 생성
            epicKey = getOrCreateEpic(rt, cfg, epicName);
        }

        // DB 저장 (커밋은 호출부 @Transactional 에 의해 수행)
        if (repoCfg != null) {
            repoCfg.setJiraEpicKey(epicKey);
            repoConfigRepo.save(repoCfg);
            log.info("[SmartWay] repo_config.jira_epic_key 저장: repo={}, epicKey={}",
                    repoCfg.getRepoName(), epicKey);
        }
        return epicKey;
    }

    /**
     * Component 검색 또는 생성
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateComponent(RestTemplate rt, JiraConfig cfg, String name, String desc) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/project/" + cfg.getProjectKey() + "/components";
        log.info("[SmartWay] Component 검색: name={} | url={}", name, url);
        try {
            List<Map<String, Object>> components = rt.getForObject(url, List.class);
            if (components != null) {
                log.info("[SmartWay] Component 목록: {}개", components.size());
                for (Map<String, Object> comp : components) {
                    if (name.equals(comp.get("name"))) {
                        log.info("[SmartWay] Component 기존 사용: {} (id={})", name, comp.get("id"));
                        return comp;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SmartWay] 컴포넌트 목록 조회 실패: {} | url={}", e.getMessage(), url);
        }

        String createUrl = cfg.getJiraBaseUrl() + "/rest/api/2/component";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", cfg.getProjectKey());
        body.put("name", name);
        if (desc != null) body.put("description", desc);
        log.info("[SmartWay] Component 신규 생성: name={} | url={}", name, createUrl);
        try {
            Map<String, Object> created = rt.postForObject(createUrl, body, Map.class);
            log.info("[SmartWay] 컴포넌트 생성 완료: {} (id={})", name, created != null ? created.get("id") : "?");
            return created != null ? created : Map.of();
        } catch (Exception e) {
            log.warn("[SmartWay] 컴포넌트 생성 실패 (이미 존재할 수 있음): {} | url={}", e.getMessage(), createUrl);
            return Map.of();
        }
    }

    // ========================================================================
    //  비즈니스 메서드: 정방향 (URLViewer → Jira)
    // ========================================================================

    /**
     * 건별 Jira 발행/업데이트
     */
    @Transactional
    public Map<String, Object> syncRecordToJira(Long recordId) {
        log.info("[SmartWay] syncRecordToJira 시작: recordId={}", recordId);

        JiraConfig cfg = getConfig();
        log.info("[SmartWay] 설정 로드: baseUrl={}, project={}", cfg.getJiraBaseUrl(), cfg.getProjectKey());

        RestTemplate rt = buildRestTemplate(cfg);
        ApiRecord record = recordRepo.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("레코드 없음: id=" + recordId));

        log.info("[SmartWay] 레코드: id={}, repo={}, path={}, status={}, jiraKey={}",
                record.getId(), record.getRepositoryName(), record.getApiPath(),
                record.getStatus(), record.getJiraIssueKey());

        RepoConfig repoCfg = repoConfigRepo.findByRepoName(record.getRepositoryName()).orElse(null);

        String businessName = repoCfg != null && repoCfg.getBusinessName() != null
                ? repoCfg.getBusinessName() : record.getRepositoryName();
        String appType = repoCfg != null && repoCfg.getAppType() != null ? repoCfg.getAppType() : "APP";
        String appTypeLabel = "APP".equals(appType) ? "앱" : "홈페이지";

        log.info("[SmartWay] 메타: businessName={}, appType={}", businessName, appType);

        // 1. Epic 확보 (레포당 1개)
        String epicName = "[" + businessName + "][" + record.getRepositoryName() + "] URL현황";
        log.info("[SmartWay] Step1. Epic 확보(레포단위): {}", epicName);
        String epicKey = getOrCreateEpicForRepo(rt, cfg, repoCfg, epicName);
        log.info("[SmartWay] Step1. Epic 완료: {}", epicKey);

        // 2. Component 확보
        String componentName = record.getRepositoryName() + " (" + appTypeLabel + ")";
        log.info("[SmartWay] Step2. Component 확보: {}", componentName);
        Map<String, Object> component = getOrCreateComponent(rt, cfg, componentName,
                businessName + " - " + appTypeLabel);
        log.info("[SmartWay] Step2. Component 완료: id={}", component.get("id"));

        // 3. 담당자 매핑
        String assignee = resolveAssignee(record, repoCfg);
        log.info("[SmartWay] Step3. 담당자 매핑: manager={}, team={} → jiraAccountId={}",
                resolveManagerName(record, repoCfg),
                resolveTeamName(record, repoCfg),
                assignee);

        // 4. Story 필드 구성
        Map<String, Object> fields = buildStoryFields(rt, cfg, record, repoCfg, businessName,
                epicKey, component, assignee);
        log.info("[SmartWay] Step4. Story 필드: summary={}, priority={}",
                fields.get("summary"), ((Map<?, ?>) fields.getOrDefault("priority", Map.of())).get("name"));

        // 5. 멱등성: jiraIssueKey가 있으면 UPDATE, 없으면 CREATE
        String issueKey = record.getJiraIssueKey();
        boolean wasNew = (issueKey == null || issueKey.isBlank());
        log.info("[SmartWay] Step5. 발행 방식: {} (기존 issueKey={})", wasNew ? "CREATE" : "UPDATE", issueKey);

        if (!wasNew) {
            try {
                updateIssue(rt, cfg, issueKey, fields);
            } catch (RuntimeException ex) {
                issueKey = retryOnEpicLinkError(rt, cfg, issueKey, fields, ex, false);
            }
        } else {
            try {
                Map<String, Object> result = createIssue(rt, cfg, fields);
                issueKey = (String) result.get("key");
            } catch (RuntimeException ex) {
                issueKey = retryOnEpicLinkError(rt, cfg, null, fields, ex, true);
            }
        }

        // 6. 차단근거 티켓 이슈 연결 (CREATE 시에만 수행, UPDATE는 중복 방지)
        if (wasNew) {
            log.info("[SmartWay] Step6. 차단근거 이슈 연결 시작: issueKey={}, blockedReason={}",
                    issueKey, record.getBlockedReason());
            linkBlockedReasonTickets(rt, cfg, issueKey, record.getBlockedReason());
        } else {
            log.debug("[SmartWay] Step6. 이슈 연결 스킵 (UPDATE 모드): issueKey={}", issueKey);
        }

        // 7. ApiRecord 업데이트
        record.setJiraIssueKey(issueKey);
        record.setJiraIssueUrl(cfg.getJiraBaseUrl().replaceAll("/+$", "") + "/browse/" + issueKey);
        record.setJiraEpicKey(epicKey);
        record.setJiraSyncedAt(LocalDateTime.now());
        if (record.getReviewStage() == null || record.getReviewStage().isBlank()) {
            record.setReviewStage("JIRA_ISSUED");
        }
        recordRepo.save(record);

        String action = wasNew ? "created" : "updated";
        log.info("[SmartWay] Step7. DB 갱신 완료: recordId={}, issueKey={}, action={}, reviewStage={}",
                recordId, issueKey, action, record.getReviewStage());
        return Map.of("issueKey", issueKey, "action", action);
    }

    /**
     * 레포별 일괄 발행
     */
    @Transactional
    public Map<String, Object> syncRepoToJira(String repositoryName) {
        List<ApiRecord> targets = recordRepo.findByRepositoryName(repositoryName).stream()
                .filter(this::isBlockCandidate)
                .toList();
        log.info("[SmartWay] syncRepoToJira: repo={}, 대상={}건", repositoryName, targets.size());

        // 루프 진입 전 Epic 선확보 (레포당 1개만 생성되도록 보장)
        if (!targets.isEmpty()) {
            try {
                JiraConfig cfg = getConfig();
                RestTemplate rt = buildRestTemplate(cfg);
                RepoConfig repoCfg = repoConfigRepo.findByRepoName(repositoryName).orElse(null);
                if (repoCfg != null) {
                    String businessName = repoCfg.getBusinessName() != null ? repoCfg.getBusinessName() : repositoryName;
                    String epicName = "[" + businessName + "][" + repositoryName + "] URL현황";
                    getOrCreateEpicForRepo(rt, cfg, repoCfg, epicName);
                }
            } catch (Exception e) {
                log.warn("[SmartWay] Epic 선확보 실패(계속 진행): repo={}, error={}", repositoryName, e.getMessage());
            }
        }

        int created = 0, updated = 0, failed = 0;
        for (ApiRecord r : targets) {
            try {
                Map<String, Object> result = syncRecordToJira(r.getId());
                if ("created".equals(result.get("action"))) created++;
                else updated++;
            } catch (Exception e) {
                log.warn("[SmartWay] {} 동기화 실패: {}", r.getApiPath(), e.getMessage());
                failed++;
            }
        }
        log.info("[SmartWay] 레포 {} 동기화: 대상={}, 생성={}, 갱신={}, 실패={}",
                repositoryName, targets.size(), created, updated, failed);
        return Map.of("total", targets.size(), "created", created, "updated", updated, "failed", failed);
    }

    /**
     * 전체 일괄 발행
     */
    @Transactional
    public Map<String, Object> syncAllToJira() {
        List<String> repos = recordRepo.findAllRepositoryNames();
        log.info("[SmartWay] syncAllToJira: 전체 레포 {}개", repos.size());
        int totalCreated = 0, totalUpdated = 0, totalFailed = 0;
        for (String repo : repos) {
            Map<String, Object> result = syncRepoToJira(repo);
            totalCreated += (int) result.get("created");
            totalUpdated += (int) result.get("updated");
            totalFailed += (int) result.get("failed");
        }
        log.info("[SmartWay] 전체 동기화 완료: 생성={}, 갱신={}, 실패={}",
                totalCreated, totalUpdated, totalFailed);
        return Map.of("created", totalCreated, "updated", totalUpdated, "failed", totalFailed);
    }

    // ========================================================================
    //  비즈니스 메서드: 역방향 (Jira → URLViewer)
    // ========================================================================

    /**
     * 건별 역방향 동기화
     */
    @Transactional
    public Map<String, Object> syncRecordFromJira(Long recordId) {
        JiraConfig cfg = getConfig();
        RestTemplate rt = buildRestTemplate(cfg);
        ApiRecord record = recordRepo.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("레코드 없음: id=" + recordId));

        log.info("[SmartWay] syncRecordFromJira: recordId={}, issueKey={}", recordId, record.getJiraIssueKey());

        if (record.getJiraIssueKey() == null || record.getJiraIssueKey().isBlank()) {
            log.info("[SmartWay] 역방향 스킵 — jiraIssueKey 없음: recordId={}", recordId);
            return Map.of("status", "skipped", "reason", "no jira issue key");
        }

        Map<String, Object> issue = getIssue(rt, cfg, record.getJiraIssueKey());
        if (issue.isEmpty()) {
            log.info("[SmartWay] 역방향 스킵 — Jira 이슈 미존재: {}", record.getJiraIssueKey());
            return Map.of("status", "skipped", "reason", "issue not found in Jira");
        }
        String beforeStage = record.getReviewStage();
        applyJiraStatusToRecord(record, issue, cfg);
        log.info("[SmartWay] 역방향 반영: issueKey={}, reviewStage {} → {}",
                record.getJiraIssueKey(), beforeStage, record.getReviewStage());
        record.setJiraSyncedAt(LocalDateTime.now());
        recordRepo.save(record);

        return Map.of("status", "synced", "issueKey", record.getJiraIssueKey());
    }

    /**
     * 레포별 역방향 동기화
     */
    @Transactional
    public Map<String, Object> syncRepoFromJira(String repositoryName) {
        List<ApiRecord> targets = recordRepo.findByRepositoryName(repositoryName).stream()
                .filter(r -> r.getJiraIssueKey() != null && !r.getJiraIssueKey().isBlank())
                .toList();
        log.info("[SmartWay] syncRepoFromJira: repo={}, 대상={}건", repositoryName, targets.size());

        int synced = 0, failed = 0;
        JiraConfig cfg = getConfig();
        RestTemplate rt = buildRestTemplate(cfg);
        for (ApiRecord r : targets) {
            try {
                Map<String, Object> issue = getIssue(rt, cfg, r.getJiraIssueKey());
                if (!issue.isEmpty()) {
                    String before = r.getReviewStage();
                    applyJiraStatusToRecord(r, issue, cfg);
                    log.info("[SmartWay] {} 역방향: stage {} → {}", r.getJiraIssueKey(), before, r.getReviewStage());
                    r.setJiraSyncedAt(LocalDateTime.now());
                    recordRepo.save(r);
                    synced++;
                } else {
                    log.info("[SmartWay] {} 이슈 미존재 — 스킵", r.getJiraIssueKey());
                }
            } catch (Exception e) {
                log.warn("[SmartWay] {} 역방향 동기화 실패: {}", r.getJiraIssueKey(), e.getMessage());
                failed++;
            }
        }
        return Map.of("total", targets.size(), "synced", synced, "failed", failed);
    }

    /**
     * 전체 역방향 동기화 — JQL로 최근 변경분만 조회
     */
    @Transactional
    public Map<String, Object> syncAllFromJira() {
        JiraConfig cfg = getConfig();
        RestTemplate rt = buildRestTemplate(cfg);

        String jql = "project = " + cfg.getProjectKey();
        if (cfg.getLastSyncedAt() != null) {
            String since = cfg.getLastSyncedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            jql += " AND updated >= \"" + since + "\"";
        }
        log.info("[SmartWay] syncAllFromJira: jql={}", jql);

        List<Map<String, Object>> issues = searchByJql(rt, cfg, jql, 500);
        log.info("[SmartWay] 역방향 대상 이슈: {}건", issues.size());

        int synced = 0, notFound = 0, failed = 0;
        for (Map<String, Object> issue : issues) {
            String issueKey = (String) issue.get("key");
            try {
                ApiRecord record = recordRepo.findByJiraIssueKey(issueKey).orElse(null);
                if (record == null) {
                    log.info("[SmartWay] {} — DB에 매핑 레코드 없음 (스킵)", issueKey);
                    notFound++;
                    continue;
                }
                String before = record.getReviewStage();
                applyJiraStatusToRecord(record, issue, cfg);
                log.info("[SmartWay] {} 역방향: stage {} → {}", issueKey, before, record.getReviewStage());
                record.setJiraSyncedAt(LocalDateTime.now());
                recordRepo.save(record);
                synced++;
            } catch (Exception e) {
                log.warn("[SmartWay] {} 역방향 동기화 실패: {}", issueKey, e.getMessage());
                failed++;
            }
        }

        cfg.setLastSyncedAt(LocalDateTime.now());
        jiraConfigRepo.save(cfg);

        log.info("[SmartWay] 전체 역방향 동기화: 총={}, 동기화={}, 미발견={}, 실패={}",
                issues.size(), synced, notFound, failed);
        return Map.of("total", issues.size(), "synced", synced, "notFound", notFound, "failed", failed);
    }

    // ========================================================================
    //  연결 테스트
    // ========================================================================

    /**
     * Jira 연결 테스트 — GET /rest/api/2/myself
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> testConnection() {
        JiraConfig cfg = getConfig();
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/myself";
        log.info("[SmartWay] 연결 테스트 → url={}", url);
        RestTemplate rt = buildRestTemplate(cfg);
        try {
            Map<String, Object> me = rt.getForObject(url, Map.class);
            if (me == null) {
                log.warn("[SmartWay] 연결 테스트 — 응답 비어있음");
                return Map.of("success", false, "error", "응답이 비어 있습니다.");
            }
            log.info("[SmartWay] 연결 테스트 성공: displayName={}, email={}",
                    me.get("displayName"), me.get("emailAddress"));
            return Map.of("success", true,
                    "user", me.getOrDefault("displayName", ""),
                    "email", me.getOrDefault("emailAddress", ""));
        } catch (Exception e) {
            log.error("[SmartWay] 연결 테스트 실패: {} | url={}", e.getMessage(), url);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 대표적인 오류 한글 힌트 부여
            if (msg.contains("PKIX") || msg.contains("SunCertPathBuilderException")) {
                msg = "SSL 인증서 검증 실패 (내부 CA 미신뢰). 서버 재기동 후 재시도해 주세요. 원인: " + msg;
            } else if (msg.contains("401") || msg.toLowerCase().contains("unauthorized")) {
                msg = "인증 실패 (401) — Bearer 토큰을 확인해 주세요.";
            } else if (msg.contains("404")) {
                msg = "경로 미존재 (404) — SmartWay URL 을 확인해 주세요.";
            } else if (msg.contains("ConnectException") || msg.contains("connect timed out")) {
                msg = "서버 접속 실패 — 네트워크/URL 을 확인해 주세요. 원인: " + msg;
            }
            return Map.of("success", false, "error", msg);
        }
    }

    // ========================================================================
    //  헬퍼 메서드
    // ========================================================================

    private boolean isBlockCandidate(ApiRecord r) {
        String s = r.getStatus();
        if (s == null) return false;
        // (1)-(*) 차단대상 leaf, (2)-(*) 추가검토대상 leaf 모두 후보
        return s.startsWith("①-") || s.startsWith("②-");
    }

    /**
     * 담당자 이름 해석: managerOverride → repoCfg.managerMappings(programId 매칭) → repoCfg.managerName
     * viewer.html의 resolveManager 로직과 동일하게 유지한다.
     */
    private String resolveManagerName(ApiRecord record, RepoConfig repoCfg) {
        if (record.getManagerOverride() != null && !record.getManagerOverride().isBlank()) {
            log.debug("[Jira] resolveManagerName: 선택경로=override, manager={}", record.getManagerOverride());
            return record.getManagerOverride();
        }
        if (repoCfg != null) {
            String mappingsJson = repoCfg.getManagerMappings();
            if (mappingsJson != null && !mappingsJson.isBlank() && record.getApiPath() != null) {
                try {
                    List<Map<String, Object>> mappings = MAPPER.readValue(mappingsJson,
                            MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
                    String apiPathUpper = record.getApiPath().toUpperCase();
                    for (Map<String, Object> m : mappings) {
                        String programId = (String) m.get("programId");
                        String managerName = (String) m.get("managerName");
                        if (programId != null && !programId.isBlank()
                                && managerName != null && !managerName.isBlank()
                                && apiPathUpper.contains(programId.toUpperCase())) {
                            log.debug("[Jira] resolveManagerName: 선택경로=programId-mapping, programId={}, manager={}",
                                    programId, managerName);
                            return managerName;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[Jira] resolveManagerName: managerMappings 파싱 실패, 폴백 진행: {}", e.getMessage());
                }
            }
            if (repoCfg.getManagerName() != null && !repoCfg.getManagerName().isBlank()) {
                log.debug("[Jira] resolveManagerName: 선택경로=default, manager={}", repoCfg.getManagerName());
                return repoCfg.getManagerName();
            }
        }
        log.debug("[Jira] resolveManagerName: 담당자 없음");
        return null;
    }

    /**
     * 팀 이름 해석: teamOverride → repoCfg.teamName
     */
    private String resolveTeamName(ApiRecord record, RepoConfig repoCfg) {
        if (record.getTeamOverride() != null && !record.getTeamOverride().isBlank()) {
            return record.getTeamOverride();
        }
        return (repoCfg != null) ? repoCfg.getTeamName() : null;
    }

    /**
     * 담당자 매핑: resolveManagerName → resolveTeamName → JiraUserMapping 변환
     */
    private String resolveAssignee(ApiRecord record, RepoConfig repoCfg) {
        String manager = resolveManagerName(record, repoCfg);
        if (manager == null || manager.isBlank()) {
            log.debug("[Jira] resolveAssignee: 담당자 없음 → assignee=null");
            return null;
        }

        String team = resolveTeamName(record, repoCfg);

        if (team != null) {
            Optional<JiraUserMapping> byTeam =
                    userMappingRepo.findByTeamNameAndUrlviewerName(team, manager);
            if (byTeam.isPresent()) {
                log.debug("[Jira] resolveAssignee: 팀+이름 매핑 성공: team={}, name={} → {}",
                        team, manager, byTeam.get().getJiraAccountId());
                return byTeam.get().getJiraAccountId();
            }
            log.debug("[Jira] resolveAssignee: 팀+이름 매핑 미존재 — 이름만으로 폴백: team={}, name={}", team, manager);
        }

        Optional<JiraUserMapping> byName = userMappingRepo.findFirstByUrlviewerName(manager);
        if (byName.isPresent()) {
            log.debug("[Jira] resolveAssignee: 이름 단독 매핑: {} → {}",
                    manager, byName.get().getJiraAccountId());
        } else {
            log.debug("[Jira] resolveAssignee: 매핑 없음: name={} → assignee=null", manager);
        }
        return byName.map(JiraUserMapping::getJiraAccountId).orElse(null);
    }

    /**
     * Story 필드 구성
     */
    private Map<String, Object> buildStoryFields(RestTemplate rt, JiraConfig cfg, ApiRecord record, RepoConfig repoCfg,
                                                  String businessName, String epicKey,
                                                  Map<String, Object> component, String assigneeAccountId) {
        String summary = "[" + businessName + "][" + record.getRepositoryName() + "] " + record.getApiPath();
        if (summary.length() > 255) {
            summary = summary.substring(0, 252) + "...";
        }

        String descText = buildDescriptionTables(cfg, repoCfg, record, businessName);

        // ①-②/①-③ 호출0건 → Highest, ①-④ 업무종료/①-⑤ 현업제외 → Medium, (2)-(*) 추가검토 → Low
        String s = record.getStatus() != null ? record.getStatus() : "";
        String priority;
        if (s.startsWith("①-②") || s.startsWith("①-③") || s.startsWith("①-①")) priority = "Highest";
        else if (s.startsWith("①-")) priority = "Medium";
        else priority = "Low";

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", cfg.getProjectKey()));
        fields.put("issuetype", Map.of("name", "Story"));
        fields.put("summary", summary);
        fields.put("description", descText);
        fields.put("priority", Map.of("name", priority));

        // Epic Link 필드 주입 (핵심): parent(신형) 또는 customfield_XXXX(구형).
        // 미해석 시 레이블 폴백 (epic-<레포명>) 으로 최소한의 그룹화 보장.
        List<String> labels = new ArrayList<>();
        labels.add(record.getRepositoryName());
        if (epicKey != null && !epicKey.isBlank()) {
            String epicLinkFieldId = resolveEpicLinkFieldId(rt, cfg);
            if ("parent".equals(epicLinkFieldId)) {
                fields.put("parent", Map.of("key", epicKey));
            } else if (epicLinkFieldId != null && !epicLinkFieldId.isEmpty()) {
                fields.put(epicLinkFieldId, epicKey);
            } else {
                labels.add("epic-" + record.getRepositoryName());
                log.warn("[SmartWay] Epic Link 필드 미해석 → 레이블 폴백: epic-{}", record.getRepositoryName());
            }
        }
        fields.put("labels", labels);

        if (component != null && component.get("id") != null) {
            fields.put("components", List.of(Map.of("id", String.valueOf(component.get("id")))));
        }
        if (assigneeAccountId != null) {
            fields.put("assignee", Map.of("name", assigneeAccountId));
        }

        log.debug("[Jira] buildStoryFields: summary='{}', priority={}, epicKey={}, assignee={}",
                summary, priority, epicKey, assigneeAccountId);

        return fields;
    }

    /**
     * SmartWay(Jira) 이슈 description 을 4개 표로 구성한다.
     * - Jira wiki markup 사용: h3. 제목, ||헤더||, |셀|, {color:#xxx}text{color}
     * - 타이틀: 파란색 + bold + ■ 접두사
     * - 상태값 셀은 viewer.html 배지 색상과 동일
     * - URL상태정보는 4-열(항목|값|항목|값) 표로 상태+차단 통합
     *
     * 표 구성:
     *   1) ■ URL기본정보   — 업무명, 레포지토리, URL경로, Full URL, 관련메뉴
     *   2) ■ URL상태정보   — 상태/차단기준, 1년호출건/차단일자, 1달호출건/차단근거, -/차단비고 (4-열)
     *   3) ■ URL기타정보   — Controller, 메소드, ApiOperation, Description주석, 메소드주석, 컨트롤러주석, HTTP Method, Deprecated
     *   4) ■ URL관련 소스변경이력(최근 5건) — gitHistory(JSON)
     */
    String buildDescriptionTables(JiraConfig cfg, RepoConfig repoCfg, ApiRecord record, String businessName) {
        log.debug("[Jira] buildDescriptionTables 시작: recordId={}, repo={}, apiPath={}",
                record.getId(), record.getRepositoryName(), record.getApiPath());

        StringBuilder sb = new StringBuilder();

        // 1) URL기본정보
        appendTitle(sb, "URL기본정보");
        sb.append("||항목||값||\n");
        row(sb, "업무명",     businessName);
        row(sb, "레포지토리", record.getRepositoryName());
        row(sb, "팀",         safe(resolveTeamName(record, repoCfg)));
        row(sb, "담당자",     safe(resolveManagerName(record, repoCfg)));
        row(sb, "URL 경로",   record.getApiPath());
        row(sb, "Full URL",   record.getFullUrl());
        row(sb, "관련메뉴",   safe(record.getDescriptionOverride()));
        sb.append("\n");

        // 2) URL상태정보 (4-열: 상태 + 차단 통합)
        appendTitle(sb, "URL상태정보");
        sb.append("||항목||값||항목||값||\n");
        row4(sb,    "상태",        colorizeStatus(record.getStatus()),
                    "차단기준",    safe(record.getBlockCriteria()));
        row4(sb,    "1년 호출건",  nn(record.getCallCount()) + "건",
                    "차단일자",    record.getBlockedDate() != null ? record.getBlockedDate().toString() : "-");
        row4Raw(sb, "1달 호출건",  nn(record.getCallCountMonth()) + "건",
                    "차단근거",    linkifyBlockedReason(cfg, record.getBlockedReason()));
        row4(sb,    "-",           "-",
                    "차단비고",    safe(record.getMemo()));
        sb.append("\n");

        // 3) URL기타정보 (Controller·메소드 최상단, HTTP Method·Deprecated 기존 표에서 이동)
        appendTitle(sb, "URL기타정보");
        sb.append("||항목||값||\n");
        row(sb, "Controller",      safe(record.getControllerName()));
        row(sb, "메소드",           safe(record.getMethodName()));
        row(sb, "ApiOperation",     safe(record.getApiOperationValue()));
        row(sb, "Description 주석", safe(record.getDescriptionTag()));
        row(sb, "메소드 주석",       safe(record.getFullComment()));
        row(sb, "컨트롤러 주석",     safe(record.getControllerComment()));
        row(sb, "HTTP Method",       safe(record.getHttpMethod()));
        row(sb, "Deprecated",        "Y".equals(record.getIsDeprecated()) ? "Y" : "N");
        sb.append("\n");

        // 4) URL관련 소스변경이력
        appendTitle(sb, "URL관련 소스변경이력 (최근 5건)");
        sb.append("||#||날짜||변경자||내용||\n");
        List<String[]> commits = parseGitHistory(record.getGitHistory());
        if (commits.isEmpty()) {
            sb.append("|-|-|-|-|\n");
        } else {
            int idx = 1;
            for (String[] c : commits) {
                sb.append("|").append(idx++).append("|")
                  .append(cell(c[0])).append("|")
                  .append(cell(c[1])).append("|")
                  .append(cell(c[2])).append("|\n");
            }
        }

        log.debug("[Jira] buildDescriptionTables 완료: length={}, commits={}",
                sb.length(), commits.size());
        return sb.toString();
    }

    /** 표 타이틀: 파란색 + bold + ■ 접두사 (Jira wiki markup) */
    private void appendTitle(StringBuilder sb, String title) {
        sb.append("h3. {color:#1e40af}*■ ").append(title).append("*{color}\n");
    }

    /** 일반 2열 행 (값을 cell() 로 정제) */
    private void row(StringBuilder sb, String k, String v) {
        sb.append("|").append(k).append("|").append(cell(v)).append("|\n");
    }

    /** 값이 이미 안전하게 정제된 상태(예: linkifyBlockedReason) 일 때 사용 */
    private void rowRaw(StringBuilder sb, String k, String v) {
        sb.append("|").append(k).append("|").append(v == null || v.isBlank() ? "-" : v).append("|\n");
    }

    /** 4-열 행: v1·v2 모두 cell() 정제 */
    private void row4(StringBuilder sb, String k1, String v1, String k2, String v2) {
        sb.append("|").append(k1).append("|").append(cell(v1))
          .append("|").append(k2).append("|").append(cell(v2)).append("|\n");
    }

    /** 4-열 행: v1은 cell() 정제, rawV2는 이미 정제된 값(linkifyBlockedReason 결과 등) */
    private void row4Raw(StringBuilder sb, String k1, String v1, String k2, String rawV2) {
        sb.append("|").append(k1).append("|").append(cell(v1))
          .append("|").append(k2).append("|")
          .append(rawV2 == null || rawV2.isBlank() ? "-" : rawV2).append("|\n");
    }

    /** 셀 값 정제: null/빈값 → '-', Jira wiki 예약문자 중 표 구분자 '|' 를 전각으로 치환, 개행은 \\\\ 로 */
    private String cell(String v) {
        if (v == null || v.isBlank()) return "-";
        String s = v.replace("|", "｜");
        s = s.replace("\r\n", "\n").replace("\r", "\n").replace("\n", " \\\\ ");
        if (s.length() > 500) s = s.substring(0, 497) + "...";
        return s;
    }

    /** null/-/blank 를 '-' 로 치환 */
    private String safe(String v) {
        if (v == null || v.isBlank() || "-".equals(v.trim())) return "-";
        return v.trim();
    }

    /**
     * 상태값을 viewer.html 배지 색상으로 채색.
     * 사용/①-① 차단완료: 초록, ①-②/①-③ 호출0건: 빨강, ①-④/①-⑤ 업무종료/현업제외: 주황,
     * (2)-(*) 추가검토대상: 노랑
     */
    private String colorizeStatus(String status) {
        if (status == null || status.isBlank()) return "-";
        String color;
        if ("사용".equals(status) || status.startsWith("①-①")) color = "#166534";
        else if (status.startsWith("①-②") || status.startsWith("①-③")) color = "#991b1b";
        else if (status.startsWith("①-")) color = "#c2410c";
        else if (status.startsWith("②-")) color = "#92400e";
        else color = "#475569";
        return "{color:" + color + "}*" + status + "*{color}";
    }

    /**
     * 차단근거 내 [CSR-xxx]/[OP-xxx] 티켓 패턴을 SmartWay(Jira) 이슈 링크로 변환.
     * viewer.html 의 class="jira-badge" 가 가리키는 URL 과 동일한 {baseUrl}/browse/{KEY} 를 사용.
     */
    private String linkifyBlockedReason(JiraConfig cfg, String reason) {
        if (reason == null || reason.isBlank()) return "-";
        String baseUrl = cfg != null ? cfg.getJiraBaseUrl() : null;
        // 1) 줄바꿈/길이/파이프 정제를 먼저 수행 (단, 파이프 치환은 안전하게 하되 아래 링크 조립 후 별도 처리)
        String s = reason.replace("\r\n", "\n").replace("\r", "\n").replace("\n", " \\\\ ");
        if (s.length() > 500) s = s.substring(0, 497) + "...";
        // 기존 본문에 있던 '|' 는 표 구분자와 충돌하므로 전각으로 치환
        s = s.replace("|", "｜");
        if (baseUrl == null || baseUrl.isBlank()) return s;

        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // 2) 그 다음 이슈 키 패턴을 [KEY|URL] 링크로 치환 (이 '|' 는 wiki 링크 문법용)
        java.util.regex.Matcher m = TICKET_KEY_PATTERN.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String key = m.group(1) != null ? m.group(1) : m.group(2);
            String replacement = "[" + key + "|" + trimmed + "/browse/" + key + "]";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** gitHistory(JSON 배열) → [date, author, message] 최근 5건. 파싱 실패 시 빈 리스트. */
    @SuppressWarnings("unchecked")
    private List<String[]> parseGitHistory(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            if (!(parsed instanceof List<?> list)) return List.of();
            List<String[]> result = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> map)) continue;
                String date    = strOrBlank(map.get("date"));
                String author  = strOrBlank(map.get("author"));
                String message = strOrBlank(map.get("message"));
                result.add(new String[]{ date, author, message });
                if (result.size() >= 5) break;
            }
            return result;
        } catch (Exception e) {
            log.debug("[Jira] parseGitHistory 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private String strOrBlank(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * Jira 이슈 상태를 ApiRecord의 reviewStage/reviewResult에 반영
     */
    @SuppressWarnings("unchecked")
    private void applyJiraStatusToRecord(ApiRecord record, Map<String, Object> issue, JiraConfig cfg) {
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        if (fields == null) {
            log.debug("[Jira] applyJiraStatusToRecord: fields 없음 — 스킵");
            return;
        }

        Map<String, Object> statusObj = (Map<String, Object>) fields.get("status");
        Map<String, Object> resolutionObj = (Map<String, Object>) fields.get("resolution");
        String resolution = resolutionObj != null ? (String) resolutionObj.get("name") : null;

        String category = "";
        if (statusObj != null) {
            Map<String, Object> statusCategory = (Map<String, Object>) statusObj.get("statusCategory");
            if (statusCategory != null) {
                category = (String) statusCategory.getOrDefault("key", "");
            }
        }

        log.debug("[Jira] applyJiraStatusToRecord: statusCategory={}, resolution={}, issueKey={}",
                category, resolution, issue.get("key"));

        if ("done".equals(category)) {
            if ("Blocked".equalsIgnoreCase(resolution) || "차단확정".equals(resolution)) {
                record.setReviewStage("JIRA_APPROVED");
                record.setReviewResult("차단확정");
            } else if ("Excluded".equalsIgnoreCase(resolution) || "차단대상 제외".equals(resolution)) {
                record.setReviewStage("JIRA_REJECTED");
                record.setReviewResult("차단대상 제외");
            } else {
                record.setReviewStage("JIRA_APPROVED");
                record.setReviewResult("판단불가");
            }
        } else {
            record.setReviewStage("JIRA_ISSUED");
        }

        log.debug("[Jira] applyJiraStatusToRecord 결과: reviewStage={}, reviewResult={}",
                record.getReviewStage(), record.getReviewResult());
    }

    /** null-safe Long → String */
    private String nn(Long v) {
        return v != null ? String.valueOf(v) : "0";
    }

    /**
     * Jira 설정 조회 (없으면 예외)
     */
    private JiraConfig getConfig() {
        JiraConfig cfg = jiraConfigRepo.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Jira 설정이 없습니다. 설정 페이지에서 Jira 연동을 설정해 주세요."));
        log.debug("[Jira] getConfig: baseUrl={}, project={}, lastSyncedAt={}",
                cfg.getJiraBaseUrl(), cfg.getProjectKey(), cfg.getLastSyncedAt());
        return cfg;
    }
}
