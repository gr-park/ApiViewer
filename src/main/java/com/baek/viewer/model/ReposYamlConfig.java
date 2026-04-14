package com.baek.viewer.model;

import java.util.List;
import java.util.Map;

public class ReposYamlConfig {

    private GlobalSection global;
    private List<RepoEntry> repositories;
    private JiraSection jira;

    public GlobalSection getGlobal() { return global; }
    public void setGlobal(GlobalSection global) { this.global = global; }
    public List<RepoEntry> getRepositories() { return repositories; }
    public void setRepositories(List<RepoEntry> repositories) { this.repositories = repositories; }
    public JiraSection getJira() { return jira; }
    public void setJira(JiraSection jira) { this.jira = jira; }

    // ── 공통 설정 ──────────────────────────────────────────
    public static class GlobalSection {
        private PeriodGlobal period;
        private Integer reviewThreshold;
        private String password;
        private String gitBinPath;
        private Integer pageSize;
        private Integer pageNavSize;
        private List<String> teams;
        private String apmLogLevel;
        private String smtpHost;
        private Integer smtpPort;
        private String smtpUsername;
        private String smtpPassword;
        private String mailFrom;
        private String mailTo;
        private Integer logTailLines;
        private Integer whatapPtotal;
        private Integer whatapPsize;
        private List<WhatapProfile> whatapProfiles;
        private List<JenniferProfile> jenniferProfiles;
        private String bitbucketUrl;
        private String bitbucketToken;
        private Integer listRepoLimit;
        private String cloneLocalPath;
        private String gitBashPath;

