package com.baek.viewer.job;

import com.baek.viewer.model.ExtractRequest;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.ApiExtractorService;
import com.baek.viewer.service.SnapshotService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Git Pull 후 전체 레포지토리 추출 배치.
 * 각 레포별로 git pull → 소스 분석 → DB 저장.
 */
public class GitPullExtractJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(GitPullExtractJob.class);
    @Autowired private ScheduleConfigRepository scheduleRepo;
    @Autowired private RepoConfigRepository repoConfigRepo;
    @Autowired private ApiExtractorService extractorService;
    @Autowired private SnapshotService snapshotService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("[배치] Git Pull & 추출 시작");
        List<RepoConfig> repos = repoConfigRepo.findAll();
        int success = 0, fail = 0;
        StringBuilder resultMsg = new StringBuilder();
        List<String> processedRepos = new ArrayList<>();

        for (RepoConfig repo : repos) {
            if (!"Y".equalsIgnoreCase(repo.getAnalysisBatchEnabled())) {
                log.info("[배치] {} — 분석배치 비활성(N), 건너뜀", repo.getRepoName());
                continue;
            }
            if (repo.getRootPath() == null || repo.getRootPath().isBlank()) {
                log.warn("[배치] {} — rootPath 없음, 건너뜀", repo.getRepoName());
                continue;
            }

            try {
                // 1. Git 강제 동기화 (fetch + reset --hard + clean)
                String gitBin = (repo.getGitBinPath() != null && !repo.getGitBinPath().isBlank())
                        ? repo.getGitBinPath() : "git";
                String rootPath = repo.getRootPath();
                java.io.File gitDir = new java.io.File(rootPath);
                String branch = repo.getGitBranch();

                String syncStatus;
                String syncMessage;
                try {
                    log.info("[배치] {} — Git 강제 동기화 실행 (dir={}, branch={})",
                            repo.getRepoName(), rootPath, (branch == null || branch.isBlank()) ? "(HEAD)" : branch);
                    String syncResult = extractorService.hardSyncToOrigin(gitDir, gitBin, branch);
                    syncStatus = "OK";
                    syncMessage = syncResult;
                    log.info("[배치] {} — Git 동기화 완료: {}", repo.getRepoName(), syncResult);
                } catch (Exception syncEx) {
                    syncStatus = "FAIL";
                    syncMessage = syncEx.getMessage();
                    log.error("[배치] {} — Git 동기화 실패: {}", repo.getRepoName(), syncEx.getMessage());
                    resultMsg.append(repo.getRepoName()).append(":sync실패 ");
                }
                updateRepoSyncStatus(repo.getRepoName(), syncStatus, syncMessage);

                // 2. 추출 (동기화 실패해도 현재 파일 기준으로 분석 진행)
                // [정책] 레포별 스냅샷이 매번 생성되지 않도록 skipSnapshot=true. 모든 레포 끝난 뒤 1회만 스냅샷 생성.
                ExtractRequest req = new ExtractRequest();
                req.setRootPath(rootPath);
                req.setRepositoryName(repo.getRepoName());
                req.setDomain(repo.getDomain());
                req.setApiPathPrefix(repo.getApiPathPrefix());
                req.setGitBinPath(gitBin);
                req.setPathConstants(repo.getPathConstants());
                req.setClientIp("BATCH");
                req.setSkipSnapshot(true);

                extractorService.extract(req);
                log.info("[배치] {} — 추출 완료", repo.getRepoName());
                processedRepos.add(repo.getRepoName());
                success++;

            } catch (Exception e) {
                log.error("[배치] {} — 실패: {}", repo.getRepoName(), e.getMessage());
                resultMsg.append(repo.getRepoName()).append(":실패 ");
                fail++;
            }
        }

        // 모든 레포 추출이 끝난 뒤 풀 스냅샷을 1회만 생성 (정책: 시점 기준 전체 스냅샷)
        if (success > 0) {
            try {
                String ts = LocalDateTime.now().toString().replace("T", " ");
                if (ts.length() > 19) ts = ts.substring(0, 19);
                String label = String.format("Batch Extract %d개 레포(성공 %d) @ %s", repos.size(), success, ts);
                String repoNames = processedRepos.stream().collect(Collectors.joining(","));
                snapshotService.createSnapshot("EXTRACT_BATCH", label, repoNames, "BATCH");
                log.info("[배치] 스냅샷 생성 완료 (전체 스냅샷, 라벨=\"{}\")", label);
            } catch (Exception e) {
                log.warn("[배치] 스냅샷 생성 실패 (분석 결과에 영향 없음): {}", e.getMessage());
            }
        } else {
            log.warn("[배치] 모든 레포 추출 실패 — 스냅샷 생성 건너뜀");
        }

        String result = String.format("성공 %d개, 실패 %d개 / 총 %d개 레포", success, fail, repos.size());
        if (resultMsg.length() > 0) result += " (" + resultMsg.toString().trim() + ")";
        log.info("[배치] Git Pull & 추출 완료 — {}", result);
        updateResult(result);
        context.setResult(java.util.Map.of(
                "status", fail == 0 ? "SUCCESS" : (success == 0 ? "FAIL" : "SUCCESS"),
                "count", success,
                "failCount", fail,
                "summary", result));
    }

    /** 레포별 마지막 sync 결과를 repo_config 에 기록한다. 조회 화면 배너에서 참조.
     *  Quartz Job 이라 Spring AOP 기반 @Transactional 이 안정적이지 않으므로 생략.
     *  repoConfigRepo.save 는 Spring Data CrudRepository 가 이미 트랜잭션을 래핑한다. */
    protected void updateRepoSyncStatus(String repoName, String status, String message) {
        try {
            repoConfigRepo.findByRepoName(repoName).ifPresent(rc -> {
                rc.setLastSyncStatus(status);
                rc.setLastSyncAt(LocalDateTime.now());
                String trimmed = message;
                if (trimmed != null && trimmed.length() > 1000) {
                    trimmed = trimmed.substring(0, 1000) + "... (truncated)";
                }
                rc.setLastSyncMessage(trimmed);
                repoConfigRepo.save(rc);
            });
        } catch (Exception e) {
            log.warn("[배치] sync 상태 저장 실패 repo={}: {}", repoName, e.getMessage());
        }
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("GIT_PULL_EXTRACT").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
