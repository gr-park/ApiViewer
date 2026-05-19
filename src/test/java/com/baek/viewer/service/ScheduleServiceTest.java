package com.baek.viewer.service;

import com.baek.viewer.model.ScheduleConfig;
import com.baek.viewer.repository.ScheduleConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ScheduleService 단위테스트.
 * Quartz Scheduler mock + Repository mock.
 * init()은 @PostConstruct 이므로 직접 호출하지 않음 — ensureAndApplyDefaults로 갈음.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private Scheduler scheduler;

    @Mock
    private ScheduleConfigRepository repository;

    @InjectMocks
    private ScheduleService service;

    @Test
    @DisplayName("applySchedule — enabled=false면 기존 JobKey 삭제만 하고 scheduleJob 호출 안 함")
    void applySchedule_disabled_deletesOnly() throws Exception {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("GIT_PULL_EXTRACT");
        cfg.setEnabled(false);
        cfg.setScheduleType("DAILY");
        cfg.setRunTime("03:00");

        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

        service.applySchedule(cfg);

        verify(scheduler, times(1)).deleteJob(any(JobKey.class));
        verify(scheduler, never()).scheduleJob(any(), any());
    }

    @Test
    @DisplayName("applySchedule — enabled=true 면 scheduleJob 으로 등록")
    void applySchedule_enabled_schedulesJob() throws Exception {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("GIT_PULL_EXTRACT");
        cfg.setEnabled(true);
        cfg.setScheduleType("DAILY");
        cfg.setRunTime("03:00");

        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        service.applySchedule(cfg);

        verify(scheduler, times(1)).scheduleJob(any(), any());
    }

    @Test
    @DisplayName("applySchedule — 알 수 없는 jobType 이면 아무 것도 등록하지 않음")
    void applySchedule_unknownJobType_noSchedule() throws Exception {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("UNKNOWN_JOB");
        cfg.setEnabled(true);
        cfg.setScheduleType("DAILY");
        cfg.setRunTime("03:00");

        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        service.applySchedule(cfg);

        verify(scheduler, never()).scheduleJob(any(), any());
    }

    @Test
    @DisplayName("saveAndApply — repository.save 호출 후 applySchedule 수행")
    void saveAndApply_savesThenApplies() throws Exception {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("APM_COLLECT");
        cfg.setEnabled(false);
        cfg.setScheduleType("DAILY");
        cfg.setRunTime("06:00");

        when(repository.save(any(ScheduleConfig.class))).thenReturn(cfg);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        ScheduleConfig result = service.saveAndApply(cfg);

        assertThat(result).isSameAs(cfg);
        verify(repository, times(1)).save(cfg);
    }

    @Test
    @DisplayName("findAll — repository.findAll 위임")
    void findAll_delegatesToRepository() {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("APM_COLLECT");
        when(repository.findAll()).thenReturn(List.of(cfg));

        List<ScheduleConfig> list = service.findAll();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getJobType()).isEqualTo("APM_COLLECT");
    }

    @Test
    @DisplayName("ensureAndApplyDefaults — 기본 설정이 없으면 create 호출")
    void ensureAndApplyDefaults_createsDefaults() {
        // 아무 것도 없는 상태
        when(repository.findByJobType(any())).thenReturn(Optional.empty());
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any(ScheduleConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        service.ensureAndApplyDefaults();

        // GIT_PULL_EXTRACT, DB_SNAPSHOT, APM_COLLECT 총 3건 save
        ArgumentCaptor<ScheduleConfig> captor = ArgumentCaptor.forClass(ScheduleConfig.class);
        verify(repository, atLeast(3)).save(captor.capture());
        List<String> jobTypes = captor.getAllValues().stream().map(ScheduleConfig::getJobType).toList();
        assertThat(jobTypes).contains("GIT_PULL_EXTRACT", "DB_SNAPSHOT", "APM_COLLECT");
    }

    @Test
    @DisplayName("triggerJobOnce — Quartz 에 Job 이 있으면 triggerJob 만 호출")
    void triggerJobOnce_registered_triggers() throws Exception {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("GIT_PULL_EXTRACT");
        cfg.setJobParam("7");
        when(scheduler.checkExists(new JobKey("GIT_PULL_EXTRACT"))).thenReturn(true);

        service.triggerJobOnce(cfg);

        verify(scheduler, times(1)).triggerJob(eq(new JobKey("GIT_PULL_EXTRACT")), any());
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("triggerJobOnce — Quartz 에 Job 이 없으면 일회성 scheduleJob")
    void triggerJobOnce_notRegistered_schedulesEphemeral() throws Exception {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("GIT_PULL_EXTRACT");
        when(scheduler.checkExists(new JobKey("GIT_PULL_EXTRACT"))).thenReturn(false);

        service.triggerJobOnce(cfg);

        verify(scheduler, never()).triggerJob(any(JobKey.class), any());
        verify(scheduler, times(1)).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    @DisplayName("triggerJobOnce — 알 수 없는 jobType 이면 IllegalArgumentException")
    void triggerJobOnce_unknown_throws() {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setJobType("UNKNOWN_X");

        assertThatThrownBy(() -> service.triggerJobOnce(cfg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 jobType");
        verifyNoInteractions(scheduler);
    }
}
