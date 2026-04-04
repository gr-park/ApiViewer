package com.baek.viewer.model;

import java.util.List;
import java.util.Map;

public class ReposYamlConfig {

    private GlobalSection global;
    private List<RepoEntry> repositories;

    public GlobalSection getGlobal() { return global; }
    public void setGlobal(GlobalSection global) { this.global = global; }
    public List<RepoEntry> getRepositories() { return repositories; }
    public void setRepositories(List<RepoEntry> repositories) { this.repositories = repositories; }

    // ── 공통 설정 ──────────────────────────────────────────
    public static class GlobalSection {
        private PeriodGlobal period;
        private Integer reviewThreshold;
        private String password;
        private String gitBinPath;
        private List<WhatapProfile> whatapProfiles;

        public PeriodGlobal getPeriod() { return period; }
        public void setPeriod(PeriodGlobal period) { this.period = period; }
        public Integer getReviewThreshold() { return reviewThreshold; }
        public void setReviewThreshold(Integer reviewThreshold) { this.reviewThreshold = reviewThreshold; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getGitBinPath() { return gitBinPath; }
        public void setGitBinPath(String gitBinPath) { this.gitBinPath = gitBinPath; }
        public List<WhatapProfile> getWhatapProfiles() { return whatapProfiles; }
        public void setWhatapProfiles(List<WhatapProfile> whatapProfiles) { this.whatapProfiles = whatapProfiles; }
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

    // ── 레포별 설정 ────────────────────────────────────────
    public static class RepoEntry {
        private String repoName;
        private String domain;
        private String rootPath;
        private String gitBinPath;          // null이면 global.gitBinPath 사용
        private String gitPullEnabled = "Y";
        private String teamName;
        private String managerName;
        private String businessName;
        private String apiPathPrefix;
        private String pathConstants;
        private WhatapEntry whatap;

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
        public WhatapEntry getWhatap() { return whatap; }
        public void setWhatap(WhatapEntry whatap) { this.whatap = whatap; }
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
}
