package com.baek.viewer.model;

import jakarta.persistence.*;

@Entity
@Table(name = "repo_config")
public class RepoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_name", nullable = false, unique = true)
    private String repoName;

    @Column(name = "domain")
    private String domain;

    @Column(name = "root_path")
    private String rootPath;

    @Column(name = "git_bin_path")
    private String gitBinPath;

    /** 분석 시 git pull 수행 여부 (Y/N, 기본 Y) */
    @Column(name = "git_pull_enabled", length = 1)
    private String gitPullEnabled = "Y";

    /** Git 브랜치명 (빈값이면 현재 브랜치 유지, 값 있으면 checkout 후 pull) */
    @Column(name = "git_branch", length = 100)
    private String gitBranch;

    /** 분석 배치 수행 여부 (Y/N, 기본 Y) */
    @Column(name = "analysis_batch_enabled", length = 1)
    private String analysisBatchEnabled = "Y";

    /** APM 수집 배치 수행 여부 (Y/N, 기본 Y) */
    @Column(name = "apm_batch_enabled", length = 1)
    private String apmBatchEnabled = "Y";

    @Column(name = "team_name")
    private String teamName;

    @Column(name = "manager_name")
    private String managerName;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "api_path_prefix")
    private String apiPathPrefix;

    @Column(name = "path_constants", length = 1000)
    private String pathConstants;

    @Column(name = "whatap_enabled", length = 1)
    private String whatapEnabled = "N";

    @Column(name = "whatap_profile_name", length = 50)
    private String whatapProfileName;

    @Column(name = "whatap_url", columnDefinition = "TEXT")
    private String whatapUrl;

    @Column(name = "whatap_pcode")
    private Integer whatapPcode = 8;

    @Column(name = "whatap_filter", length = 500)
    private String whatapFilter;

    @Column(name = "whatap_okinds", length = 500)
    private String whatapOkinds;

    @Column(name = "whatap_okinds_name")
    private String whatapOkindsName;

    @Column(name = "whatap_cookie", columnDefinition = "TEXT")
    private String whatapCookie;

    /** 프로그램ID별 담당자 매핑 JSON — [{"programId":"CARD","managerName":"김철수"}, ...] */
    @Column(name = "manager_mappings", columnDefinition = "TEXT")
    private String managerMappings;

    /** 앱 유형: APP(앱) / WEB(홈페이지) */
    @Column(name = "app_type", length = 20)
    private String appType;

    // ── 제니퍼 설정 ──
    @Column(name = "jennifer_enabled", length = 1)
    private String jenniferEnabled = "N";

    @Column(name = "jennifer_profile_name", length = 50)
    private String jenniferProfileName;

    @Column(name = "jennifer_url", columnDefinition = "TEXT")
    private String jenniferUrl;

    @Column(name = "jennifer_sid")
    private Integer jenniferSid;

    @Column(name = "jennifer_filter", length = 200)
    private String jenniferFilter;

    @Column(name = "jennifer_bearer_token", columnDefinition = "TEXT")
    private String jenniferBearerToken;

    /** 제니퍼 OID 목록 JSON — [{"oid":10021,"shortName":"mall-tiny-api-1"}, ...] */
    @Column(name = "jennifer_oids", columnDefinition = "TEXT")
    private String jenniferOids;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getRootPath() { return rootPath; }
    public void setRootPath(String rootPath) { this.rootPath = rootPath; }
    public String getGitBinPath() { return gitBinPath; }
    public void setGitBinPath(String gitBinPath) { this.gitBinPath = gitBinPath; }
    public String getGitPullEnabled() { return gitPullEnabled; }
    public void setGitPullEnabled(String gitPullEnabled) { this.gitPullEnabled = gitPullEnabled; }
    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
    public String getAnalysisBatchEnabled() { return analysisBatchEnabled != null ? analysisBatchEnabled : "Y"; }
    public void setAnalysisBatchEnabled(String v) { this.analysisBatchEnabled = v; }
    public String getApmBatchEnabled() { return apmBatchEnabled != null ? apmBatchEnabled : "Y"; }
    public void setApmBatchEnabled(String v) { this.apmBatchEnabled = v; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
    public String getManagerName() { return managerName; }
    public void setManagerName(String managerName) { this.managerName = managerName; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public String getApiPathPrefix() { return apiPathPrefix; }
    public void setApiPathPrefix(String apiPathPrefix) { this.apiPathPrefix = apiPathPrefix; }
    public String getPathConstants() { return pathConstants; }
    public void setPathConstants(String pathConstants) { this.pathConstants = pathConstants; }
    public String getWhatapEnabled() { return whatapEnabled; }
    public void setWhatapEnabled(String whatapEnabled) { this.whatapEnabled = whatapEnabled; }
    public String getWhatapProfileName() { return whatapProfileName; }
    public void setWhatapProfileName(String whatapProfileName) { this.whatapProfileName = whatapProfileName; }
    public String getWhatapUrl() { return whatapUrl; }
    public void setWhatapUrl(String whatapUrl) { this.whatapUrl = whatapUrl; }
    public Integer getWhatapPcode() { return whatapPcode; }
    public void setWhatapPcode(Integer whatapPcode) { this.whatapPcode = whatapPcode; }
    public String getWhatapFilter() { return whatapFilter; }
    public void setWhatapFilter(String whatapFilter) { this.whatapFilter = whatapFilter; }
    public String getWhatapOkinds() { return whatapOkinds; }
    public void setWhatapOkinds(String whatapOkinds) { this.whatapOkinds = whatapOkinds; }
    public String getWhatapOkindsName() { return whatapOkindsName; }
    public void setWhatapOkindsName(String whatapOkindsName) { this.whatapOkindsName = whatapOkindsName; }
    public String getWhatapCookie() { return whatapCookie; }
    public void setWhatapCookie(String whatapCookie) { this.whatapCookie = whatapCookie; }
    public String getManagerMappings() { return managerMappings; }
    public void setManagerMappings(String managerMappings) { this.managerMappings = managerMappings; }
    public String getAppType() { return appType; }
    public void setAppType(String appType) { this.appType = appType; }
    public String getJenniferEnabled() { return jenniferEnabled; }
    public void setJenniferEnabled(String jenniferEnabled) { this.jenniferEnabled = jenniferEnabled; }
    public String getJenniferProfileName() { return jenniferProfileName; }
    public void setJenniferProfileName(String jenniferProfileName) { this.jenniferProfileName = jenniferProfileName; }
    public String getJenniferUrl() { return jenniferUrl; }
    public void setJenniferUrl(String jenniferUrl) { this.jenniferUrl = jenniferUrl; }
    public Integer getJenniferSid() { return jenniferSid; }
    public void setJenniferSid(Integer jenniferSid) { this.jenniferSid = jenniferSid; }
    public String getJenniferFilter() { return jenniferFilter; }
    public void setJenniferFilter(String jenniferFilter) { this.jenniferFilter = jenniferFilter; }
    public String getJenniferBearerToken() { return jenniferBearerToken; }
    public void setJenniferBearerToken(String jenniferBearerToken) { this.jenniferBearerToken = jenniferBearerToken; }
    public String getJenniferOids() { return jenniferOids; }
    public void setJenniferOids(String jenniferOids) { this.jenniferOids = jenniferOids; }
}
