package com.baek.viewer.model;

public class ExtractRequest {
    private String rootPath;
    private String repositoryName;
    private String domain;
    private String apiPathPrefix;
    private String pathConstants;
    private String gitBinPath;
    private String clientIp; // 서버에서 세팅

    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }

    public String getRootPath() { return rootPath; }
    public void setRootPath(String rootPath) { this.rootPath = rootPath; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getApiPathPrefix() { return apiPathPrefix; }
    public void setApiPathPrefix(String apiPathPrefix) { this.apiPathPrefix = apiPathPrefix; }

    public String getPathConstants() { return pathConstants; }
    public void setPathConstants(String pathConstants) { this.pathConstants = pathConstants; }

    public String getGitBinPath() { return gitBinPath; }
    public void setGitBinPath(String gitBinPath) { this.gitBinPath = gitBinPath; }
}