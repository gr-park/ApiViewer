package com.baek.viewer.job;

import com.baek.viewer.model.ExtractRequest;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.ApiExtractorService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Git Pull 후 전체 레포지토리 추출 배치.
 * 각 레포별로 git pull → 소스 분석 → DB 저장.
 */
@Component
public class GitPullExtractJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(GitPullExtractJob.class);
    private final ScheduleConfigRepository scheduleRepo;
    private final RepoConfigRepository repoConfigRepo;
    private final ApiExtractorService extractorService;

    public GitPullExtractJob(ScheduleConfigRepository scheduleRepo,
                             RepoConfigRepository repoConfigRepo,
                             ApiExtractorService extractorService) {
        this.scheduleRepo = scheduleRepo;
        this.repoConfigRepo = repoConfigRepo;
        this.extractorService = extractorService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        log.info("[배치] Git Pull & 추출 시작");
        List<RepoConfig> repos = repoConfigRepo.findAll();
        int success = 0, fail = 0;
        StringBuilder resultMsg = new StringBuilder();

        for (RepoConfig repo : repos) {
            if (repo.getRootPath() == null || repo.getRootPath().isBlank()) {
                log.warn("[배치] {} — rootPath 없음, 건너뜀", repo.getRepoName());
                continue;
            }

            try {
                // 1. Git Pull
                String gitBin = (repo.getGitBinPath() != null && !repo.getGitBinPath().isBlank())
                        ? repo.getGitBinPath() : "git";
                String rootPath = repo.getRootPath();
                // rootPath에서 상위 git 디렉토리 추정 (src/main/java 등을 포함할 수 있으므로)
                String gitDir = rootPath;
                log.info("[배치] {} — git pull 실행 (dir={})", repo.getRepoName(), gitDir);

                ProcessBuilder pb = new ProcessBuilder(gitBin, "pull");
                pb.directory(new java.io.File(gitDir));
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) output.append(line).append(" ");
                }
                int exitCode = proc.waitFor();
                log.info("[배치] {} — git pull 완료 (exit={}, output={})", repo.getRepoName(), exitCode, output.toString().trim());

                // 2. 추출
                ExtractRequest req = new ExtractRequest();
                req.setRootPath(rootPath);
                req.setRepositoryName(repo.getRepoName());
                req.setDomain(repo.getDomain());
                req.setApiPathPrefix(repo.getApiPathPrefix());
                req.setGitBinPath(gitBin);
                req.setPathConstants(repo.getPathConstants());
                req.setClientIp("BATCH");

                extractorService.extract(req);
                log.info("[배치] {} — 추출 완료", repo.getRepoName());
                success++;

            } catch (Exception e) {
                log.error("[배치] {} — 실패: {}", repo.getRepoName(), e.getMessage());
                resultMsg.append(repo.getRepoName()).append(":실패 ");
                fail++;
            }
        }

        String result = String.format("성공 %d개, 실패 %d개 / 총 %d개 레포", success, fail, repos.size());
        if (resultMsg.length() > 0) result += " (" + resultMsg.toString().trim() + ")";
        log.info("[배치] Git Pull & 추출 완료 — {}", result);
        updateResult(result);
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("GIT_PULL_EXTRACT").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
