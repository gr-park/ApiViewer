package com.baek.viewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 배치 수행 이력 — JobListener 또는 각 Job 종료 시점에 한 건씩 INSERT.
 * 설정 페이지 "배치 수행 이력" 영역에서 날짜/배치명으로 조회한다.
 */
@Entity
@Table(name = "batch_execution_log", indexes = {
        @Index(name = "idx_batch_log_job_start", columnList = "job_type, start_time"),
        @Index(name = "idx_batch_log_start", columnList = "start_time")
})
public class BatchExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 배치 유형 (GIT_PULL_EXTRACT, APM_COLLECT 등) */
    @Column(name = "job_type", nullable = false, length = 50)
    private String jobType;

    /** 수행 당시 스케줄 설명 (예: "Git Pull & 소스 분석") */
    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** SUCCESS | FAIL */
    @Column(name = "status", length = 20)
    private String status;

    /** 처리 건수 (집계 가능한 배치만. 기타 null) */
    @Column(name = "item_count")
    private Integer itemCount;

    /**
     * 보조 실패·스킵 건수 등 (예: {@code GIT_PULL_EXTRACT}에서 레포별 추출 실패 수).
     * Job 결과 Map 의 {@code failCount} 로 기록. 미사용 배치는 null.
     */
    @Column(name = "fail_item_count")
    private Integer failItemCount;

    /** 결과 요약 (성공 시 주요 수치, 실패 시 간단 사유) */
    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    /** 상세 메시지 (에러 스택/전체 결과 텍스트 등) */
    @Column(name = "message", length = 4000)
    private String message;

    /** 소요 시간(ms) */
    @Column(name = "duration_ms")
    private Long durationMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public Integer getFailItemCount() { return failItemCount; }
    public void setFailItemCount(Integer failItemCount) { this.failItemCount = failItemCount; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}
