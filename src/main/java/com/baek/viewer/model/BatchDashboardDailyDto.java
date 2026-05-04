package com.baek.viewer.model;

import java.time.LocalDateTime;

/**
 * 대시보드용 배치 이력 — 동일 (일자, jobType) 당 1행. 당일 다회 수행 시 마지막 수행 시각·결과를 대표로 하고 {@code runCount}에 횟수를 담는다.
 */
public class BatchDashboardDailyDto {

    private String runDate;
    private String jobType;
    private String description;
    private int runCount;
    private LocalDateTime lastStartTime;
    private LocalDateTime lastEndTime;
    private Long durationMs;
    private Integer itemCount;
    /** GIT_PULL_EXTRACT 등 — 부분 실패 레포 수 (없으면 null) */
    private Integer failItemCount;
    private String status;
    private String resultSummary;

    public String getRunDate() { return runDate; }
    public void setRunDate(String runDate) { this.runDate = runDate; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getRunCount() { return runCount; }
    public void setRunCount(int runCount) { this.runCount = runCount; }
    public LocalDateTime getLastStartTime() { return lastStartTime; }
    public void setLastStartTime(LocalDateTime lastStartTime) { this.lastStartTime = lastStartTime; }
    public LocalDateTime getLastEndTime() { return lastEndTime; }
    public void setLastEndTime(LocalDateTime lastEndTime) { this.lastEndTime = lastEndTime; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public Integer getFailItemCount() { return failItemCount; }
    public void setFailItemCount(Integer failItemCount) { this.failItemCount = failItemCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
}
