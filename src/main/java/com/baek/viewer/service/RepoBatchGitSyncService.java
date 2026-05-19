package com.baek.viewer.service;

import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 분석 배치용 레포 Git 강제 동기화 — {@link com.baek.viewer.job.GitPullJob},
 * {@link com.baek.viewer.job.GitPullExtractJob} 공통.
 */
@Service
public class RepoBatchGitSyncService {

    private static final Logger log = LoggerFactory.getLogger(RepoBatchGitSyncService.class);

    private final RepoConfigRepository repoConfigRepo;
    private final ApiExtractorService extractorService;

    public RepoBatchGitSyncService(RepoConfigRepository repoConfigRepo,
                                   ApiExtractorService extractorService) {
        this.repoConfigRepo = repoConfigRepo;
        this.extractorService = extractorService;
    }

    /** 분석 배치 대상 레포 (analysisBatchEnabled=Y, rootPath 있음). */
    public List<RepoConfig> listAnalysisBatchRepos() {
        List<RepoConfig> out = new ArrayList<>();
        for (RepoConfig repo : repoConfigRepo.findAll()) {
            if (!"Y".equalsIgnoreCase(repo.getAnalysisBatchEnabled())) {
                continue;
            }
            if (repo.getRootPath() == null || repo.getRootPath().isBlank()) {
                log.warn("[배치] {} — rootPath 없음, 건너뜀", repo.getRepoName());
                continue;
            }
            out.add(repo);
        }
        return out;
    }

    public boolean isGitPullEnabled(RepoConfig repo) {
        return !"N".equalsIgnoreCase(repo.getGitPullEnabled());
    }

    /**
     * Git 강제 동기화 1건. gitPullEnabled=N 이면 시도하지 않음.
     *
     * @return null 이면 Pull 건너뜀, non-null 이면 시도 결과(OK/FAIL)
     */
    public RepoSyncAttempt syncRepoIfEnabled(RepoConfig repo, String logPrefix) {
        if (!isGitPullEnabled(repo)) {
            log.info("{} {} — Git Pull 건너뜀 (git_pull_enabled=N)", logPrefix, repo.getRepoName());
            return null;
        }
        String gitBin = resolveGitBin(repo);
        String rootPath = repo.getRootPath();
        File gitDir = new File(rootPath);
        String branch = repo.getGitBranch();
        try {
            log.info("{} {} — Git 강제 동기화 (dir={}, branch={})",
                    logPrefix, repo.getRepoName(), rootPath,
                    (branch == null || branch.isBlank()) ? "(HEAD)" : branch);
            String syncResult = extractorService.hardSyncToOrigin(gitDir, gitBin, branch);
            log.info("{} {} — Git 동기화 완료: {}", logPrefix, repo.getRepoName(), syncResult);
            updateRepoSyncStatus(repo.getRepoName(), "OK", syncResult);
            return new RepoSyncAttempt("OK", syncResult);
        } catch (Exception syncEx) {
            String msg = syncEx.getMessage();
            log.error("{} {} — Git 동기화 실패: {}", logPrefix, repo.getRepoName(), msg);
            updateRepoSyncStatus(repo.getRepoName(), "FAIL", msg);
            return new RepoSyncAttempt("FAIL", msg);
        }
    }

    public String resolveGitBin(RepoConfig repo) {
        return (repo.getGitBinPath() != null && !repo.getGitBinPath().isBlank())
                ? repo.getGitBinPath() : "git";
    }

    public void updateRepoSyncStatus(String repoName, String status, String message) {
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

    public record RepoSyncAttempt(String status, String message) {
        public boolean isOk() {
            return "OK".equalsIgnoreCase(status);
        }
    }
}
