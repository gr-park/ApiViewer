package com.baek.viewer.job;

import com.baek.viewer.model.ExtractRequest;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.ApiExtractorService;
import com.baek.viewer.service.RepoBatchGitSyncService;
import com.baek.viewer.service.SnapshotService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Git Pull 후 전체 레포지토리 추출 배치.
 * 각 레포별로 git pull(설정 시) → 소스 분석 → DB 저장.
 */
public class GitPullExtractJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(GitPullExtractJob.class);
    private static final String LOG_PREFIX = "[배치][GIT_PULL_EXTRACT]";

    @Autowired private ScheduleConfigRepository scheduleRepo;
    @Autowired private RepoBatchGitSyncService repoBatchGitSync;
    @Autowired private ApiExtractorService extractorService;
    @Autowired private SnapshotService snapshotService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("{} Git Pull & 추출 시작", LOG_PREFIX);
        List<RepoConfig> repos = repoBatchGitSync.listAnalysisBatchRepos();
        int success = 0;
        int fail = 0;
        StringBuilder resultMsg = new StringBuilder();
        List<String> processedRepos = new ArrayList<>();

        for (RepoConfig repo : repos) {
            try {
                String gitBin = repoBatchGitSync.resolveGitBin(repo);
                String rootPath = repo.getRootPath();

                RepoBatchGitSyncService.RepoSyncAttempt syncAttempt =
                        repoBatchGitSync.syncRepoIfEnabled(repo, LOG_PREFIX);
                if (syncAttempt != null && !syncAttempt.isOk()) {
                    resultMsg.append(repo.getRepoName()).append(":sync실패 ");
                }

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
                log.info("{} {} — 추출 완료", LOG_PREFIX, repo.getRepoName());
                processedRepos.add(repo.getRepoName());
                success++;

            } catch (Exception e) {
                log.error("{} {} — 실패: {}", LOG_PREFIX, repo.getRepoName(), e.getMessage());
                resultMsg.append(repo.getRepoName()).append(":실패 ");
                fail++;
            }
        }

        if (success > 0) {
            try {
                String ts = LocalDateTime.now().toString().replace("T", " ");
                if (ts.length() > 19) {
                    ts = ts.substring(0, 19);
                }
                String label = String.format("Batch Extract %d개 레포(성공 %d) @ %s", repos.size(), success, ts);
                String repoNames = processedRepos.stream().collect(Collectors.joining(","));
                snapshotService.createSnapshot("EXTRACT_BATCH", label, repoNames, "BATCH");
                log.info("{} 스냅샷 생성 완료 (라벨=\"{}\")", LOG_PREFIX, label);
            } catch (Exception e) {
                log.warn("{} 스냅샷 생성 실패: {}", LOG_PREFIX, e.getMessage());
            }
        } else {
            log.warn("{} 모든 레포 추출 실패 — 스냅샷 생성 건너뜀", LOG_PREFIX);
        }

        String result = String.format("성공 %d개, 실패 %d개 / 총 %d개 레포", success, fail, repos.size());
        if (resultMsg.length() > 0) {
            result += " (" + resultMsg.toString().trim() + ")";
        }
        log.info("{} 완료 — {}", LOG_PREFIX, result);
        updateResult(result);
        context.setResult(Map.of(
                "status", fail == 0 ? "SUCCESS" : (success == 0 ? "FAIL" : "SUCCESS"),
                "count", success,
                "failCount", fail,
                "summary", result));
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("GIT_PULL_EXTRACT").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
