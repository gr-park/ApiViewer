package com.baek.viewer.job;

import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.ApmCollectionService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * APM(Whatap/Jennifer) 호출건수 수집 배치 — 전체 레포 대상.
 * jobParam="days"만큼 과거 데이터를 각 레포의 활성화된 APM source별로 수집 후 집계 반영.
 * WHATAP 최대 365일, JENNIFER 최대 30일로 clamp됨.
 */
public class ApmCollectJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ApmCollectJob.class);
    @Autowired private ScheduleConfigRepository scheduleRepo;
    @Autowired private RepoConfigRepository repoConfigRepo;
    @Autowired private ApmCollectionService apmCollectionService;

    @Override
    public void execute(JobExecutionContext context) {
        String jobParam = context.getJobDetail().getJobDataMap().getString("jobParam"); // 수집 범위 일수
        int days = parseDays(jobParam, 7);
        long startMs = System.currentTimeMillis();

        log.info("════════════════════════════════════════════════════════════");
        log.info("[APM_COLLECT] 배치 시작 (수집범위={}일)", days);
        try {
            LocalDate to = LocalDate.now().minusDays(1);
            LocalDate from = to.minusDays(days - 1);
            log.info("[APM_COLLECT] 수집 기간: {} ~ {} ({}일)", from, to, days);

            List<RepoConfig> repos = repoConfigRepo.findAll();
            log.info("[APM_COLLECT] 전체 레포: {}개 스캔", repos.size());
            int totalGenerated = 0;
            int repoCount = 0;
            int skipped = 0;
            for (RepoConfig r : repos) {
                if (!"Y".equalsIgnoreCase(r.getApmBatchEnabled())) {
                    log.info("[APM_COLLECT]   - {} : 스킵 (APM배치 비활성)", r.getRepoName());
                    skipped++; continue;
                }
                boolean whatap = "Y".equalsIgnoreCase(r.getWhatapEnabled());
                boolean jennifer = "Y".equalsIgnoreCase(r.getJenniferEnabled());
                if (!whatap && !jennifer) {
                    log.info("[APM_COLLECT]   - {} : 스킵 (APM 미활성)", r.getRepoName());
                    skipped++;
                    continue;
                }
                repoCount++;
                int repoGen = 0;
                if (whatap) {
                    try {
                        int wd = Math.min(days, 365);
                        LocalDate wfrom = to.minusDays(wd - 1);
                        Object o = apmCollectionService.generateMockDataByRange(r.getRepoName(), wfrom, to, "WHATAP").get("generated");
                        int n = (o instanceof Number num) ? num.intValue() : 0;
                        totalGenerated += n; repoGen += n;
                        log.info("[APM_COLLECT]   - {} : WHATAP {}건 수집 ({}일)", r.getRepoName(), n, wd);
                    } catch (Exception e) {
                        log.warn("[APM_COLLECT]   - {} : WHATAP 실패: {}", r.getRepoName(), e.getMessage());
                    }
                }
                if (jennifer) {
                    try {
                        int jd = Math.min(days, 30);
                        LocalDate jfrom = to.minusDays(jd - 1);
                        Object o = apmCollectionService.generateMockDataByRange(r.getRepoName(), jfrom, to, "JENNIFER").get("generated");
                        int n = (o instanceof Number num) ? num.intValue() : 0;
                        totalGenerated += n; repoGen += n;
                        log.info("[APM_COLLECT]   - {} : JENNIFER {}건 수집 ({}일)", r.getRepoName(), n, jd);
                    } catch (Exception e) {
                        log.warn("[APM_COLLECT]   - {} : JENNIFER 실패: {}", r.getRepoName(), e.getMessage());
                    }
                }
                try {
                    Object o = apmCollectionService.aggregateToRecords(r.getRepoName()).get("updated");
                    int updated = (o instanceof Number num) ? num.intValue() : 0;
                    log.info("[APM_COLLECT]   - {} : 집계반영 — {}건 API 호출건수 업데이트 (수집 {}건)", r.getRepoName(), updated, repoGen);
                } catch (Exception e) {
                    log.warn("[APM_COLLECT]   - {} : 집계 실패: {}", r.getRepoName(), e.getMessage());
                }
            }
            long elapsed = System.currentTimeMillis() - startMs;
            String msg = String.format("성공 — 대상레포 %d개 / 스킵 %d개 / 수집 %d건 / 범위 %d일 (%s~%s) / %dms",
                    repoCount, skipped, totalGenerated, days, from, to, elapsed);
            log.info("[APM_COLLECT] 배치 완료: {}", msg);
            log.info("════════════════════════════════════════════════════════════");
            updateResult(msg);
        } catch (Exception e) {
            log.error("[APM_COLLECT] 배치 실패: {}", e.getMessage(), e);
            log.info("════════════════════════════════════════════════════════════");
            updateResult("실패: " + e.getMessage());
        }
    }

    private int parseDays(String s, int def) {
        try { return s != null && !s.isBlank() ? Integer.parseInt(s.trim()) : def; }
        catch (NumberFormatException e) { return def; }
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("APM_COLLECT").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
