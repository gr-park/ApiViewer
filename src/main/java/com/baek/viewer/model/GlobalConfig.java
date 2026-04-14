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

    /** 로그 조회 시 tail 줄 수 (기본 1000, 0이면 전체) */
    @Column(name = "log_tail_lines")
    private Integer logTailLines = 1000;

    // ── 메일 발송 설정 ──
    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort = 25;

    @Column(name = "smtp_username")
    private String smtpUsername;

    @Column(name = "smtp_password")
    private String smtpPassword;

    @Column(name = "mail_from")
    private String mailFrom;

    @Column(name = "mail_to", columnDefinition = "TEXT")
    private String mailTo;  // 쉼표 구분 수신자 목록

    /** 와탭 조회 ptotal (전체 건수 상한, 기본 100) */
    @Column(name = "whatap_ptotal")
    private Integer whatapPtotal = 100;

    /** 와탭 조회 psize (한 페이지 건수, 기본 10000) */
    @Column(name = "whatap_psize")
    private Integer whatapPsize = 10000;

    /**
     * 현업검토 화면에 표시할 상태 목록 (콤마 구분)
     * 기본값: 3가지 차단대상 모두 표시
     */
    @Column(name = "review_target_statuses", columnDefinition = "TEXT")
    private String reviewTargetStatuses;

    /**
     * 데이터 백업 메타 정보 JSON
     * {"analysis":{"at":"...","count":0},"callHistory":{"at":"...","count":0}}
     */
    @Column(name = "last_backup_meta", columnDefinition = "TEXT")
    private String lastBackupMeta;

    // ── Bitbucket 클론 설정 ──
    /** Bitbucket 서버 URL (예: https://bitbucket.company.com) */
    @Column(name = "bitbucket_url", length = 500)
    private String bitbucketUrl;

    /** Bitbucket Bearer 토큰 */
    @Column(name = "bitbucket_token", columnDefinition = "TEXT")
    private String bitbucketToken;

    /** 레포 목록 조회 시 페이지당 건수 (기본 100) */
    @Column(name = "list_repo_limit")
    private Integer listRepoLimit = 100;

    /** 클론받을 로컬 디렉토리 경로 */
    @Column(name = "clone_local_path", length = 1000)
    private String cloneLocalPath;

    /** git-bash.exe 경로 (Windows 전용, 예: C:\Program Files\Git\git-bash.exe) */
    @Column(name = "git_bash_path", length = 1000)
    private String gitBashPath;


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
    public Integer getLogTailLines() { return logTailLines != null ? logTailLines : 1000; }
    public void setLogTailLines(Integer v) { this.logTailLines = v; }
    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String v) { this.smtpHost = v; }
    public Integer getSmtpPort() { return smtpPort != null ? smtpPort : 25; }
    public void setSmtpPort(Integer v) { this.smtpPort = v; }
    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String v) { this.smtpUsername = v; }
    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String v) { this.smtpPassword = v; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String v) { this.mailFrom = v; }
    public String getMailTo() { return mailTo; }
    public void setMailTo(String v) { this.mailTo = v; }
    public Integer getWhatapPtotal() { return whatapPtotal != null ? whatapPtotal : 100; }
    public void setWhatapPtotal(Integer v) { this.whatapPtotal = v; }
    public Integer getWhatapPsize() { return whatapPsize != null ? whatapPsize : 10000; }
    public void setWhatapPsize(Integer v) { this.whatapPsize = v; }
    public String getReviewTargetStatuses() {
        return reviewTargetStatuses != null ? reviewTargetStatuses
               : "최우선 차단대상,후순위 차단대상,추가검토필요 차단대상";
    }
    public void setReviewTargetStatuses(String v) { this.reviewTargetStatuses = v; }
    public String getLastBackupMeta() { return lastBackupMeta; }
    public void setLastBackupMeta(String v) { this.lastBackupMeta = v; }
    public String getBitbucketUrl() { return bitbucketUrl; }
    public void setBitbucketUrl(String v) { this.bitbucketUrl = v; }
    public String getBitbucketToken() { return bitbucketToken; }
    public void setBitbucketToken(String v) { this.bitbucketToken = v; }
    public Integer getListRepoLimit() { return listRepoLimit != null ? listRepoLimit : 100; }
    public void setListRepoLimit(Integer v) { this.listRepoLimit = v; }
    public String getCloneLocalPath() { return cloneLocalPath; }
    public void setCloneLocalPath(String v) { this.cloneLocalPath = v; }
    public String getGitBashPath() { return gitBashPath; }
    public void setGitBashPath(String v) { this.gitBashPath = v; }
}
