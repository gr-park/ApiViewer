package com.baek.viewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_assignee_notice")
public class AdminAssigneeNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    /** 관리자가 특정 담당자→관리자 메시지에 대한 답장 */
    @Column(name = "reply_to_assignee_message_id")
    private Long replyToAssigneeMessageId;

    public Long getId() { return id; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(LocalDateTime dismissedAt) { this.dismissedAt = dismissedAt; }
    public Long getReplyToAssigneeMessageId() { return replyToAssigneeMessageId; }
    public void setReplyToAssigneeMessageId(Long replyToAssigneeMessageId) { this.replyToAssigneeMessageId = replyToAssigneeMessageId; }
}
