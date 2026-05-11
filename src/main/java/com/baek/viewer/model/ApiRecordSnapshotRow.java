package com.baek.viewer.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_record_snapshot_row",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_snapshot_key", columnNames = {"snapshot_id", "repository_name", "api_path", "http_method"})
        },
        indexes = {
                @Index(name = "idx_snap_row_snapshot", columnList = "snapshot_id"),
                @Index(name = "idx_snap_row_repo", columnList = "snapshot_id, repository_name"),
                @Index(name = "idx_snap_row_status", columnList = "snapshot_id, status"),
        }
)
public class ApiRecordSnapshotRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    /** 원본 api_record.id */
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "api_path", nullable = false, length = 2000)
    private String apiPath;

    @Column(name = "http_method", length = 20)
    private String httpMethod;

    @Column(name = "last_analyzed_at")
    private LocalDateTime lastAnalyzedAt;

    @Column(name = "created_ip", length = 50)
    private String createdIp;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    @Column(name = "modified_ip", length = 50)
    private String modifiedIp;

    @Column(name = "reviewed_ip", length = 50)
    private String reviewedIp;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "auto_analyzed_status", length = 50)
    private String autoAnalyzedStatus;

    @Column(name = "status_overridden")
    private Boolean statusOverridden;

    @Column(name = "log_work_excluded")
    private Boolean logWorkExcluded;

    @Column(name = "recent_log_only")
    private Boolean recentLogOnly;

    @Column(name = "test_suspect_reason", columnDefinition = "TEXT")
    private String testSuspectReason;

    @Column(name = "path_param_pattern", columnDefinition = "TEXT")
    private String pathParamPattern;

    @Column(name = "block_target", length = 30)
    private String blockTarget;

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

    @Column(name = "is_deprecated", length = 1)
    private String isDeprecated;

    @Column(name = "has_url_block", length = 1)
    private String hasUrlBlock;

    @Column(name = "block_marking_incomplete")
    private Boolean blockMarkingIncomplete;

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

    @Column(name = "controller_file_path", columnDefinition = "TEXT")
    private String controllerFilePath;

    @Column(name = "memo", length = 500)
    private String memo;

    @Column(name = "review_result", length = 50)
    private String reviewResult;

    @Column(name = "review_opinion", length = 500)
    private String reviewOpinion;

    @Column(name = "cbo_scheduled_date")
    private LocalDate cboScheduledDate;

    @Column(name = "deploy_scheduled_date")
    private LocalDate deployScheduledDate;

    @Column(name = "deploy_csr", length = 50)
    private String deployCsr;

    @Column(name = "deploy_manager", length = 100)
    private String deployManager;

    @Column(name = "review_team", length = 100)
    private String reviewTeam;

    @Column(name = "review_manager", length = 100)
    private String reviewManager;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "blocked_date")
    private LocalDate blockedDate;

    @Column(name = "blocked_reason", columnDefinition = "TEXT")
    private String blockedReason;

    @Column(name = "status_changed")
    private Boolean statusChanged;

    @Column(name = "status_change_log", columnDefinition = "TEXT")
    private String statusChangeLog;

    @Column(name = "is_new")
    private Boolean isNew;

    @Column(name = "data_source", length = 20)
    private String dataSource;

    @Column(name = "team_override", length = 100)
    private String teamOverride;

    @Column(name = "manager_override", length = 100)
    private String managerOverride;

    @Column(name = "manager_overridden")
    private Boolean managerOverridden;

    @Column(name = "description_override", columnDefinition = "TEXT")
    private String descriptionOverride;

    @Column(name = "git_history", columnDefinition = "TEXT")
    private String gitHistory;

    @Column(name = "review_stage", length = 30)
    private String reviewStage;

    @Column(name = "internal_reviewer", length = 50)
    private String internalReviewer;

    @Column(name = "internal_reviewed_at")
    private LocalDateTime internalReviewedAt;

    @Column(name = "internal_memo", columnDefinition = "TEXT")
    private String internalMemo;

    @Column(name = "jira_epic_key", length = 50)
    private String jiraEpicKey;

    @Column(name = "jira_issue_key", length = 50)
    private String jiraIssueKey;

    @Column(name = "jira_issue_url", length = 500)
    private String jiraIssueUrl;

    @Column(name = "jira_synced_at")
    private LocalDateTime jiraSyncedAt;

    public Long getId() { return id; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) { this.apiPath = apiPath; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAutoAnalyzedStatus() { return autoAnalyzedStatus; }
    public void setAutoAnalyzedStatus(String autoAnalyzedStatus) { this.autoAnalyzedStatus = autoAnalyzedStatus; }
    public String getPathParamPattern() { return pathParamPattern; }
    public void setPathParamPattern(String pathParamPattern) { this.pathParamPattern = pathParamPattern; }
}

