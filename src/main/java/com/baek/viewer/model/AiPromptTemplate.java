package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_prompt_template")
public class AiPromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slug", nullable = false, unique = true, length = 64)
    private String slug;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 설정 화면 「테스트 요청」 마지막 응답 본문 */
    @Column(name = "last_test_reply", columnDefinition = "TEXT")
    private String lastTestReply;

    @Column(name = "last_test_at")
    private LocalDateTime lastTestAt;

    @Column(name = "last_test_error", length = 500)
    private String lastTestError;

    /** URL현황·배치 등 실제 호출 마지막 응답 본문 */
    @Column(name = "last_prod_reply", columnDefinition = "TEXT")
    private String lastProdReply;

    @Column(name = "last_prod_at")
    private LocalDateTime lastProdAt;

    @Column(name = "last_prod_meta", length = 500)
    private String lastProdMeta;

    @Column(name = "last_prod_error", length = 500)
    private String lastProdError;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getLastTestReply() { return lastTestReply; }
    public void setLastTestReply(String lastTestReply) { this.lastTestReply = lastTestReply; }
    public LocalDateTime getLastTestAt() { return lastTestAt; }
    public void setLastTestAt(LocalDateTime lastTestAt) { this.lastTestAt = lastTestAt; }
    public String getLastTestError() { return lastTestError; }
    public void setLastTestError(String lastTestError) { this.lastTestError = lastTestError; }
    public String getLastProdReply() { return lastProdReply; }
    public void setLastProdReply(String lastProdReply) { this.lastProdReply = lastProdReply; }
    public LocalDateTime getLastProdAt() { return lastProdAt; }
    public void setLastProdAt(LocalDateTime lastProdAt) { this.lastProdAt = lastProdAt; }
    public String getLastProdMeta() { return lastProdMeta; }
    public void setLastProdMeta(String lastProdMeta) { this.lastProdMeta = lastProdMeta; }
    public String getLastProdError() { return lastProdError; }
    public void setLastProdError(String lastProdError) { this.lastProdError = lastProdError; }
}
