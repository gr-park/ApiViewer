package com.baek.viewer.job;

import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.model.ScheduleConfig;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.ApiExtractorService;
import com.baek.viewer.service.RepoBatchGitSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitPullJobTest {

    @Mock private ScheduleConfigRepository scheduleRepo;
    @Mock private RepoConfigRepository repoConfigRepo;
    @Mock private ApiExtractorService extractorService;
    @Mock private JobExecutionContext context;

    private RepoBatchGitSyncService repoBatchGitSync;
    private GitPullJob job;

    @BeforeEach
    void setUp() {
        repoBatchGitSync = new RepoBatchGitSyncService(repoConfigRepo, extractorService);
        job = new GitPullJob();
        ReflectionTestUtils.setField(job, "scheduleRepo", scheduleRepo);
        ReflectionTestUtils.setField(job, "repoBatchGitSync", repoBatchGitSync);
    }

    @Test
    @DisplayName("execute — sync만 수행, extract 미호출")
    void execute_syncOnly_noExtract() throws Exception {
        RepoConfig repo = new RepoConfig();
        repo.setRepoName("demo");
        repo.setRootPath("/tmp/demo");
        repo.setAnalysisBatchEnabled("Y");
        repo.setGitPullEnabled("Y");

        when(repoConfigRepo.findAll()).thenReturn(List.of(repo));
        when(extractorService.hardSyncToOrigin(any(), any(), any())).thenReturn("ok");
        when(scheduleRepo.findByJobType("GIT_PULL")).thenReturn(Optional.of(new ScheduleConfig()));

        job.execute(context);

        verify(extractorService, times(1)).hardSyncToOrigin(any(), any(), any());
        verify(extractorService, never()).extract(any());

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(context).setResult(cap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) cap.getValue();
        assertThat(result.get("count")).isEqualTo(1);
        assertThat(result.get("failCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("execute — git_pull_enabled=N 이면 hardSync 건너뜀")
    void execute_gitPullDisabled_skipsSync() throws Exception {
        RepoConfig repo = new RepoConfig();
        repo.setRepoName("demo");
        repo.setRootPath("/tmp/demo");
        repo.setAnalysisBatchEnabled("Y");
        repo.setGitPullEnabled("N");

        when(repoConfigRepo.findAll()).thenReturn(List.of(repo));
        when(scheduleRepo.findByJobType("GIT_PULL")).thenReturn(Optional.empty());

        job.execute(context);

        verify(extractorService, never()).hardSyncToOrigin(any(), any(), any());
        verify(extractorService, never()).extract(any());
    }
}
