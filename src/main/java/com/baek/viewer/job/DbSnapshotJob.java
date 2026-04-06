package com.baek.viewer.job;

import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.DbMonitorService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/**
 * DB 파일 사이즈 일별 스냅샷 배치.
 * 매일 지정 시각에 db_size_history 테이블에 현재 DB 크기/레코드 수를 기록.
 */
public class DbSnapshotJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DbSnapshotJob.class);
    @Autowired private DbMonitorService dbMonitorService;
    @Autowired private ScheduleConfigRepository scheduleRepo;

    @Override
    public void execute(JobExecutionContext context) {
        long startMs = System.currentTimeMillis();
        log.info("[DB_SNAPSHOT] 배치 시작");
        try {
            var snap = dbMonitorService.takeSnapshot();
            long elapsed = System.currentTimeMillis() - startMs;
            String msg = String.format("성공 (DB %.1fMB, ApiRecord %d건, ApmCallData %d건, %dms)",
                    snap.getDbSizeBytes() / 1024.0 / 1024.0,
                    snap.getApiRecordCount(), snap.getApmCallDataCount(), elapsed);
            log.info("[DB_SNAPSHOT] 배치 완료: {}", msg);
            updateResult(msg);
        } catch (Exception e) {
            log.error("[DB_SNAPSHOT] 배치 실패: {}", e.getMessage(), e);
            updateResult("실패: " + e.getMessage());
        }
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("DB_SNAPSHOT").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
