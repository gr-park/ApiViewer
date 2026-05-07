package com.baek.viewer.model;

/**
 * 차단대상 진행사항 대시보드 전용 경량 DTO.
 * 8개 필드만 로드해 그룹별 13컬럼 카운트 집계 (사용/차단/보류 3-tier).
 */
public class BlockOverviewDto {

    private final Long id;
    private final String repositoryName;
    private final String status;
    private final Long callCount;
    private final Boolean logWorkExcluded;
    private final Boolean recentLogOnly;
    private final String reviewResult;
    private final String teamOverride;
    private final String managerOverride;
    private final String deployManager;
    private final String apiPath;

    public BlockOverviewDto(Long id,
                            String repositoryName,
                            String status,
                            Long callCount,
                            Boolean logWorkExcluded,
                            Boolean recentLogOnly,
                            String reviewResult,
                            String teamOverride,
                            String managerOverride,
                            String deployManager,
                            String apiPath) {
        this.id = id;
        this.repositoryName = repositoryName;
        this.status = status;
        this.callCount = callCount;
        this.logWorkExcluded = logWorkExcluded;
        this.recentLogOnly = recentLogOnly;
        this.reviewResult = reviewResult;
        this.teamOverride = teamOverride;
        this.managerOverride = managerOverride;
        this.deployManager = deployManager;
        this.apiPath = apiPath;
    }

    public Long getId() { return id; }
    public String getRepositoryName() { return repositoryName; }
    public String getStatus() { return status; }
    public long getCallCountValue() { return callCount != null ? callCount : 0L; }
    public boolean isLogWorkExcluded() { return Boolean.TRUE.equals(logWorkExcluded); }
    public boolean isRecentLogOnly() { return Boolean.TRUE.equals(recentLogOnly); }
    public String getReviewResult() { return reviewResult; }
    public String getTeamOverride() { return teamOverride; }
    public String getManagerOverride() { return managerOverride; }
    public String getDeployManager() { return deployManager; }
    public String getApiPath() { return apiPath; }
}
