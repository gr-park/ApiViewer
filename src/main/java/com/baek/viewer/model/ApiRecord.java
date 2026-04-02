package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "api_record", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"repository_name", "api_path", "http_method"})
})
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

    /** 마지막으로 소스에서 분석된 날짜 (추출 시마다 갱신) */
    @Column(name = "last_analyzed_date")
    private LocalDate lastAnalyzedDate;

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

    @Column(name = "method_name")
    private String methodName;

    @Column(name = "controller_name")
    private String controllerName;

    @Column(name = "repo_path", length = 500)
    private String repoPath;

    /** @Deprecated 어노테이션 여부 (상태 계산용, 내부 용도) */
    @Column(name = "is_deprecated", length = 1)
    private String isDeprecated;

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

    @Column(name = "git_history", columnDefinition = "TEXT")
    private String gitHistory; // JSON: [{"date":"...","author":"...","message":"..."},...]

    public Long getId() { return id; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) { this.apiPath = apiPath; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public LocalDate getLastAnalyzedDate() { return lastAnalyzedDate; }
    public void setLastAnalyzedDate(LocalDate lastAnalyzedDate) { this.lastAnalyzedDate = lastAnalyzedDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isStatusOverridden() { return statusOverridden; }
    public void setStatusOverridden(boolean statusOverridden) { this.statusOverridden = statusOverridden; }
    public Long getCallCount() { return callCount; }
    public void setCallCount(Long callCount) { this.callCount = callCount; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getControllerName() { return controllerName; }
    public void setControllerName(String controllerName) { this.controllerName = controllerName; }
    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public String getIsDeprecated() { return isDeprecated; }
    public void setIsDeprecated(String isDeprecated) { this.isDeprecated = isDeprecated; }
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
    public String getBlockTarget() { return blockTarget; }
    public void setBlockTarget(String blockTarget) { this.blockTarget = blockTarget; }
    public String getBlockCriteria() { return blockCriteria; }
    public void setBlockCriteria(String blockCriteria) { this.blockCriteria = blockCriteria; }
    public String getGitHistory() { return gitHistory; }
    public void setGitHistory(String gitHistory) { this.gitHistory = gitHistory; }
}
