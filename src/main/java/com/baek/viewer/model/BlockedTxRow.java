package com.baek.viewer.model;

/**
 * URL 차단 모니터링 — 와탭/Jennifer 응답 한 행을 화면에 표시할 형태로 매핑한 DTO.
 * DB 저장 X.
 */
public class BlockedTxRow {

    /** APM 소스 구분: "WHATAP" | "JENNIFER" */
    private String source;

    // ── 공통 ──
    private String repoName;
    private String endtime;       // YYYY-MM-DD HH:mm:ss (KST) or raw time string
    private boolean bot;          // 봇 키워드 매치 여부

    // ── 와탭 전용 ──
    private String okindName;
    private String service;
    private String method;
    private String domain;
    private String podName;
    private String country;
    private String clientType;
    private String clientName;
    private String clientOs;
    private String oName;
    private String errClass;
    private String errMessage;
    private String ipAddr;
    private String userAgent;

    // ── Jennifer 전용 ──
    private String applicationName;
    private String domainId;       // domainId (정수를 문자열로 보관)
    private String domainName;
    private String instanceId;     // instanceId (정수를 문자열로 보관)
    private String instanceName;
    private String errorType;
    private String message;        // "차단" 포함 여부로 필터링하는 메시지 필드

    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getRepoName() { return repoName; }
    public void setRepoName(String v) { this.repoName = v; }
    public String getEndtime() { return endtime; }
    public void setEndtime(String v) { this.endtime = v; }
    public boolean isBot() { return bot; }
    public void setBot(boolean v) { this.bot = v; }
    public String getOkindName() { return okindName; }
    public void setOkindName(String v) { this.okindName = v; }
    public String getService() { return service; }
    public void setService(String v) { this.service = v; }
    public String getMethod() { return method; }
    public void setMethod(String v) { this.method = v; }
    public String getDomain() { return domain; }
    public void setDomain(String v) { this.domain = v; }
    public String getPodName() { return podName; }
    public void setPodName(String v) { this.podName = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getClientType() { return clientType; }
    public void setClientType(String v) { this.clientType = v; }
    public String getClientName() { return clientName; }
    public void setClientName(String v) { this.clientName = v; }
    public String getClientOs() { return clientOs; }
    public void setClientOs(String v) { this.clientOs = v; }
    public String getOName() { return oName; }
    public void setOName(String v) { this.oName = v; }
    public String getErrClass() { return errClass; }
    public void setErrClass(String v) { this.errClass = v; }
    public String getErrMessage() { return errMessage; }
    public void setErrMessage(String v) { this.errMessage = v; }
    public String getIpAddr() { return ipAddr; }
    public void setIpAddr(String v) { this.ipAddr = v; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String v) { this.userAgent = v; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String v) { this.applicationName = v; }
    public String getDomainId() { return domainId; }
    public void setDomainId(String v) { this.domainId = v; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String v) { this.domainName = v; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String v) { this.instanceId = v; }
    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String v) { this.instanceName = v; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String v) { this.errorType = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
}
