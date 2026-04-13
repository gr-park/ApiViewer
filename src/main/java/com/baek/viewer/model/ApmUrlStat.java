package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * APM URL별 호출건수 사전 집계 테이블.
 * api_record(URL 분석)와 무관하게 apm_call_data 전체 기준으로 집계.
 * APM 수집 완료 시 레포별로 갱신 (delete + insert).
 *
 * call-stats.html 1주/1달/1년 fast-path에서 사용.
 */
@Entity
@Table(name = "apm_url_stat",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_url_stat_repo_path", columnNames = {"repository_name", "api_path"})
    },
    indexes = {
        @Index(name = "idx_url_stat_repo",        columnList = "repository_name"),
        @Index(name = "idx_url_stat_year",         columnList = "call_count"),
        @Index(name = "idx_url_stat_month",        columnList = "call_count_month"),
        @Index(name = "idx_url_stat_week",         columnList = "call_count_week"),
        @Index(name = "idx_url_stat_repo_year",    columnList = "repository_name, call_count"),
        @Index(name = "idx_url_stat_repo_month",   columnList = "repository_name, call_count_month"),
        @Index(name = "idx_url_stat_repo_week",    columnList = "repository_name, call_count_week")
    })
public class ApmUrlStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 레포지토리명 */
    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    /** API 경로 */
    @Column(name = "api_path", nullable = false, length = 2000)
    private String apiPath;

    /** 최근 1년 호출건수 */
    @Column(name = "call_count")
    private long callCount;

    /** 최근 1달 호출건수 */
    @Column(name = "call_count_month")
    private long callCountMonth;

    /** 최근 1주 호출건수 */
    @Column(name = "call_count_week")
    private long callCountWeek;

    /** 집계 일시 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── getters / setters ──

    public Long getId() { return id; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) {
        this.apiPath = apiPath != null && apiPath.length() > 2000 ? apiPath.substring(0, 2000) : apiPath;
    }

    public long getCallCount() { return callCount; }
    public void setCallCount(long callCount) { this.callCount = callCount; }

    public long getCallCountMonth() { return callCountMonth; }
    public void setCallCountMonth(long callCountMonth) { this.callCountMonth = callCountMonth; }

    public long getCallCountWeek() { return callCountWeek; }
    public void setCallCountWeek(long callCountWeek) { this.callCountWeek = callCountWeek; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
