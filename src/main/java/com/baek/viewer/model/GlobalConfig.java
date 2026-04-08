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

    /** 페이지당 표시 건수 (기본 200) */
    @Column(name = "page_size")
    private Integer pageSize = 200;

    /** 페이지 네비게이션 표시 개수 (기본 10) */
    @Column(name = "page_nav_size")
    private Integer pageNavSize = 10;

    /** 팀 목록 JSON: ["IT카드개발팀","IT커머스개발팀"] — 자동완성용 */
    @Column(name = "teams", columnDefinition = "TEXT")
    private String teams;

    /** 와탭 공통 프로필 JSON: [{"name":"운영","url":"...","cookie":"..."}] */
    @Column(name = "whatap_profiles", columnDefinition = "TEXT")
    private String whatapProfiles;

    /** 제니퍼 공통 프로필 JSON: [{"name":"운영","url":"...","bearerToken":"..."}] */
    @Column(name = "jennifer_profiles", columnDefinition = "TEXT")
    private String jenniferProfiles;

    /** Whatap Mock 사용 여부 (Y/N) */
    @Column(name = "whatap_mock_enabled", length = 5)
    private String whatapMockEnabled = "N";

    /** Jennifer Mock 사용 여부 (Y/N) */
    @Column(name = "jennifer_mock_enabled", length = 5)
    private String jenniferMockEnabled = "N";

    /** 시스템 로그 레벨 (INFO / DEBUG) — DEBUG 시 파싱 과정·APM 요청/응답 전문 기록 */
    @Column(name = "apm_log_level", length = 10)
    private String apmLogLevel = "INFO";

    /** 와탭 조회 ptotal (전체 건수 상한, 기본 100) */
    @Column(name = "whatap_ptotal")
    private Integer whatapPtotal = 100;

    /** 와탭 조회 psize (한 페이지 건수, 기본 10000) */
    @Column(name = "whatap_psize")
    private Integer whatapPsize = 10000;


    public Long getId() { return id; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public Integer getReviewThreshold() { return reviewThreshold != null ? reviewThreshold : 3; }
    public void setReviewThreshold(Integer reviewThreshold) { this.reviewThreshold = reviewThreshold; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Integer getPageSize() { return pageSize != null ? pageSize : 200; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public Integer getPageNavSize() { return pageNavSize != null ? pageNavSize : 10; }
    public void setPageNavSize(Integer pageNavSize) { this.pageNavSize = pageNavSize; }
    public String getTeams() { return teams; }
    public void setTeams(String teams) { this.teams = teams; }
    public String getWhatapProfiles() { return whatapProfiles; }
    public void setWhatapProfiles(String whatapProfiles) { this.whatapProfiles = whatapProfiles; }
    public String getJenniferProfiles() { return jenniferProfiles; }
    public void setJenniferProfiles(String jenniferProfiles) { this.jenniferProfiles = jenniferProfiles; }
    public String getWhatapMockEnabled() { return whatapMockEnabled != null ? whatapMockEnabled : "N"; }
    public void setWhatapMockEnabled(String v) { this.whatapMockEnabled = v; }
    public boolean isWhatapMockEnabled() { return "Y".equalsIgnoreCase(getWhatapMockEnabled()); }
    public String getJenniferMockEnabled() { return jenniferMockEnabled != null ? jenniferMockEnabled : "N"; }
    public void setJenniferMockEnabled(String v) { this.jenniferMockEnabled = v; }
    public boolean isJenniferMockEnabled() { return "Y".equalsIgnoreCase(getJenniferMockEnabled()); }
    public String getApmLogLevel() { return apmLogLevel != null ? apmLogLevel : "INFO"; }
    public void setApmLogLevel(String v) { this.apmLogLevel = v; }
    public boolean isApmDebug() { return "DEBUG".equalsIgnoreCase(getApmLogLevel()); }
    public Integer getWhatapPtotal() { return whatapPtotal != null ? whatapPtotal : 100; }
    public void setWhatapPtotal(Integer v) { this.whatapPtotal = v; }
    public Integer getWhatapPsize() { return whatapPsize != null ? whatapPsize : 10000; }
    public void setWhatapPsize(Integer v) { this.whatapPsize = v; }
}
