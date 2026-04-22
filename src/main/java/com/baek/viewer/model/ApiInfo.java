package com.baek.viewer.model;

public class ApiInfo {
    private String apiPath;
    private String fullUrl;
    private String httpMethod;
    private String methodName;
    private String controllerName;
    private String repoPath;
    private String isDeprecated;
    private String programId;
    private String apiOperationValue;
    private String descriptionTag;
    private String fullComment;
    private String controllerComment;
    private String requestPropertyValue;
    private String controllerRequestPropertyValue;
    private String[] git1 = {"-", "-", "No History"};
    private String[] git2 = {"-", "-", "No History"};
    private String[] git3 = {"-", "-", "No History"};
    private String[] git4 = {"-", "-", "No History"};
    private String[] git5 = {"-", "-", "No History"};
    private Long callCount; // Whatap 호출건수 (null = 미조회)
    private String hasUrlBlock = "N"; // 메소드 첫 줄에 UnsupportedOperationException throw 여부
    private boolean blockMarkingIncomplete; // 실질 차단이지만 @Deprecated 또는 [URL차단작업] 주석 중 일부 누락

    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) {
        this.apiPath = apiPath != null && apiPath.length() > 2000 ? apiPath.substring(0, 2000) : apiPath;
    }

    public String getFullUrl() { return fullUrl; }
    public void setFullUrl(String fullUrl) { this.fullUrl = fullUrl; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getControllerName() { return controllerName; }
    public void setControllerName(String controllerName) { this.controllerName = controllerName; }

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getIsDeprecated() { return isDeprecated; }
    public void setIsDeprecated(String isDeprecated) { this.isDeprecated = isDeprecated; }

    public String getProgramId() { return programId; }
    public void setProgramId(String programId) { this.programId = programId; }

    public String getApiOperationValue() { return apiOperationValue; }
    public void setApiOperationValue(String apiOperationValue) { this.apiOperationValue = apiOperationValue; }

    public String getDescriptionTag() { return descriptionTag; }
    public void setDescriptionTag(String descriptionTag) { this.descriptionTag = descriptionTag; }

    public String getFullComment() { return fullComment; }
    public void setFullComment(String fullComment) { this.fullComment = fullComment; }

    public String getControllerComment() { return controllerComment; }
    public void setControllerComment(String controllerComment) { this.controllerComment = controllerComment; }

    public String getRequestPropertyValue() { return requestPropertyValue; }
    public void setRequestPropertyValue(String requestPropertyValue) { this.requestPropertyValue = requestPropertyValue; }

    public String getControllerRequestPropertyValue() { return controllerRequestPropertyValue; }
    public void setControllerRequestPropertyValue(String v) { this.controllerRequestPropertyValue = v; }

    public String[] getGit1() { return git1; }
    public void setGit1(String[] git1) { this.git1 = git1; }

    public String[] getGit2() { return git2; }
    public void setGit2(String[] git2) { this.git2 = git2; }

    public String[] getGit3() { return git3; }
    public void setGit3(String[] git3) { this.git3 = git3; }

    public String[] getGit4() { return git4; }
    public void setGit4(String[] git4) { this.git4 = git4; }

    public String[] getGit5() { return git5; }
    public void setGit5(String[] git5) { this.git5 = git5; }

    public Long getCallCount() { return callCount; }
    public void setCallCount(Long callCount) { this.callCount = callCount; }
    public String getHasUrlBlock() { return hasUrlBlock; }
    public void setHasUrlBlock(String hasUrlBlock) { this.hasUrlBlock = hasUrlBlock; }
    public boolean isBlockMarkingIncomplete() { return blockMarkingIncomplete; }
    public void setBlockMarkingIncomplete(boolean blockMarkingIncomplete) { this.blockMarkingIncomplete = blockMarkingIncomplete; }
}