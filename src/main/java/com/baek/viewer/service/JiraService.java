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

        // 1. Epic 확보
        String epicName = "[" + businessName + "] URL 차단 검토";
        log.info("[SmartWay] Step1. Epic 확보: {}", epicName);
        String epicKey = getOrCreateEpic(rt, cfg, epicName);
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
                record.getManagerOverride() != null ? record.getManagerOverride()
                        : (repoCfg != null ? repoCfg.getManagerName() : null),
                record.getTeamOverride() != null ? record.getTeamOverride()
                        : (repoCfg != null ? repoCfg.getTeamName() : null),
                assignee);

        // 4. Story 필드 구성
        Map<String, Object> fields = buildStoryFields(cfg, record, repoCfg, businessName,
                epicKey, component, assignee);
        log.info("[SmartWay] Step4. Story 필드: summary={}, priority={}",
                fields.get("summary"), ((Map<?, ?>) fields.getOrDefault("priority", Map.of())).get("name"));

        // 5. 멱등성: jiraIssueKey가 있으면 UPDATE, 없으면 CREATE
        String issueKey = record.getJiraIssueKey();
        boolean wasNew = (issueKey == null || issueKey.isBlank());
        log.info("[SmartWay] Step5. 발행 방식: {} (기존 issueKey={})", wasNew ? "CREATE" : "UPDATE", issueKey);

        if (!wasNew) {
            updateIssue(rt, cfg, issueKey, fields);
        } else {
            Map<String, Object> result = createIssue(rt, cfg, fields);
            issueKey = (String) result.get("key");
        }

        // 6. ApiRecord 업데이트
        record.setJiraIssueKey(issueKey);
        record.setJiraIssueUrl(cfg.getJiraBaseUrl() + "/browse/" + issueKey);
        record.setJiraEpicKey(epicKey);
        record.setJiraSyncedAt(LocalDateTime.now());
        if (record.getReviewStage() == null || record.getReviewStage().isBlank()) {
            record.setReviewStage("JIRA_ISSUED");
        }
        recordRepo.save(record);

        String action = wasNew ? "created" : "updated";
        log.info("[SmartWay] Step6. DB 갱신 완료: recordId={}, issueKey={}, action={}, reviewStage={}",
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
        return "최우선 차단대상".equals(s)
                || "후순위 차단대상".equals(s)
                || "추가검토필요 차단대상".equals(s)
                || "차단완료".equals(s);
    }

    /**
     * 담당자 매핑: managerOverride → managerMappings → managerName → JiraUserMapping 변환
     */
    private String resolveAssignee(ApiRecord record, RepoConfig repoCfg) {
        String manager = record.getManagerOverride();
        if (manager == null && repoCfg != null) {
            manager = repoCfg.getManagerName();
        }
        if (manager == null) {
            log.debug("[Jira] resolveAssignee: 담당자 없음 → assignee=null");
            return null;
        }

        String team = record.getTeamOverride();
        if (team == null && repoCfg != null) {
            team = repoCfg.getTeamName();
        }

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
    private Map<String, Object> buildStoryFields(JiraConfig cfg, ApiRecord record, RepoConfig repoCfg,
                                                  String businessName, String epicKey,
                                                  Map<String, Object> component, String assigneeAccountId) {
        String content = null;
        if (record.getApiOperationValue() != null && !record.getApiOperationValue().isBlank()
                && !"-".equals(record.getApiOperationValue())) {
            content = record.getApiOperationValue().trim();
        } else if (record.getDescriptionTag() != null && !record.getDescriptionTag().isBlank()
                && !"-".equals(record.getDescriptionTag())) {
            content = record.getDescriptionTag().trim();
        }
        String summary = "[" + businessName + "] " + record.getApiPath()
                + (content != null ? " — " + content : "");
        if (summary.length() > 255) {
            summary = summary.substring(0, 252) + "...";
        }

        String descText = buildDescriptionTables(cfg, record, businessName);

        String priority = switch (record.getStatus()) {
            case "최우선 차단대상" -> "Highest";
            case "후순위 차단대상" -> "Medium";
            default -> "Low";
        };

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", cfg.getProjectKey()));
        fields.put("issuetype", Map.of("name", "Story"));
        fields.put("summary", summary);
        fields.put("description", descText);
        fields.put("priority", Map.of("name", priority));
        fields.put("labels", List.of(record.getRepositoryName()));

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
     * SmartWay(Jira) 이슈 description 을 5개 표로 구성한다.
     * - Jira wiki markup 사용: h3. 제목, ||헤더||, |셀|, {color:#xxx}text{color}
     * - 타이틀: 파란색 + bold (h3. {color:#1e40af}*...*{color})
     * - 상태값 셀은 viewer.html 배지 색상과 동일
     * - 차단근거 셀: SmartWay 이슈 URL 링크
     *
     * 표 구성:
     *   1) 기본정보     — 업무명, 레포지토리, Controller, 메소드, URL경로, Full URL, HTTP Method
     *   2) 상세정보     — ApiOperation, Description주석, 메소드주석, 컨트롤러주석, 관련메뉴(override)
     *   3) 상태정보     — 상태(배지색), 상태확정, 1년/1달/1주 호출건, Deprecated
     *   4) 차단정보     — 차단기준, 차단일자, 차단근거(SmartWay 링크), 비고
     *   5) 소스변경이력 — gitHistory(JSON) → 최근 5건 (날짜/변경자/내용)
     */
    String buildDescriptionTables(JiraConfig cfg, ApiRecord record, String businessName) {
        log.debug("[Jira] buildDescriptionTables 시작: recordId={}, repo={}, apiPath={}",
                record.getId(), record.getRepositoryName(), record.getApiPath());

        StringBuilder sb = new StringBuilder();

        // 1) 기본정보
        appendTitle(sb, "기본정보");
        sb.append("||항목||값||\n");
        row(sb, "업무명",       businessName);
        row(sb, "레포지토리",   record.getRepositoryName());
        row(sb, "Controller",   record.getControllerName());
        row(sb, "메소드",       record.getMethodName());
        row(sb, "URL 경로",     record.getApiPath());
        row(sb, "Full URL",     record.getFullUrl());
        row(sb, "HTTP Method",  record.getHttpMethod());
        sb.append("\n");

        // 2) 상세정보
        appendTitle(sb, "상세정보");
        sb.append("||항목||값||\n");
        row(sb, "관련메뉴(사용자지정)", safe(record.getDescriptionOverride()));
        row(sb, "ApiOperation",          safe(record.getApiOperationValue()));
        row(sb, "Description 주석",      safe(record.getDescriptionTag()));
        row(sb, "메소드 주석",           safe(record.getFullComment()));
        row(sb, "컨트롤러 주석",         safe(record.getControllerComment()));
        sb.append("\n");

        // 3) 상태정보
        appendTitle(sb, "상태정보");
        sb.append("||항목||값||\n");
        row(sb, "상태",          colorizeStatus(record.getStatus()));
        row(sb, "상태확정",      record.isStatusOverridden() ? "확정" : "미확정");
        row(sb, "1년 호출건",    nn(record.getCallCount()) + "건");
        row(sb, "1달 호출건",    nn(record.getCallCountMonth()) + "건");
        row(sb, "1주 호출건",    nn(record.getCallCountWeek()) + "건");
        row(sb, "Deprecated",    "Y".equals(record.getIsDeprecated()) ? "Y" : "N");
        sb.append("\n");

        // 4) 차단정보
        appendTitle(sb, "차단정보");
        sb.append("||항목||값||\n");
        row(sb, "차단기준",  safe(record.getBlockCriteria()));
        row(sb, "차단일자",  record.getBlockedDate() != null ? record.getBlockedDate().toString() : "-");
        rowRaw(sb, "차단근거", linkifyBlockedReason(cfg, record.getBlockedReason()));
        row(sb, "비고",      safe(record.getMemo()));
        sb.append("\n");

        // 5) 소스변경이력
        appendTitle(sb, "소스변경이력 (최근 5건)");
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

    /** 표 타이틀: 파란색 + bold (Jira wiki markup) */
    private void appendTitle(StringBuilder sb, String title) {
        sb.append("h3. {color:#1e40af}*").append(title).append("*{color}\n");
    }

    /** 일반 2열 행 (값을 cell() 로 정제) */
    private void row(StringBuilder sb, String k, String v) {
        sb.append("|").append(k).append("|").append(cell(v)).append("|\n");
    }

    /** 값이 이미 안전하게 정제된 상태(예: linkifyBlockedReason) 일 때 사용 */
    private void rowRaw(StringBuilder sb, String k, String v) {
        sb.append("|").append(k).append("|").append(v == null || v.isBlank() ? "-" : v).append("|\n");
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
     * 사용/차단완료: 초록(#166534), 최우선: 빨강(#991b1b), 후순위: 주황(#c2410c),
     * 추가검토필요: 노랑(#92400e)
     */
    private String colorizeStatus(String status) {
        if (status == null || status.isBlank()) return "-";
        String color = switch (status) {
            case "사용", "차단완료" -> "#166534";
            case "최우선 차단대상"   -> "#991b1b";
            case "후순위 차단대상"   -> "#c2410c";
            case "추가검토필요 차단대상" -> "#92400e";
            default                   -> "#475569";
        };
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
        // 2) 그 다음 CSR-xxx / OP-xxx 패턴을 [KEY|URL] 링크로 치환 (이 '|' 는 wiki 링크 문법용)
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\[((?:CSR|OP)-\\d+)\\]|\\b((?:CSR|OP)-\\d+)\\b");
        java.util.regex.Matcher m = p.matcher(s);
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
