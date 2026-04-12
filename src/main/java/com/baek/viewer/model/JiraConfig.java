package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "jira_config")
public class JiraConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Jira 서버 URL (예: https://jira.example.com) */
    @Column(name = "jira_base_url", length = 500)
    private String jiraBaseUrl;

    /** Jira 프로젝트 키 (예: URLB) */
    @Column(name = "project_key", length = 20)
    private String projectKey;

    /** Jira API 토큰 */
    @Column(name = "api_token", length = 500)
    private String apiToken;

    /** 서비스 계정 ID (Basic Auth 사용자) */
    @Column(name = "service_account", length = 100)
    private String serviceAccount;

    /** 동기화 활성화 여부 */
    @Column(name = "sync_enabled")
    private boolean syncEnabled = false;

    /** 동기화 주기 (분 단위) */
    @Column(name = "sync_interval")
    private int syncInterval = 60;

    /** 마지막 동기화 일시 */
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** URLViewer ID 커스텀 필드 ID (예: customfield_10100) */
    @Column(name = "custom_field_id", length = 50)
    private String customFieldId;

    /** JSON: Jira 상태명 → reviewStage 매핑 */
    @Column(name = "status_mappings", columnDefinition = "TEXT")
    private String statusMappings;

    /** 생성 일시 */
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── getter / setter ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getJiraBaseUrl() { return jiraBaseUrl; }
    public void setJiraBaseUrl(String jiraBaseUrl) { this.jiraBaseUrl = jiraBaseUrl; }

    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }

    public String getServiceAccount() { return serviceAccount; }
    public void setServiceAccount(String serviceAccount) { this.serviceAccount = serviceAccount; }

    public boolean isSyncEnabled() { return syncEnabled; }
    public void setSyncEnabled(boolean syncEnabled) { this.syncEnabled = syncEnabled; }

    public int getSyncInterval() { return syncInterval; }
    public void setSyncInterval(int syncInterval) { this.syncInterval = syncInterval; }

    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public String getCustomFieldId() { return customFieldId; }
    public void setCustomFieldId(String customFieldId) { this.customFieldId = customFieldId; }

    public String getStatusMappings() { return statusMappings; }
    public void setStatusMappings(String statusMappings) { this.statusMappings = statusMappings; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
