package com.baek.viewer.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 목록 조회용 경량 프로젝션 — fullComment, controllerComment, blockedReason 제외
 */
public interface ApiRecordSummary {
    Long getId();
    String getRepositoryName();
    String getApiPath();
    String getHttpMethod();
    LocalDateTime getLastAnalyzedAt();
    String getCreatedIp();
    LocalDateTime getModifiedAt();
    String getModifiedIp();
    String getReviewedIp();
    String getStatus();
    boolean isStatusOverridden();
    String getBlockTarget();
    String getBlockCriteria();
    Long getCallCount();
    Long getCallCountMonth();
    Long getCallCountWeek();
    String getMethodName();
    String getControllerName();
    String getRepoPath();
    String getIsDeprecated();
    String getProgramId();
    String getApiOperationValue();
    String getDescriptionTag();
    String getRequestPropertyValue();
    String getControllerRequestPropertyValue();
    String getFullUrl();
    String getControllerFilePath();
    String getMemo();
    String getReviewResult();
    String getReviewOpinion();
    String getReviewTeam();
    String getReviewManager();
    LocalDateTime getReviewedAt();
    LocalDate getBlockedDate();
    String getBlockedReason();
    Boolean isStatusChanged();
    Boolean isNew();
    String getDataSource();
    String getStatusChangeLog();
    String getTeamOverride();
    String getManagerOverride();
    String getGitHistory();
    String getReviewStage();
    String getJiraIssueKey();
    String getJiraIssueUrl();
    LocalDateTime getJiraSyncedAt();
}