        public PeriodGlobal getPeriod() { return period; }
        public void setPeriod(PeriodGlobal period) { this.period = period; }
        public Integer getReviewThreshold() { return reviewThreshold; }
        public void setReviewThreshold(Integer reviewThreshold) { this.reviewThreshold = reviewThreshold; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getGitBinPath() { return gitBinPath; }
        public void setGitBinPath(String gitBinPath) { this.gitBinPath = gitBinPath; }
        public Integer getPageSize() { return pageSize; }
        public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
        public Integer getPageNavSize() { return pageNavSize; }
        public void setPageNavSize(Integer pageNavSize) { this.pageNavSize = pageNavSize; }
        public List<String> getTeams() { return teams; }
        public void setTeams(List<String> teams) { this.teams = teams; }
        public String getApmLogLevel() { return apmLogLevel; }
        public void setApmLogLevel(String apmLogLevel) { this.apmLogLevel = apmLogLevel; }
        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String v) { this.smtpHost = v; }
        public Integer getSmtpPort() { return smtpPort; }
        public void setSmtpPort(Integer v) { this.smtpPort = v; }
        public String getSmtpUsername() { return smtpUsername; }
        public void setSmtpUsername(String v) { this.smtpUsername = v; }
        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String v) { this.smtpPassword = v; }
        public String getMailFrom() { return mailFrom; }
        public void setMailFrom(String v) { this.mailFrom = v; }
        public String getMailTo() { return mailTo; }
        public void setMailTo(String v) { this.mailTo = v; }
        public Integer getLogTailLines() { return logTailLines; }
        public void setLogTailLines(Integer v) { this.logTailLines = v; }
        public Integer getWhatapPtotal() { return whatapPtotal; }
        public void setWhatapPtotal(Integer v) { this.whatapPtotal = v; }
        public Integer getWhatapPsize() { return whatapPsize; }
        public void setWhatapPsize(Integer v) { this.whatapPsize = v; }
        public List<WhatapProfile> getWhatapProfiles() { return whatapProfiles; }
        public void setWhatapProfiles(List<WhatapProfile> whatapProfiles) { this.whatapProfiles = whatapProfiles; }
        public List<JenniferProfile> getJenniferProfiles() { return jenniferProfiles; }
        public void setJenniferProfiles(List<JenniferProfile> jenniferProfiles) { this.jenniferProfiles = jenniferProfiles; }
        public String getBitbucketUrl() { return bitbucketUrl; }
        public void setBitbucketUrl(String v) { this.bitbucketUrl = v; }
        public String getBitbucketToken() { return bitbucketToken; }
        public void setBitbucketToken(String v) { this.bitbucketToken = v; }
        public Integer getListRepoLimit() { return listRepoLimit; }
        public void setListRepoLimit(Integer v) { this.listRepoLimit = v; }
        public String getCloneLocalPath() { return cloneLocalPath; }
        public void setCloneLocalPath(String v) { this.cloneLocalPath = v; }
        public String getGitBashPath() { return gitBashPath; }
        public void setGitBashPath(String v) { this.gitBashPath = v; }
    }

    public static class PeriodGlobal {
        private String startDate;
        private String endDate;
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
    }

    /** 와탭 공통 프로필 (URL/쿠키 공유) */
    public static class WhatapProfile {
        private String name;
        private String url;
        private String cookie;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getCookie() { return cookie; }
        public void setCookie(String cookie) { this.cookie = cookie; }
    }

    /** 제니퍼 공통 프로필 (URL/Bearer토큰 공유) */
    public static class JenniferProfile {
        private String name;
        private String url;
        private String bearerToken;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
    }

    // ── Jira 연동 설정 ──────────────────────────────────────
    public static class JiraSection {
        private String baseUrl;
        private String projectKey;
        private String serviceAccount;
        private String apiToken;
        private String customFieldId;
        private List<UserMappingEntry> userMappings;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
        public String getServiceAccount() { return serviceAccount; }
        public void setServiceAccount(String serviceAccount) { this.serviceAccount = serviceAccount; }
        public String getApiToken() { return apiToken; }
        public void setApiToken(String apiToken) { this.apiToken = apiToken; }
        public String getCustomFieldId() { return customFieldId; }
        public void setCustomFieldId(String customFieldId) { this.customFieldId = customFieldId; }
        public List<UserMappingEntry> getUserMappings() { return userMappings; }
        public void setUserMappings(List<UserMappingEntry> userMappings) { this.userMappings = userMappings; }
    }

    public static class UserMappingEntry {
        private String team;
        private String name;
        private String jiraAccountId;
        private String jiraDisplayName;

        public String getTeam() { return team; }
        public void setTeam(String team) { this.team = team; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getJiraAccountId() { return jiraAccountId; }
        public void setJiraAccountId(String jiraAccountId) { this.jiraAccountId = jiraAccountId; }
        public String getJiraDisplayName() { return jiraDisplayName; }
        public void setJiraDisplayName(String jiraDisplayName) { this.jiraDisplayName = jiraDisplayName; }
    }

    /** 프로그램ID별 담당자 매핑 */
    public static class ManagerMapping {
        private String programId;
        private String managerName;

        public String getProgramId() { return programId; }
        public void setProgramId(String programId) { this.programId = programId; }
        public String getManagerName() { return managerName; }
        public void setManagerName(String managerName) { this.managerName = managerName; }
    }

    // ── 레포별 설정 ────────────────────────────────────────
    public static class RepoEntry {
        private String repoName;
        private String domain;
        private String rootPath;
        private String gitBinPath;          // null이면 global.gitBinPath 사용
        private String gitBranch;           // null/빈값이면 현재 브랜치 유지
        private String gitPullEnabled = "Y";
        private String analysisBatchEnabled = "Y";
        private String apmBatchEnabled = "Y";
        private String teamName;
        private String managerName;
        private String businessName;
        private List<ManagerMapping> managerMappings;
        private String apiPathPrefix;
        private String pathConstants;
        private WhatapEntry whatap;
        private JenniferEntry jennifer;

        public String getRepoName() { return repoName; }
        public void setRepoName(String repoName) { this.repoName = repoName; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getRootPath() { return rootPath; }
        public void setRootPath(String rootPath) { this.rootPath = rootPath; }
        public String getGitBinPath() { return gitBinPath; }
        public void setGitBinPath(String gitBinPath) { this.gitBinPath = gitBinPath; }
        public String getGitBranch() { return gitBranch; }
        public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
        public String getGitPullEnabled() { return gitPullEnabled; }
        public void setGitPullEnabled(String gitPullEnabled) { this.gitPullEnabled = gitPullEnabled; }
        public String getAnalysisBatchEnabled() { return analysisBatchEnabled; }
        public void setAnalysisBatchEnabled(String v) { this.analysisBatchEnabled = v; }
        public String getApmBatchEnabled() { return apmBatchEnabled; }
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
        public List<ManagerMapping> getManagerMappings() { return managerMappings; }
        public void setManagerMappings(List<ManagerMapping> managerMappings) { this.managerMappings = managerMappings; }
        public WhatapEntry getWhatap() { return whatap; }
        public void setWhatap(WhatapEntry whatap) { this.whatap = whatap; }
        public JenniferEntry getJennifer() { return jennifer; }
        public void setJennifer(JenniferEntry jennifer) { this.jennifer = jennifer; }
    }

    public static class WhatapEntry {
        private String enabled = "N";
        private String profileName;         // 공통 프로필 참조명
        private String url;                 // 개별 지정 시 오버라이드
        private Integer pcode = 8;
        private String filter;
        private String okinds;
        private String okindsName;
        private String cookie;              // 개별 지정 시 오버라이드

        public String getEnabled() { return enabled; }
        public void setEnabled(String enabled) { this.enabled = enabled; }
        public String getProfileName() { return profileName; }
        public void setProfileName(String profileName) { this.profileName = profileName; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Integer getPcode() { return pcode; }
        public void setPcode(Integer pcode) { this.pcode = pcode; }
        public String getFilter() { return filter; }
        public void setFilter(String filter) { this.filter = filter; }
        public String getOkinds() { return okinds; }
        public void setOkinds(String okinds) { this.okinds = okinds; }
        public String getOkindsName() { return okindsName; }
        public void setOkindsName(String okindsName) { this.okindsName = okindsName; }
        public String getCookie() { return cookie; }
        public void setCookie(String cookie) { this.cookie = cookie; }
    }

    public static class JenniferEntry {
        private String enabled = "N";
        private String profileName;
        private String url;
        private Integer sid;
        private String filter;
        private String bearerToken;
        private List<OidEntry> oids;

        public String getEnabled() { return enabled; }
        public void setEnabled(String enabled) { this.enabled = enabled; }
        public String getProfileName() { return profileName; }
        public void setProfileName(String profileName) { this.profileName = profileName; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Integer getSid() { return sid; }
        public void setSid(Integer sid) { this.sid = sid; }
        public String getFilter() { return filter; }
        public void setFilter(String filter) { this.filter = filter; }
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
        public List<OidEntry> getOids() { return oids; }
        public void setOids(List<OidEntry> oids) { this.oids = oids; }
    }

    /** 제니퍼 OID (sid 하위 인스턴스 단위) */
    public static class OidEntry {
        private Integer oid;
        private String shortName;

        public Integer getOid() { return oid; }
        public void setOid(Integer oid) { this.oid = oid; }
        public String getShortName() { return shortName; }
        public void setShortName(String shortName) { this.shortName = shortName; }
    }
}
