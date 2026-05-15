package com.baek.viewer.job;

import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.AiSummaryService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/**
 * LOCAi 추천 메시지 주기적 갱신 배치.
 * 스케줄 주기마다 대시보드 통계·DB 현황을 수집하여 AI 요약을 생성하고
 * GlobalConfig.aiDashboardSummary 에 캐시한다.
 */
public class AiSummaryJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AiSummaryJob.class);

    @Autowired private AiSummaryService aiSummaryService;
    @Autowired private ScheduleConfigRepository scheduleRepo;

    @Override
    public void execute(JobExecutionContext context) {
        long startMs = System.currentTimeMillis();
        log.info("[AI_SUMMARY] 배치 시작");
        try {
            String summary = aiSummaryService.generateAndCache();
            long elapsed = System.currentTimeMillis() - startMs;
            String msg = summary != null
                    ? String.format("성공 (%d자, %dms)", summary.length(), elapsed)
                    : String.format("건너뜀 (AI 미설정, %dms)", elapsed);
            log.info("[AI_SUMMARY] 배치 완료: {}", msg);
            updateResult(msg);
            context.setResult(java.util.Map.of("status", "SUCCESS", "summary", msg));
        } catch (Exception e) {
            log.error("[AI_SUMMARY] 배치 실패: {}", e.getMessage(), e);
            String msg = "실패: " + e.getMessage();
            updateResult(msg);
            context.setResult(java.util.Map.of("status", "FAIL", "summary", msg));
        }
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("AI_SUMMARY").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
