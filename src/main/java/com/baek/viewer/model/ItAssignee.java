package com.baek.viewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "it_assignee",
        uniqueConstraints = @UniqueConstraint(name = "uk_it_assignee_team_name", columnNames = {"team_name", "assignee_name"}))
public class ItAssignee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_name", nullable = false, length = 100)
    private String teamName;

    @Column(name = "assignee_name", nullable = false, length = 100)
    private String assigneeName;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 마지막 제안 반려 알림 (담당자 로그인 시 노출 후 해제 가능) */
    @Column(name = "proposal_reject_notice", columnDefinition = "TEXT")
    private String proposalRejectNotice;

    @Column(name = "proposal_reject_notice_at")
    private LocalDateTime proposalRejectNoticeAt;

    public Long getId() {
        return id;
    }

    public String getTeamName() {
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    public String getAssigneeName() {
        return assigneeName;
    }

    public void setAssigneeName(String assigneeName) {
        this.assigneeName = assigneeName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getProposalRejectNotice() {
        return proposalRejectNotice;
    }

    public void setProposalRejectNotice(String proposalRejectNotice) {
        this.proposalRejectNotice = proposalRejectNotice;
    }

    public LocalDateTime getProposalRejectNoticeAt() {
        return proposalRejectNoticeAt;
    }

    public void setProposalRejectNoticeAt(LocalDateTime proposalRejectNoticeAt) {
        this.proposalRejectNoticeAt = proposalRejectNoticeAt;
    }
}
