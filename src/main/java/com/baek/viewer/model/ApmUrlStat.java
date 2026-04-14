package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * APM URL별 호출건수 사전 집계 테이블.
 * api_record(URL 분석)와 무관하게 apm_call_data 전체 기준으로 집계.
 * APM 수집 완료 시 레포별로 갱신 (delete + insert).
 *
 * 기간: 전일(1일) / 1주(7일) / 1달(30일) / 3달(90일) / 6달(180일) / 1년(365일)
 * call-stats.html 대시보드 fast-path 데이터 소스.
 */
@Entity
@Table(name = "apm_url_stat",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_url_stat_repo_path", columnNames = {"repository_name", "api_path"})
    },
    indexes = {
        @Index(name = "idx_url_stat_repo",         columnList = "repository_name"),
        @Index(name = "idx_url_stat_year",          columnList = "call_count"),
        @Index(name = "idx_url_stat_month",         columnList = "call_count_month"),
        @Index(name = "idx_url_stat_week",          columnList = "call_count_week"),
        @Index(name = "idx_url_stat_repo_year",     columnList = "repository_name, call_count"),
        @Index(name = "idx_url_stat_repo_month",    columnList = "repository_name, call_count_month"),
        @Index(name = "idx_url_stat_repo_week",     columnList = "repository_name, call_count_week"),
        @Index(name = "idx_url_stat_repo_3month",   columnList = "repository_name, call_count_3month"),
        @Index(name = "idx_url_stat_repo_6month",   columnList = "repository_name, call_count_6month")
    })
public class ApmUrlStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "api_path", nullable = false, length = 2000)
    private String apiPath;

    // ── 호출건수 ──
    @Column(name = "call_count_yesterday") private long callCountYesterday;
    @Column(name = "call_count_week")      private long callCountWeek;
    @Column(name = "call_count_month")     private long callCountMonth;
    @Column(name = "call_count_3month")    private long callCount3Month;
    @Column(name = "call_count_6month")    private long callCount6Month;
    /** 최근 1년 호출건수 (기존 callCount 컬럼 유지) */
    @Column(name = "call_count")           private long callCount;

    // ── 에러건수 ──
    @Column(name = "error_count_yesterday") private long errorCountYesterday;
    @Column(name = "error_count_week")      private long errorCountWeek;
    @Column(name = "error_count_month")     private long errorCountMonth;
    @Column(name = "error_count_3month")    private long errorCount3Month;
    @Column(name = "error_count_6month")    private long errorCount6Month;
    @Column(name = "error_count_year")      private long errorCountYear;

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

    public long getCallCountYesterday() { return callCountYesterday; }
    public void setCallCountYesterday(long v) { this.callCountYesterday = v; }

    public long getCallCountWeek() { return callCountWeek; }
    public void setCallCountWeek(long v) { this.callCountWeek = v; }

    public long getCallCountMonth() { return callCountMonth; }
    public void setCallCountMonth(long v) { this.callCountMonth = v; }

    public long getCallCount3Month() { return callCount3Month; }
    public void setCallCount3Month(long v) { this.callCount3Month = v; }

    public long getCallCount6Month() { return callCount6Month; }
    public void setCallCount6Month(long v) { this.callCount6Month = v; }

    public long getCallCount() { return callCount; }
    public void setCallCount(long v) { this.callCount = v; }

    public long getErrorCountYesterday() { return errorCountYesterday; }
    public void setErrorCountYesterday(long v) { this.errorCountYesterday = v; }

    public long getErrorCountWeek() { return errorCountWeek; }
    public void setErrorCountWeek(long v) { this.errorCountWeek = v; }

    public long getErrorCountMonth() { return errorCountMonth; }
    public void setErrorCountMonth(long v) { this.errorCountMonth = v; }

    public long getErrorCount3Month() { return errorCount3Month; }
    public void setErrorCount3Month(long v) { this.errorCount3Month = v; }

    public long getErrorCount6Month() { return errorCount6Month; }
    public void setErrorCount6Month(long v) { this.errorCount6Month = v; }

    public long getErrorCountYear() { return errorCountYear; }
    public void setErrorCountYear(long v) { this.errorCountYear = v; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
