package com.baek.viewer.model;

import java.time.LocalDate;

/**
 * 배포일자 분포 통계 전용 DTO — 차단완료 + 차단대상(최우선/후순위/추가검토필요) 만 로드.
 * 대시보드 "배포일자 분포" 섹션 집계용.
 */
public class DeployScheduleDto {

    private final Long id;
    private final String repositoryName;
    private final String status;
    private final String teamOverride;
    private final String managerOverride;
    private final String deployManager;
    private final String apiPath;
    private final LocalDate deployScheduledDate;

    public DeployScheduleDto(Long id,
                             String repositoryName,
                             String status,
                             String teamOverride,
                             String managerOverride,
                             String deployManager,
                             String apiPath,
                             LocalDate deployScheduledDate) {
        this.id = id;
        this.repositoryName = repositoryName;
        this.status = status;
        this.teamOverride = teamOverride;
        this.managerOverride = managerOverride;
        this.deployManager = deployManager;
        this.apiPath = apiPath;
        this.deployScheduledDate = deployScheduledDate;
    }

    public Long getId() { return id; }
    public String getRepositoryName() { return repositoryName; }
    public String getStatus() { return status; }
    public String getTeamOverride() { return teamOverride; }
    public String getManagerOverride() { return managerOverride; }
    public String getDeployManager() { return deployManager; }
    public String getApiPath() { return apiPath; }
    public LocalDate getDeployScheduledDate() { return deployScheduledDate; }
}
