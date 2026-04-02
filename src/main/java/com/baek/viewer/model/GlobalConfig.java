package com.baek.viewer.model;

import jakarta.persistence.*;

@Entity
@Table(name = "global_config")
public class GlobalConfig {

    @Id
    private Long id = 1L; // 단일 레코드

    @Column(name = "start_date", length = 20)
    private String startDate;

    @Column(name = "end_date", length = 20)
    private String endDate;

    @Column(name = "review_threshold")
    private Integer reviewThreshold = 3;

    @Column(name = "password", length = 100)
    private String password;

    public Long getId() { return id; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public Integer getReviewThreshold() { return reviewThreshold != null ? reviewThreshold : 3; }
    public void setReviewThreshold(Integer reviewThreshold) { this.reviewThreshold = reviewThreshold; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
