package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * APM 호출 데이터 (일별, API별).
 * Whatap/Jennifer/Mock 등 소스별로 수집.
 */
@Entity
@Table(name = "apm_call_data",
    indexes = {
        @Index(name = "idx_apm_repo_api_date", columnList = "repository_name, api_path, call_date"),
        @Index(name = "idx_apm_call_date", columnList = "call_date")
    })
public class ApmCallData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "api_path", nullable = false, length = 2000)
    private String apiPath;

    @Column(name = "call_date", nullable = false)
    private LocalDate callDate;

    @Column(name = "call_count")
    private long callCount;

    @Column(name = "error_count")
    private long errorCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "class_name", columnDefinition = "TEXT")
    private String className;

    /** 데이터 소스: WHATAP, JENNIFER, MOCK */
    @Column(name = "source", length = 20)
    private String source = "MOCK";

    // getters/setters
    public Long getId() { return id; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) {
        this.apiPath = apiPath != null && apiPath.length() > 2000 ? apiPath.substring(0, 2000) : apiPath;
    }
    public LocalDate getCallDate() { return callDate; }
    public void setCallDate(LocalDate callDate) { this.callDate = callDate; }
    public long getCallCount() { return callCount; }
    public void setCallCount(long callCount) { this.callCount = callCount; }
    public long getErrorCount() { return errorCount; }
    public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
