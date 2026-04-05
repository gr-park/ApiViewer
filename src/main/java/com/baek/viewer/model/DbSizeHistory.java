package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DB 파일 사이즈 일별 스냅샷 (증가 추이 모니터링용).
 */
@Entity
@Table(name = "db_size_history")
public class DbSizeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false, unique = true)
    private LocalDate snapshotDate;

    @Column(name = "db_size_bytes")
    private long dbSizeBytes;

    @Column(name = "api_record_count")
    private long apiRecordCount;

    @Column(name = "apm_call_data_count")
    private long apmCallDataCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
    public long getDbSizeBytes() { return dbSizeBytes; }
    public void setDbSizeBytes(long dbSizeBytes) { this.dbSizeBytes = dbSizeBytes; }
    public long getApiRecordCount() { return apiRecordCount; }
    public void setApiRecordCount(long apiRecordCount) { this.apiRecordCount = apiRecordCount; }
    public long getApmCallDataCount() { return apmCallDataCount; }
    public void setApmCallDataCount(long apmCallDataCount) { this.apmCallDataCount = apmCallDataCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
