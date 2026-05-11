package com.baek.viewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "assignee_to_admin_message")
public class AssigneeToAdminMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_assignee_id", nullable = false)
    private Long senderAssigneeId;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "admin_dismissed_at")
    private LocalDateTime adminDismissedAt;

    @Column(name = "sender_team_name", length = 200)
    private String senderTeamName;

    @Column(name = "sender_assignee_name", length = 200)
    private String senderAssigneeName;

    /** 담당자가 특정 관리자 쪽지(notice id)에 대한 답장 */
    @Column(name = "reply_to_admin_notice_id")
    private Long replyToAdminNoticeId;

    public Long getId() { return id; }
    public Long getSenderAssigneeId() { return senderAssigneeId; }
    public void setSenderAssigneeId(Long senderAssigneeId) { this.senderAssigneeId = senderAssigneeId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getAdminDismissedAt() { return adminDismissedAt; }
    public void setAdminDismissedAt(LocalDateTime adminDismissedAt) { this.adminDismissedAt = adminDismissedAt; }
    public String getSenderTeamName() { return senderTeamName; }
    public void setSenderTeamName(String senderTeamName) { this.senderTeamName = senderTeamName; }
    public String getSenderAssigneeName() { return senderAssigneeName; }
    public void setSenderAssigneeName(String senderAssigneeName) { this.senderAssigneeName = senderAssigneeName; }
    public Long getReplyToAdminNoticeId() { return replyToAdminNoticeId; }
    public void setReplyToAdminNoticeId(Long replyToAdminNoticeId) { this.replyToAdminNoticeId = replyToAdminNoticeId; }
}
