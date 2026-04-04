package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_record",
    uniqueConstraints = { @UniqueConstraint(columnNames = {"repository_name", "api_path", "http_method"}) },
    indexes = { @Index(name = "idx_repo_name", columnList = "repository_name") })
public class ApiRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "api_path", nullable = false, length = 500)
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

    @Column(name = "method_name")
    private String methodName;

    @Column(name = "controller_name")
    private String controllerName;

    @Column(name = "repo_path", length = 500)
    private String repoPath;

    /** @Deprecated 어노테이션 여부 (상태 계산용, 내부 용도) */
    @Column(name = "is_deprecated", length = 1)
    private String isDeprecated;

    /** 메소드 첫 줄에 UnsupportedOperationException throw 여부 */
    @Column(name = "has_url_block", length = 1)
    private String hasUrlBlock;

    @Column(name = "program_id")
    private String programId;

    @Column(name = "api_operation_value", length = 500)
    private String apiOperationValue;

    @Column(name = "description_tag", length = 500)
    private String descriptionTag;

    @Column(name = "full_comment", columnDefinition = "TEXT")
    private String fullComment;

    @Column(name = "controller_comment", columnDefinition = "TEXT")
    private String controllerComment;

    @Column(name = "request_property_value", length = 500)
    private String requestPropertyValue;

    @Column(name = "controller_request_property_value", length = 500)
    private String controllerRequestPropertyValue;

    @Column(name = "full_url", length = 1000)
    private String fullUrl;

    /** 컨트롤러 파일 풀패스 (/{repoName}/{repoPath}) */
    @Column(name = "controller_file_path", length = 1000)
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
    private boolean statusChanged = false;

    /** 상태 변경 내역 로그 */
    @Column(name = "status_change_log", length = 500)
    private String statusChangeLog;

    /** 신규 추가 플래그 (분석 시 처음 발견) */
    @Column(name = "is_new")
    private boolean isNew = false;

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

    public Long getId() { return id; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) { this.apiPath = apiPath; }
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
    public LocalDate getBlockedDate() { return blockedDate; }
    public void setBlockedDate(LocalDate blockedDate) { this.blockedDate = blockedDate; }
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public boolean isStatusChanged() { return statusChanged; }
    public void setStatusChanged(boolean statusChanged) { this.statusChanged = statusChanged; }
    public boolean isNew() { return isNew; }
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
}
