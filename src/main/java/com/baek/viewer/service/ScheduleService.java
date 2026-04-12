package com.baek.viewer.service;

import com.baek.viewer.job.ApmCollectJob;
import com.baek.viewer.job.DataBackupJob;
import com.baek.viewer.job.DbSnapshotJob;
import com.baek.viewer.job.GitPullExtractJob;
import com.baek.viewer.job.JiraSyncJob;
import com.baek.viewer.model.ScheduleConfig;
import com.baek.viewer.repository.ScheduleConfigRepository;
import jakarta.annotation.PostConstruct;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final Scheduler scheduler;
    private final ScheduleConfigRepository repository;

    public ScheduleService(Scheduler scheduler, ScheduleConfigRepository repository) {
        this.scheduler = scheduler;
        this.repository = repository;
    }

    /** 기동 시 기본 스케줄 등록 + DB에서 복원 */
    @PostConstruct
    public void init() {
        ensureDefaultConfigs();
        repository.findAll().forEach(this::applySchedule);
    }

    /** 외부에서 기본값 재생성 호출용 */
    public void ensureAndApplyDefaults() {
        ensureDefaultConfigs();
        repository.findAll().forEach(this::applySchedule);
    }

    /** 기본 스케줄 설정이 없으면 생성 + 구 APM_DAILY/APM_WEEKLY → APM_COLLECT 통합 */
    private void ensureDefaultConfigs() {
        createIfAbsent("GIT_PULL_EXTRACT", "Git Pull & 소스 분석", "DAILY", "03:00", null);
        createIfAbsentEnabled("DB_SNAPSHOT", "DB 파일 사이즈 일별 기록", "DAILY", "00:05", null);

        // APM 배치 통합: APM_DAILY/APM_WEEKLY 제거, APM_COLLECT 생성 (기본 수집범위 7일)
        boolean hasDaily = repository.findByJobType("APM_DAILY").isPresent();
        boolean hasWeekly = repository.findByJobType("APM_WEEKLY").isPresent();
        if (hasDaily || hasWeekly) {
            // 기존 엔트리가 있으면 APM_DAILY를 APM_COLLECT로 재명명, APM_WEEKLY 삭제
            repository.findByJobType("APM_DAILY").ifPresent(c -> {
                if (repository.findByJobType("APM_COLLECT").isEmpty()) {
                    c.setJobType("APM_COLLECT");
                    c.setDescription("APM 호출건수 수집 (와탭/제니퍼)");
                    if (c.getJobParam() == null) c.setJobParam("7");
                    repository.save(c);
                    log.info("[스케줄 마이그레이션] APM_DAILY → APM_COLLECT");
                } else {
                    repository.delete(c);
                }
            });
            repository.findByJobType("APM_WEEKLY").ifPresent(c -> {
                repository.delete(c);
                log.info("[스케줄 마이그레이션] APM_WEEKLY 삭제");
            });
        }
        createIfAbsent("APM_COLLECT", "APM 호출건수 수집 (와탭/제니퍼)", "DAILY", "06:00", "7");
        createIfAbsent("DATA_BACKUP", "분석데이터·호출이력 자동 백업", "DAILY", "02:30", null);
        createIfAbsent("JIRA_SYNC", "Jira 동기화 (정방향+역방향)", "HOURLY", "00:00", null);
    }

    private void createIfAbsent(String jobType, String desc, String scheduleType, String runTime, String jobParam) {
        createIfAbsentInternal(jobType, desc, scheduleType, runTime, jobParam, false);
    }

    /** 기본 활성화된 시스템 배치용 (DB 스냅샷 등) */
    private void createIfAbsentEnabled(String jobType, String desc, String scheduleType, String runTime, String jobParam) {
        createIfAbsentInternal(jobType, desc, scheduleType, runTime, jobParam, true);
    }

    private void createIfAbsentInternal(String jobType, String desc, String scheduleType, String runTime, String jobParam, boolean enabled) {
        if (repository.findByJobType(jobType).isEmpty()) {
            ScheduleConfig c = new ScheduleConfig();
            c.setJobType(jobType);
            c.setDescription(desc);
            c.setScheduleType(scheduleType);
            c.setRunTime(runTime);
            c.setEnabled(enabled);
            c.setJobParam(jobParam);
            if ("WEEKLY".equals(scheduleType)) c.setRunDay("MON");
            repository.save(c);
        }
    }

    /** 스케줄 적용 (활성화면 등록, 비활성화면 삭제) */
    public void applySchedule(ScheduleConfig config) {
        try {
            JobKey jobKey = new JobKey(config.getJobType());
            TriggerKey triggerKey = new TriggerKey(config.getJobType() + "-trigger");

            // 기존 삭제
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }

            if (!config.isEnabled()) {
                log.info("[스케줄] {} 비활성화", config.getJobType());
                return;
            }

            Class<? extends Job> jobClass = resolveJobClass(config.getJobType());
            if (jobClass == null) return;

            String cron = config.toCronExpression();

            JobDetail job = JobBuilder.newJob(jobClass)
                    .withIdentity(jobKey)
                    .usingJobData("period", config.getJobType().contains("WEEKLY") ? "WEEKLY" : "DAILY")
                    .usingJobData("jobParam", config.getJobParam() != null ? config.getJobParam() : "")
                    .storeDurably()
                    .build();

            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                    .build();

            scheduler.scheduleJob(job, trigger);
            log.info("[스케줄] {} 등록 완료: cron={}", config.getJobType(), cron);

        } catch (Exception e) {
            log.error("[스케줄] {} 등록 실패: {}", config.getJobType(), e.getMessage());
        }
    }

    /** 설정 저장 + 스케줄 재적용 */
    public ScheduleConfig saveAndApply(ScheduleConfig config) {
        ScheduleConfig saved = repository.save(config);
        applySchedule(saved);
        return saved;
    }

    public List<ScheduleConfig> findAll() {
        return repository.findAll();
    }

    private Class<? extends Job> resolveJobClass(String jobType) {
        return switch (jobType) {
            case "GIT_PULL_EXTRACT" -> GitPullExtractJob.class;
            case "APM_COLLECT", "APM_DAILY", "APM_WEEKLY" -> ApmCollectJob.class;
            case "DB_SNAPSHOT" -> DbSnapshotJob.class;
            case "DATA_BACKUP" -> DataBackupJob.class;
            case "JIRA_SYNC" -> JiraSyncJob.class;
            default -> null;
        };
    }
}
