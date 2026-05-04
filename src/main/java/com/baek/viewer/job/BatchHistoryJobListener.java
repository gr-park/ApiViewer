package com.baek.viewer.job;

import com.baek.viewer.ai.AiOpsDigestService;
import com.baek.viewer.model.BatchExecutionLog;
import com.baek.viewer.repository.BatchExecutionLogRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 모든 Quartz Job의 시작/종료 시점을 감지하여 batch_execution_log 테이블에 한 건씩 기록.
 *
 * 각 Job은 성공/실패 여부와 집계 정보를 {@code JobExecutionContext#setResult(Object)} 로
 * 다음 키를 가진 Map 으로 전달할 수 있다.
 * <pre>
 *  status    : "SUCCESS" | "FAIL"        (미지정 시 SUCCESS)
 *  count     : Integer/Long               (처리 건수, nullable)
 *  failCount : Integer/Long               (부분 실패 건수 등, 선택. 예: GIT_PULL_EXTRACT 실패 레포 수)
 *  summary   : String (≤ 500자)           (결과 요약)
 *  message   : String (≤ 4000자)          (상세/에러 메시지)
 * </pre>
 * Job이 예외를 throw 하면 listener 가 status=FAIL 로 기록하며 스택트레이스를 message 에 담는다.
 */
@Component
public class BatchHistoryJobListener implements JobListener {

    private static final Logger log = LoggerFactory.getLogger(BatchHistoryJobListener.class);
    private static final String NAME = "BatchHistoryJobListener";
    private static final String KEY_START = "batchHistory_startTime";

    private final BatchExecutionLogRepository repository;
    private final ScheduleConfigRepository scheduleRepo;
    private final AiOpsDigestService aiOpsDigestService;

    public BatchHistoryJobListener(BatchExecutionLogRepository repository,
                                   ScheduleConfigRepository scheduleRepo,
                                   AiOpsDigestService aiOpsDigestService) {
        this.repository = repository;
        this.scheduleRepo = scheduleRepo;
        this.aiOpsDigestService = aiOpsDigestService;
    }

    @Override public String getName() { return NAME; }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        context.put(KEY_START, LocalDateTime.now());
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // 사용 안 함
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        try {
            String jobType = context.getJobDetail().getKey().getName();
            LocalDateTime start = (LocalDateTime) context.get(KEY_START);
            if (start == null) start = LocalDateTime.now();
            LocalDateTime end = LocalDateTime.now();
            long durationMs = Duration.between(start, end).toMillis();

            BatchExecutionLog row = new BatchExecutionLog();
            row.setJobType(jobType);
            row.setStartTime(start);
            row.setEndTime(end);
            row.setDurationMs(durationMs);

            // description 은 현재 스케줄 설정에서 가져와 스냅샷
            scheduleRepo.findByJobType(jobType)
                    .ifPresent(s -> row.setDescription(s.getDescription()));

            String status = "SUCCESS";
            String summary = null;
            String message = null;
            Integer count = null;
            Integer failItemCount = null;

            Object result = context.getResult();
            if (result instanceof Map<?, ?> m) {
                Object st = m.get("status");
                if (st != null) status = st.toString();
                Object cnt = m.get("count");
                if (cnt instanceof Number n) count = n.intValue();
                Object fc = m.get("failCount");
                if (fc instanceof Number n) failItemCount = n.intValue();
                Object sm = m.get("summary");
                if (sm != null) summary = sm.toString();
                Object msg = m.get("message");
                if (msg != null) message = msg.toString();
            }

            if (jobException != null) {
                status = "FAIL";
                if (summary == null) summary = "실패: " + jobException.getMessage();
                if (message == null) message = stackTrace(jobException);
            }

            row.setStatus(status);
            row.setItemCount(count);
            row.setFailItemCount(failItemCount);
            row.setResultSummary(truncate(summary, 500));
            row.setMessage(truncate(message, 4000));

            repository.save(row);

            final String jt = jobType;
            final String st = status;
            CompletableFuture.runAsync(() -> {
                try {
                    aiOpsDigestService.runDigestIfConfigured(jt, st);
                } catch (Exception ex) {
                    log.warn("[AI] ops_digest 비동기 실행 실패: {}", ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[BatchHistoryJobListener] 이력 기록 실패: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
