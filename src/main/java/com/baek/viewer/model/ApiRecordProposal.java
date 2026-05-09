package com.baek.viewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 담당자 제안(draft). 승인 시 공식 {@link ApiRecord}에 반영 후 삭제.
 */
@Entity
@Table(name = "api_record_proposal",
        uniqueConstraints = @UniqueConstraint(name = "uk_proposal_record", columnNames = "record_id"))
public class ApiRecordProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "submitted_by", length = 200)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 제안 저장 시 EDITOR 세션의 담당자 ID (반려 알림 대상) */
    @Column(name = "submitter_assignee_id")
    private Long submitterAssigneeId;

    /** 목록/상세 표시용 요약 (patch 기반 짧은 문장) */
    @Column(name = "summary_text", length = 500)
    private String summaryText;

    public Long getId() {
        return id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getSubmitterAssigneeId() {
        return submitterAssigneeId;
    }

    public void setSubmitterAssigneeId(Long submitterAssigneeId) {
        this.submitterAssigneeId = submitterAssigneeId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }
}
