package com.baek.viewer.job;

import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.JiraService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Jira 동기화 배치 — 정방향(URLViewer→Jira) + 역방향(Jira→URLViewer) 양방향 실행.
 * 기존 GitPullExtractJob, ApmCollectJob 패턴과 동일.
 */
public class JiraSyncJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncJob.class);

    @Autowired
    private JiraService jiraService;

    @Autowired
    private ScheduleConfigRepository scheduleRepo;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("[JIRA_SYNC] 배치 시작");
        long startMs = System.currentTimeMillis();
        try {
            // 1. 정방향: 차단대상 → Jira 티켓 생성/업데이트
            Map<String, Object> pushResult = jiraService.syncAllToJira();

            // 2. 역방향: Jira 상태 → URLViewer 반영
            Map<String, Object> pullResult = jiraService.syncAllFromJira();

            long elapsed = System.currentTimeMillis() - startMs;
            String msg = String.format(
                    "PUSH(생성:%d/갱신:%d/실패:%d) PULL(동기화:%d/미발견:%d/실패:%d) %dms",
                    pushResult.get("created"), pushResult.get("updated"), pushResult.get("failed"),
                    pullResult.get("synced"), pullResult.get("notFound"), pullResult.get("failed"),
                    elapsed);
            log.info("[JIRA_SYNC] 배치 완료: {}", msg);
            updateResult(msg);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[JIRA_SYNC] 배치 실패 ({}ms): {}", elapsed, e.getMessage(), e);
            updateResult("실패: " + e.getMessage());
        }
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("JIRA_SYNC").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
