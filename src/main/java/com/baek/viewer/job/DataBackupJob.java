package com.baek.viewer.job;

import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.BackupService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/**
 * 분석데이터·호출이력 자동 백업 배치.
 * 전체(ALL) 단일 스냅샷 덮어쓰기 방식으로 수행.
 */
public class DataBackupJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DataBackupJob.class);
    private static final String JOB_TYPE = "DATA_BACKUP";

    @Autowired private BackupService backupService;
    @Autowired private ScheduleConfigRepository scheduleRepo;

    @Override
    public void execute(JobExecutionContext context) {
        long startMs = System.currentTimeMillis();
        log.info("[DATA_BACKUP] 배치 시작");
        try {
            int analysis    = backupService.backupAnalysis(null, "BATCH");
            int callHistory = backupService.backupCallHistory(null, "BATCH");
            long elapsed    = System.currentTimeMillis() - startMs;
            String msg = String.format("분석데이터 %,d건 / 호출이력 %,d건 백업 완료 (%dms)",
                    analysis, callHistory, elapsed);
            log.info("[DATA_BACKUP] 배치 완료: {}", msg);
            updateResult(msg);
        } catch (Exception e) {
            log.error("[DATA_BACKUP] 배치 실패: {}", e.getMessage(), e);
            updateResult("실패: " + e.getMessage());
        }
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType(JOB_TYPE).ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
