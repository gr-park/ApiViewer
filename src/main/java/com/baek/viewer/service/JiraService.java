package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.JiraConfig;
import com.baek.viewer.model.JiraUserMapping;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.JiraConfigRepository;
import com.baek.viewer.repository.JiraUserMappingRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class JiraService {

    private static final Logger log = LoggerFactory.getLogger(JiraService.class);

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
     * Bearer Token + JSON Content-Type 인터셉터 적용 (Jira Server REST API v2)
     */
    private RestTemplate buildRestTemplate(JiraConfig config) {
        RestTemplate rt = new RestTemplate();
        String token = config.getApiToken();

        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));
            return execution.execute(request, body);
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
        try {
            Map<String, Object> result = rt.postForObject(url, body, Map.class);
            log.info("[Jira] 이슈 생성 완료: {}", result != null ? result.get("key") : "null");
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.error("[Jira] 이슈 생성 실패: {}", e.getMessage());
            throw new RuntimeException("Jira 이슈 생성 실패: " + e.getMessage(), e);
        }
    }

    /**
     * PUT /rest/api/2/issue/{issueKey} — 이슈 업데이트
     */
    private void updateIssue(RestTemplate rt, JiraConfig cfg, String issueKey, Map<String, Object> fields) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/issue/" + issueKey;
        Map<String, Object> body = Map.of("fields", fields);
        try {
            rt.put(url, body);
            log.info("[Jira] 이슈 업데이트 완료: {}", issueKey);
        } catch (Exception e) {
            log.error("[Jira] 이슈 업데이트 실패 {}: {}", issueKey, e.getMessage());
            throw new RuntimeException("Jira 이슈 업데이트 실패: " + e.getMessage(), e);
        }
    }

    /**
     * GET /rest/api/2/issue/{issueKey} — 이슈 조회
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getIssue(RestTemplate rt, JiraConfig cfg, String issueKey) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/issue/" + issueKey;
        try {
            Map<String, Object> result = rt.getForObject(url, Map.class);
            return result != null ? result : Map.of();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("[Jira] 이슈 미존재: {}", issueKey);
                return Map.of();
            }
            throw new RuntimeException("Jira 이슈 조회 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Jira] 이슈 조회 실패 {}: {}", issueKey, e.getMessage());
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
        try {
            Map<String, Object> result = rt.postForObject(url, body, Map.class);
            if (result != null && result.get("issues") instanceof List) {
                return (List<Map<String, Object>>) result.get("issues");
            }
            return List.of();
        } catch (Exception e) {
            log.error("[Jira] JQL 검색 실패: jql={}, error={}", jql, e.getMessage());
            return List.of();
        }
    }

    /**
     * Epic 검색 또는 생성
     */
    @SuppressWarnings("unchecked")
    private String getOrCreateEpic(RestTemplate rt, JiraConfig cfg, String epicName) {
        // JQL로 기존 Epic 검색
        String jql = "project = " + cfg.getProjectKey()
                + " AND issuetype = Epic AND summary ~ \"" + epicName.replace("\"", "\\\"") + "\"";
        List<Map<String, Object>> results = searchByJql(rt, cfg, jql, 1);
        if (!results.isEmpty()) {
            return (String) results.get(0).get("key");
        }

        // 없으면 새로 생성
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", cfg.getProjectKey()));
        fields.put("issuetype", Map.of("name", "Epic"));
        fields.put("summary", epicName);
        // Epic Name 필드 (Jira Server 기본: customfield_10011)
        fields.put("customfield_10011", epicName);

        Map<String, Object> created = createIssue(rt, cfg, fields);
        String epicKey = (String) created.get("key");
        log.info("[Jira] Epic 생성: {} → {}", epicName, epicKey);
        return epicKey;
    }

    /**
     * Component 검색 또는 생성
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateComponent(RestTemplate rt, JiraConfig cfg, String name, String desc) {
        String url = cfg.getJiraBaseUrl() + "/rest/api/2/project/" + cfg.getProjectKey() + "/components";
        try {
            List<Map<String, Object>> components = rt.getForObject(url, List.class);
            if (components != null) {
                for (Map<String, Object> comp : components) {
                    if (name.equals(comp.get("name"))) {
                        return comp;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Jira] 컴포넌트 목록 조회 실패: {}", e.getMessage());
        }

        // 없으면 생성
        String createUrl = cfg.getJiraBaseUrl() + "/rest/api/2/component";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", cfg.getProjectKey());
        body.put("name", name);
        if (desc != null) body.put("description", desc);
        try {
            Map<String, Object> created = rt.postForObject(createUrl, body, Map.class);
            log.info("[Jira] 컴포넌트 생성: {}", name);
            return created != null ? created : Map.of();
        } catch (Exception e) {
            log.warn("[Jira] 컴포넌트 생성 실패 (이미 존재할 수 있음): {}", e.getMessage());
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
        JiraConfig cfg = getConfig();
        RestTemplate rt = buildRestTemplate(cfg);
        ApiRecord record = recordRepo.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("레코드 없음: id=" + recordId));
        RepoConfig repoCfg = repoConfigRepo.findByRepoName(record.getRepositoryName()).orElse(null);

        String businessName = repoCfg != null && repoCfg.getBusinessName() != null
                ? repoCfg.getBusinessName() : record.getRepositoryName();
        String appType = repoCfg != null && repoCfg.getAppType() != null ? repoCfg.getAppType() : "APP";
        String appTypeLabel = "APP".equals(appType) ? "앱" : "홈페이지";

        // 1. Epic 확보
        String epicName = "[" + businessName + "] URL 차단 검토";
        String epicKey = getOrCreateEpic(rt, cfg, epicName);

        // 2. Component 확보
        String componentName = record.getRepositoryName() + " (" + appTypeLabel + ")";
        Map<String, Object> component = getOrCreateComponent(rt, cfg, componentName,
                businessName + " - " + appTypeLabel);

        // 3. 담당자 매핑
        String assignee = resolveAssignee(record, repoCfg);

        // 4. Story 필드 구성
        Map<String, Object> fields = buildStoryFields(cfg, record, repoCfg, businessName,
                epicKey, component, assignee);

        // 5. 멱등성: jiraIssueKey가 있으면 UPDATE, 없으면 커스텀필드로 검색 후 CREATE/UPDATE
        String issueKey = record.getJiraIssueKey();
        boolean wasNew = (issueKey == null || issueKey.isBlank());

        // jiraIssueKey 없으면 바로 신규 생성 (커스텀 필드 검색 불필요)

        if (issueKey != null && !issueKey.isBlank()) {
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
        log.info("[Jira] 레코드 {} → {} ({})", recordId, issueKey, action);
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
        int created = 0, updated = 0, failed = 0;
        for (ApiRecord r : targets) {
            try {
                Map<String, Object> result = syncRecordToJira(r.getId());
                if ("created".equals(result.get("action"))) created++;
                else updated++;
            } catch (Exception e) {
                log.warn("[Jira] {} 동기화 실패: {}", r.getApiPath(), e.getMessage());
                failed++;
            }
        }
        log.info("[Jira] 레포 {} 동기화: 대상={}, 생성={}, 갱신={}, 실패={}",
                repositoryName, targets.size(), created, updated, failed);
        return Map.of("total", targets.size(), "created", created, "updated", updated, "failed", failed);
    }

    /**
     * 전체 일괄 발행
     */
    @Transactional
    public Map<String, Object> syncAllToJira() {
        List<String> repos = recordRepo.findAllRepositoryNames();
        int totalCreated = 0, totalUpdated = 0, totalFailed = 0;
        for (String repo : repos) {
            Map<String, Object> result = syncRepoToJira(repo);
            totalCreated += (int) result.get("created");
            totalUpdated += (int) result.get("updated");
            totalFailed += (int) result.get("failed");
        }
        log.info("[Jira] 전체 동기화 완료: 생성={}, 갱신={}, 실패={}",
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

        if (record.getJiraIssueKey() == null || record.getJiraIssueKey().isBlank()) {
            return Map.of("status", "skipped", "reason", "no jira issue key");
        }

        Map<String, Object> issue = getIssue(rt, cfg, record.getJiraIssueKey());
        if (issue.isEmpty()) {
            return Map.of("status", "skipped", "reason", "issue not found in Jira");
        }
        applyJiraStatusToRecord(record, issue, cfg);
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
        int synced = 0, failed = 0;
        JiraConfig cfg = getConfig();
        RestTemplate rt = buildRestTemplate(cfg);
        for (ApiRecord r : targets) {
            try {
                Map<String, Object> issue = getIssue(rt, cfg, r.getJiraIssueKey());
                if (!issue.isEmpty()) {
                    applyJiraStatusToRecord(r, issue, cfg);
                    r.setJiraSyncedAt(LocalDateTime.now());
                    recordRepo.save(r);
                    synced++;
                }
            } catch (Exception e) {
                log.warn("[Jira] {} 역방향 동기화 실패: {}", r.getJiraIssueKey(), e.getMessage());
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

        // 마지막 동기화 이후 변경된 티켓만 조회
        String jql = "project = " + cfg.getProjectKey();
        if (cfg.getLastSyncedAt() != null) {
            String since = cfg.getLastSyncedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            jql += " AND updated >= \"" + since + "\"";
        }

        List<Map<String, Object>> issues = searchByJql(rt, cfg, jql, 500);
        int synced = 0, notFound = 0, failed = 0;

        for (Map<String, Object> issue : issues) {
            String issueKey = (String) issue.get("key");
            try {
                // issueKey로 ApiRecord 찾기
                ApiRecord record = recordRepo.findByJiraIssueKey(issueKey).orElse(null);
                if (record == null) {
                    notFound++;
                    continue;
                }

                applyJiraStatusToRecord(record, issue, cfg);
                record.setJiraSyncedAt(LocalDateTime.now());
                recordRepo.save(record);
                synced++;
            } catch (Exception e) {
                log.warn("[Jira] {} 역방향 동기화 실패: {}", issueKey, e.getMessage());
                failed++;
            }
        }

        // 마지막 동기화 시각 갱신
        cfg.setLastSyncedAt(LocalDateTime.now());
        jiraConfigRepo.save(cfg);

        log.info("[Jira] 전체 역방향 동기화: 총={}, 동기화={}, 미발견={}, 실패={}",
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
        RestTemplate rt = buildRestTemplate(cfg);
        try {
            Map<String, Object> me = rt.getForObject(
                    cfg.getJiraBaseUrl() + "/rest/api/2/myself", Map.class);
            if (me == null) {
                return Map.of("success", false, "error", "응답이 비어 있습니다.");
            }
            log.info("[Jira] 연결 테스트 성공: {}", me.get("displayName"));
            return Map.of("success", true,
                    "user", me.getOrDefault("displayName", ""),
                    "email", me.getOrDefault("emailAddress", ""));
        } catch (Exception e) {
            log.error("[Jira] 연결 테스트 실패: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========================================================================
    //  헬퍼 메서드
    // ========================================================================

    private boolean isBlockCandidate(ApiRecord r) {
        String s = r.getStatus();
        return "최우선 차단대상".equals(s)
                || "후순위 차단대상".equals(s)
                || "추가검토필요 차단대상".equals(s);
    }

    /**
     * 담당자 매핑: managerOverride → managerMappings → managerName → JiraUserMapping 변환
     * 팀+이름으로 우선 매핑, 없으면 이름만으로 폴백
     */
    private String resolveAssignee(ApiRecord record, RepoConfig repoCfg) {
        String manager = record.getManagerOverride();
        if (manager == null && repoCfg != null) {
            manager = repoCfg.getManagerName();
        }
        if (manager == null) return null;

        // 팀+이름 복합 매칭 우선
        String team = record.getTeamOverride();
        if (team == null && repoCfg != null) {
            team = repoCfg.getTeamName();
        }
        if (team != null) {
            Optional<JiraUserMapping> byTeam =
                    userMappingRepo.findByTeamNameAndUrlviewerName(team, manager);
            if (byTeam.isPresent()) {
                return byTeam.get().getJiraAccountId();
            }
        }

        // 이름만으로 폴백
        return userMappingRepo.findFirstByUrlviewerName(manager)
                .map(JiraUserMapping::getJiraAccountId)
                .orElse(null);
    }

    /**
     * Story 필드 구성
     */
    private Map<String, Object> buildStoryFields(JiraConfig cfg, ApiRecord record, RepoConfig repoCfg,
                                                  String businessName, String epicKey,
                                                  Map<String, Object> component, String assigneeAccountId) {
        // Summary: [{businessName}] {apiPath} — {내용}
        // 내용: apiOperationValue > descriptionTag 순으로 폴백
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

        // Description 템플릿
        StringBuilder desc = new StringBuilder();
        desc.append("■ 기본 정보\n");
        desc.append("- 업무명: ").append(businessName).append("\n");
        desc.append("- 레포지토리: ").append(record.getRepositoryName()).append("\n");
        if (record.getControllerName() != null) {
            desc.append("- Controller: ").append(record.getControllerName()).append("\n");
        }
        if (record.getMethodName() != null) {
            desc.append("- 메소드: ").append(record.getMethodName()).append("\n");
        }
        desc.append("- URL 경로: ").append(record.getApiPath()).append("\n");
        if (record.getFullUrl() != null) {
            desc.append("- Full URL: ").append(record.getFullUrl()).append("\n");
        }
        desc.append("\n■ 차단 판단 근거\n");
        desc.append("- 상태: ").append(record.getStatus()).append("\n");
        desc.append("- 호출건수: 총 ").append(nn(record.getCallCount())).append("건");
        desc.append(" / 1달 ").append(nn(record.getCallCountMonth())).append("건");
        desc.append(" / 1주 ").append(nn(record.getCallCountWeek())).append("건\n");
        if (record.getBlockCriteria() != null) {
            desc.append("- 차단기준: ").append(record.getBlockCriteria()).append("\n");
        }
        desc.append("\n■ URLViewer 참조\n");
        desc.append("- URLViewer ID: ").append(record.getId()).append("\n");

        // Priority 매핑
        String priority = switch (record.getStatus()) {
            case "최우선 차단대상" -> "Highest";
            case "후순위 차단대상" -> "Medium";
            default -> "Low";
        };

        // 필드 맵 구성
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", cfg.getProjectKey()));
        fields.put("issuetype", Map.of("name", "Story"));
        fields.put("summary", summary);
        fields.put("description", desc.toString());
        fields.put("priority", Map.of("name", priority));

        // Labels
        fields.put("labels", List.of(record.getRepositoryName()));

        // Component
        if (component != null && component.get("id") != null) {
            fields.put("components", List.of(Map.of("id", String.valueOf(component.get("id")))));
        }

        // Assignee (Jira Server: name 방식)
        if (assigneeAccountId != null) {
            fields.put("assignee", Map.of("name", assigneeAccountId));
        }

        return fields;
    }

    /**
     * Jira 이슈 상태를 ApiRecord의 reviewStage/reviewResult에 반영
     */
    @SuppressWarnings("unchecked")
    private void applyJiraStatusToRecord(ApiRecord record, Map<String, Object> issue, JiraConfig cfg) {
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        if (fields == null) return;

        Map<String, Object> statusObj = (Map<String, Object>) fields.get("status");
        Map<String, Object> resolutionObj = (Map<String, Object>) fields.get("resolution");
        String resolution = resolutionObj != null ? (String) resolutionObj.get("name") : null;

        // statusCategory.key 로 완료 여부 판단
        String category = "";
        if (statusObj != null) {
            Map<String, Object> statusCategory = (Map<String, Object>) statusObj.get("statusCategory");
            if (statusCategory != null) {
                category = (String) statusCategory.getOrDefault("key", "");
            }
        }

        if ("done".equals(category)) {
            if ("Blocked".equalsIgnoreCase(resolution) || "차단확정".equals(resolution)) {
                record.setReviewStage("JIRA_APPROVED");
                record.setReviewResult("차단확정");
            } else if ("Excluded".equalsIgnoreCase(resolution) || "차단대상 제외".equals(resolution)) {
                record.setReviewStage("JIRA_REJECTED");
                record.setReviewResult("차단대상 제외");
            } else {
                // 기타 Done (판단불가 등)
                record.setReviewStage("JIRA_APPROVED");
                record.setReviewResult("판단불가");
            }
        } else {
            // To Do, In Progress 등
            record.setReviewStage("JIRA_ISSUED");
        }
    }

    /** null-safe Long → String */
    private String nn(Long v) {
        return v != null ? String.valueOf(v) : "0";
    }

    /**
     * Jira 설정 조회 (없으면 예외)
     */
    private JiraConfig getConfig() {
        return jiraConfigRepo.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Jira 설정이 없습니다. 설정 페이지에서 Jira 연동을 설정해 주세요."));
    }
}
