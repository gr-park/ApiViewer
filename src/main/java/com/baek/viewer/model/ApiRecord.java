package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_record",
    uniqueConstraints = { @UniqueConstraint(columnNames = {"repository_name", "api_path", "http_method"}) },
    indexes = {
        @Index(name = "idx_repo_name",          columnList = "repository_name"),
        @Index(name = "idx_status",             columnList = "status"),
        @Index(name = "idx_status_repo",        columnList = "status, repository_name"),
        @Index(name = "idx_call_count",         columnList = "call_count"),
        @Index(name = "idx_blocked_date",       columnList = "blocked_date"),
        @Index(name = "idx_status_overridden",  columnList = "status_overridden"),
        @Index(name = "idx_block_target",       columnList = "block_target"),
        @Index(name = "idx_is_new",             columnList = "is_new"),
        @Index(name = "idx_status_changed",     columnList = "status_changed"),
        @Index(name = "idx_review_stage",      columnList = "review_stage"),
        @Index(name = "idx_jira_issue_key",    columnList = "jira_issue_key"),
        @Index(name = "idx_jira_epic_key",     columnList = "jira_epic_key")
    })
public class ApiRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "api_path", nullable = false, length = 2000)
    private String apiPath;

    @Column(name = "http_method", length = 20)
    private String httpMethod;

    /** 마지막 분석 일시 (추출 시마다 갱신) */
    @Column(name = "last_analyzed_at")
    private LocalDateTime lastAnalyzedAt;

    /** 최초 생성 IP */
    @Column(name = "created_ip", length = 50)
    private String createdIp;

    /** 변경 일시 (조회화면 수정 시 갱신) */
    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    /** 변경 IP */
    @Column(name = "modified_ip", length = 50)
    private String modifiedIp;

    /** 검토 IP */
    @Column(name = "reviewed_ip", length = 50)
    private String reviewedIp;

    /**
     * 상태: 사용 / 차단완료
     * statusOverridden=true이면 수동 설정 유지 (자동 재계산 안 함)
     */
    @Column(name = "status", length = 20)
    private String status = "사용";

    @Column(name = "status_overridden")
    private boolean statusOverridden = false;

    /**
     * 최우선 차단대상 중 "로그작업 이력 제외" 판정 건 여부.
     * true = 전체 커밋 기준으론 1년 미만이지만 로그작업 커밋을 제외하면 1년 경과 (로그작업 때문에 구제된 상태)
     * false = 전체 커밋이 이미 1년 경과 (순수 미사용) 또는 "최우선 차단대상"이 아닌 레코드
     * — 화면에서 (1)최우선 / (1)최우선(로그작업이력 제외) 두 지표로 분리 집계할 때 subset 구분용.
     */
    @Column(name = "log_work_excluded")
    private Boolean logWorkExcluded = false;

    /** 차단대상: 최우선 차단대상 / 후순위 차단대상 / null(미지정) — 수동 설정 전용 */
    @Column(name = "block_target", length = 30)
    private String blockTarget;

    /** 차단대상기준: 사유 텍스트 — 수동 설정 전용 */
    @Column(name = "block_criteria", length = 100)
    private String blockCriteria;

    @Column(name = "call_count")
    private Long callCount;

    @Column(name = "call_count_month")
    private Long callCountMonth;

    @Column(name = "call_count_week")
    private Long callCountWeek;

    @Column(name = "method_name", length = 500)
    private String methodName;

    @Column(name = "controller_name", length = 500)
    private String controllerName;

    @Column(name = "repo_path", columnDefinition = "TEXT")
    private String repoPath;

    /** @Deprecated 어노테이션 여부 (상태 계산용, 내부 용도) */
    @Column(name = "is_deprecated", length = 1)
    private String isDeprecated;

    /** 메소드 첫 줄에 UnsupportedOperationException throw 여부 */
    @Column(name = "has_url_block", length = 1)
    private String hasUrlBlock;

    @Column(name = "program_id", length = 500)
    private String programId;

    @Column(name = "api_operation_value", columnDefinition = "TEXT")
    private String apiOperationValue;

    @Column(name = "description_tag", columnDefinition = "TEXT")
    private String descriptionTag;

    @Column(name = "full_comment", columnDefinition = "TEXT")
    private String fullComment;

    @Column(name = "controller_comment", columnDefinition = "TEXT")
    private String controllerComment;

    @Column(name = "request_property_value", columnDefinition = "TEXT")
    private String requestPropertyValue;

    @Column(name = "controller_request_property_value", columnDefinition = "TEXT")
    private String controllerRequestPropertyValue;

    @Column(name = "full_url", columnDefinition = "TEXT")
    private String fullUrl;

    /** 컨트롤러 파일 풀패스 (/{repoName}/{repoPath}) */
    @Column(name = "controller_file_path", columnDefinition = "TEXT")
    private String controllerFilePath;

    /** 비고 (사용자 메모) */
    @Column(name = "memo", length = 500)
    private String memo;

    /** 현업검토결과: 차단대상 제외 등 */
    @Column(name = "review_result", length = 50)
    private String reviewResult;

    /** 현업검토의견: 자유 텍스트 */
    @Column(name = "review_opinion", length = 500)
    private String reviewOpinion;

    /** CBO 예정일자 */
    @Column(name = "cbo_scheduled_date")
    private java.time.LocalDate cboScheduledDate;

    /** 배포 예정일자 */
    @Column(name = "deploy_scheduled_date")
    private java.time.LocalDate deployScheduledDate;

    /** 배포 CSR */
    @Column(name = "deploy_csr", length = 50)
    private String deployCsr;

    /** 현업 팀 */
    @Column(name = "review_team", length = 100)
    private String reviewTeam;

    /** 현업 담당자 */
    @Column(name = "review_manager", length = 100)
    private String reviewManager;

    /** 현업검토일시 (자동 갱신) */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 차단일자: fullComment에서 [URL차단작업][YYYY-MM-DD] 파싱 */
    @Column(name = "blocked_date")
    private LocalDate blockedDate;

    /** 차단근거: fullComment에서 [CSR-XXXXX] 내용 파싱 */
    @Column(name = "blocked_reason", columnDefinition = "TEXT")
    private String blockedReason;

    /** 상태 변경 감지 플래그 (IT 담당자 검토 필요) */
    @Column(name = "status_changed")
    private Boolean statusChanged = false;

    /** 상태 변경 내역 로그 */
    @Column(name = "status_change_log", columnDefinition = "TEXT")
    private String statusChangeLog;

    /** 신규 추가 플래그 (분석 시 처음 발견) */
    @Column(name = "is_new")
    // note: Boolean (null-safe) — 기존 row에 NULL이 있을 수 있음
    private Boolean isNew = false;

    /** 데이터 소스: ANALYSIS(분석), UPLOAD(엑셀 업로드) */
    @Column(name = "data_source", length = 20)
    private String dataSource = "ANALYSIS";

    /** 팀 오버라이드 (조회화면에서 수정 시) */
    @Column(name = "team_override", length = 100)
    private String teamOverride;

    /** 담당자 오버라이드 (조회화면에서 수정 시) */
    @Column(name = "manager_override", length = 100)
    private String managerOverride;

    @Column(name = "git_history", columnDefinition = "TEXT")
    private String gitHistory; // JSON: [{"date":"...","author":"...","message":"..."},...]

    /** Jira 검토 단계 */
    @Column(name = "review_stage", length = 30)
    private String reviewStage;

    /** IT 내부 1차 검토자 */
    @Column(name = "internal_reviewer", length = 50)
    private String internalReviewer;

    /** IT 내부 검토 일시 */
    @Column(name = "internal_reviewed_at")
    private LocalDateTime internalReviewedAt;

    /** IT 내부 메모 (오탐 사유 등) */
    @Column(name = "internal_memo", columnDefinition = "TEXT")
    private String internalMemo;

    /** Jira Epic 키 */
    @Column(name = "jira_epic_key", length = 50)
    private String jiraEpicKey;

    /** Jira Story 티켓 키 */
    @Column(name = "jira_issue_key", length = 50)
    private String jiraIssueKey;

    /** Jira 웹 링크 */
    @Column(name = "jira_issue_url", length = 500)
    private String jiraIssueUrl;

    /** Jira 마지막 동기화 일시 */
    @Column(name = "jira_synced_at")
    private LocalDateTime jiraSyncedAt;

    public Long getId() { return id; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) {
        this.apiPath = apiPath != null && apiPath.length() > 2000 ? apiPath.substring(0, 2000) : apiPath;
    }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public LocalDateTime getLastAnalyzedAt() { return lastAnalyzedAt; }
    public void setLastAnalyzedAt(LocalDateTime lastAnalyzedAt) { this.lastAnalyzedAt = lastAnalyzedAt; }
    public String getCreatedIp() { return createdIp; }
    public void setCreatedIp(String createdIp) { this.createdIp = createdIp; }
    public LocalDateTime getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(LocalDateTime modifiedAt) { this.modifiedAt = modifiedAt; }
    public String getModifiedIp() { return modifiedIp; }
    public void setModifiedIp(String modifiedIp) { this.modifiedIp = modifiedIp; }
    public String getReviewedIp() { return reviewedIp; }
    public void setReviewedIp(String reviewedIp) { this.reviewedIp = reviewedIp; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isStatusOverridden() { return statusOverridden; }
    public void setStatusOverridden(boolean statusOverridden) { this.statusOverridden = statusOverridden; }
    public boolean isLogWorkExcluded() { return logWorkExcluded != null && logWorkExcluded; }
    public Boolean getLogWorkExcluded() { return logWorkExcluded; }
    public void setLogWorkExcluded(boolean logWorkExcluded) { this.logWorkExcluded = logWorkExcluded; }
    public Long getCallCount() { return callCount; }
    public void setCallCount(Long callCount) { this.callCount = callCount; }
    public Long getCallCountMonth() { return callCountMonth; }
    public void setCallCountMonth(Long callCountMonth) { this.callCountMonth = callCountMonth; }
    public Long getCallCountWeek() { return callCountWeek; }
    public void setCallCountWeek(Long callCountWeek) { this.callCountWeek = callCountWeek; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getControllerName() { return controllerName; }
    public void setControllerName(String controllerName) { this.controllerName = controllerName; }
    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public String getIsDeprecated() { return isDeprecated; }
    public void setIsDeprecated(String isDeprecated) { this.isDeprecated = isDeprecated; }
    public String getHasUrlBlock() { return hasUrlBlock; }
    public void setHasUrlBlock(String hasUrlBlock) { this.hasUrlBlock = hasUrlBlock; }
    public String getProgramId() { return programId; }
    public void setProgramId(String programId) { this.programId = programId; }
    public String getApiOperationValue() { return apiOperationValue; }
    public void setApiOperationValue(String apiOperationValue) { this.apiOperationValue = apiOperationValue; }
    public String getDescriptionTag() { return descriptionTag; }
    public void setDescriptionTag(String descriptionTag) { this.descriptionTag = descriptionTag; }
    public String getFullComment() { return fullComment; }
    public void setFullComment(String fullComment) { this.fullComment = fullComment; }
    public String getControllerComment() { return controllerComment; }
    public void setControllerComment(String controllerComment) { this.controllerComment = controllerComment; }
    public String getRequestPropertyValue() { return requestPropertyValue; }
    public void setRequestPropertyValue(String requestPropertyValue) { this.requestPropertyValue = requestPropertyValue; }
    public String getControllerRequestPropertyValue() { return controllerRequestPropertyValue; }
    public void setControllerRequestPropertyValue(String v) { this.controllerRequestPropertyValue = v; }
    public String getFullUrl() { return fullUrl; }
    public void setFullUrl(String fullUrl) { this.fullUrl = fullUrl; }
    public String getControllerFilePath() { return controllerFilePath; }
    public void setControllerFilePath(String controllerFilePath) { this.controllerFilePath = controllerFilePath; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public String getReviewResult() { return reviewResult; }
    public void setReviewResult(String reviewResult) { this.reviewResult = reviewResult; }
    public String getReviewOpinion() { return reviewOpinion; }
    public void setReviewOpinion(String reviewOpinion) { this.reviewOpinion = reviewOpinion; }
    public java.time.LocalDate getCboScheduledDate() { return cboScheduledDate; }
    public void setCboScheduledDate(java.time.LocalDate v) { this.cboScheduledDate = v; }
    public java.time.LocalDate getDeployScheduledDate() { return deployScheduledDate; }
    public void setDeployScheduledDate(java.time.LocalDate v) { this.deployScheduledDate = v; }
    public String getDeployCsr() { return deployCsr; }
    public void setDeployCsr(String v) { this.deployCsr = v; }
    public LocalDate getBlockedDate() { return blockedDate; }
    public void setBlockedDate(LocalDate blockedDate) { this.blockedDate = blockedDate; }
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public boolean isStatusChanged() { return statusChanged != null && statusChanged; }
    public void setStatusChanged(boolean statusChanged) { this.statusChanged = statusChanged; }
    public boolean isNew() { return isNew != null && isNew; }
    public void setNew(boolean isNew) { this.isNew = isNew; }
    public String getDataSource() { return dataSource; }
    public void setDataSource(String dataSource) { this.dataSource = dataSource; }
    public String getStatusChangeLog() { return statusChangeLog; }
    public void setStatusChangeLog(String statusChangeLog) { this.statusChangeLog = statusChangeLog; }
    public String getReviewTeam() { return reviewTeam; }
    public void setReviewTeam(String reviewTeam) { this.reviewTeam = reviewTeam; }
    public String getReviewManager() { return reviewManager; }
    public void setReviewManager(String reviewManager) { this.reviewManager = reviewManager; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getTeamOverride() { return teamOverride; }
    public void setTeamOverride(String teamOverride) { this.teamOverride = teamOverride; }
    public String getManagerOverride() { return managerOverride; }
    public void setManagerOverride(String managerOverride) { this.managerOverride = managerOverride; }
    public String getBlockTarget() { return blockTarget; }
    public void setBlockTarget(String blockTarget) { this.blockTarget = blockTarget; }
    public String getBlockCriteria() { return blockCriteria; }
    public void setBlockCriteria(String blockCriteria) { this.blockCriteria = blockCriteria; }
    public String getGitHistory() { return gitHistory; }
    public void setGitHistory(String gitHistory) { this.gitHistory = gitHistory; }
    public String getReviewStage() { return reviewStage; }
    public void setReviewStage(String reviewStage) { this.reviewStage = reviewStage; }
    public String getInternalReviewer() { return internalReviewer; }
    public void setInternalReviewer(String internalReviewer) { this.internalReviewer = internalReviewer; }
    public LocalDateTime getInternalReviewedAt() { return internalReviewedAt; }
    public void setInternalReviewedAt(LocalDateTime v) { this.internalReviewedAt = v; }
    public String getInternalMemo() { return internalMemo; }
    public void setInternalMemo(String internalMemo) { this.internalMemo = internalMemo; }
    public String getJiraEpicKey() { return jiraEpicKey; }
    public void setJiraEpicKey(String jiraEpicKey) { this.jiraEpicKey = jiraEpicKey; }
    public String getJiraIssueKey() { return jiraIssueKey; }
    public void setJiraIssueKey(String jiraIssueKey) { this.jiraIssueKey = jiraIssueKey; }
    public String getJiraIssueUrl() { return jiraIssueUrl; }
    public void setJiraIssueUrl(String jiraIssueUrl) { this.jiraIssueUrl = jiraIssueUrl; }
    public LocalDateTime getJiraSyncedAt() { return jiraSyncedAt; }
    public void setJiraSyncedAt(LocalDateTime v) { this.jiraSyncedAt = v; }
}
