package com.baek.viewer.job;

import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.RepoBatchGitSyncService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Git 소스 동기화만 수행하는 배치 (추출·스냅샷 없음).
 * 차단 URL 모니터 등 워킹트리 최신화용.
 */
public class GitPullJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(GitPullJob.class);
    private static final String LOG_PREFIX = "[배치][GIT_PULL]";

    @Autowired private ScheduleConfigRepository scheduleRepo;
    @Autowired private RepoBatchGitSyncService repoBatchGitSync;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("{} Git 소스 동기화 시작", LOG_PREFIX);
        List<RepoConfig> repos = repoBatchGitSync.listAnalysisBatchRepos();
        int success = 0;
        int fail = 0;
        int skipped = 0;
        StringBuilder resultMsg = new StringBuilder();

        for (RepoConfig repo : repos) {
            if (!repoBatchGitSync.isGitPullEnabled(repo)) {
                skipped++;
                continue;
            }
            try {
                RepoBatchGitSyncService.RepoSyncAttempt attempt =
                        repoBatchGitSync.syncRepoIfEnabled(repo, LOG_PREFIX);
                if (attempt.isOk()) {
                    success++;
                } else {
                    fail++;
                    resultMsg.append(repo.getRepoName()).append(":sync실패 ");
                }
            } catch (Exception e) {
                fail++;
                resultMsg.append(repo.getRepoName()).append(":실패 ");
                log.error("{} {} — 예외: {}", LOG_PREFIX, repo.getRepoName(), e.getMessage());
            }
        }

        String result = String.format("동기화 성공 %d개, 실패 %d개, Pull건너뜀 %d개 / 대상 %d개 레포",
                success, fail, skipped, repos.size());
        if (resultMsg.length() > 0) {
            result += " (" + resultMsg.toString().trim() + ")";
        }
        log.info("{} 완료 — {}", LOG_PREFIX, result);
        updateScheduleResult(result);
        context.setResult(Map.of(
                "status", fail == 0 ? "SUCCESS" : (success == 0 ? "FAIL" : "SUCCESS"),
                "count", success,
                "failCount", fail,
                "summary", result));
    }

    private void updateScheduleResult(String result) {
        scheduleRepo.findByJobType("GIT_PULL").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
