package com.baek.viewer.model;

import jakarta.persistence.*;

@Entity
@Table(name = "schedule_config")
public class ScheduleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 작업 유형: GIT_PULL_EXTRACT, APM_COLLECT */
    @Column(name = "job_type", nullable = false, unique = true, length = 50)
    private String jobType;

    /** 작업별 부가 파라미터 (APM_COLLECT: 수집 범위 일수 "1"/"7"/"30"/"90"/"365") */
    @Column(name = "job_param", length = 100)
    private String jobParam;

    /** 활성화 여부 */
    @Column(name = "enabled")
    private boolean enabled = false;

    /** 실행 주기 유형: DAILY, WEEKLY, HOURLY, CUSTOM */
    @Column(name = "schedule_type", length = 20)
    private String scheduleType = "DAILY";

    /** 실행 시각 (HH:mm) */
    @Column(name = "run_time", length = 10)
    private String runTime = "02:00";

    /** 요일 (WEEKLY일 때: MON,TUE,...) */
    @Column(name = "run_day", length = 20)
    private String runDay;

    /** 간격 (HOURLY일 때: 시간 단위) */
    @Column(name = "interval_hours")
    private Integer intervalHours;

    /** 커스텀 크론 표현식 */
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    /** 마지막 실행 시각 */
    @Column(name = "last_run_at")
    private java.time.LocalDateTime lastRunAt;

    /** 마지막 실행 결과 */
    @Column(name = "last_run_result", length = 500)
    private String lastRunResult;

    /** 설명 */
    @Column(name = "description", length = 200)
    private String description;

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getJobParam() { return jobParam; }
    public void setJobParam(String jobParam) { this.jobParam = jobParam; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public String getRunTime() { return runTime; }
    public void setRunTime(String runTime) { this.runTime = runTime; }
    public String getRunDay() { return runDay; }
    public void setRunDay(String runDay) { this.runDay = runDay; }
    public Integer getIntervalHours() { return intervalHours; }
    public void setIntervalHours(Integer intervalHours) { this.intervalHours = intervalHours; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public java.time.LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(java.time.LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastRunResult() { return lastRunResult; }
    public void setLastRunResult(String lastRunResult) { this.lastRunResult = lastRunResult; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    /** UI 설정을 크론 표현식으로 변환 */
    public String toCronExpression() {
        if ("CUSTOM".equals(scheduleType) && cronExpression != null) return cronExpression;
        String[] hm = (runTime != null ? runTime : "02:00").split(":");
        int hour = Integer.parseInt(hm[0]);
        int min = hm.length > 1 ? Integer.parseInt(hm[1]) : 0;
        if ("HOURLY".equals(scheduleType)) {
            int h = intervalHours != null && intervalHours > 0 ? intervalHours : 1;
            return String.format("0 %d 0/%d * * ?", min, h);
        }
        if ("WEEKLY".equals(scheduleType)) {
            String day = runDay != null ? runDay : "MON";
            return String.format("0 %d %d ? * %s", min, hour, day);
        }
        // DAILY (default)
        return String.format("0 %d %d * * ?", min, hour);
    }
}
