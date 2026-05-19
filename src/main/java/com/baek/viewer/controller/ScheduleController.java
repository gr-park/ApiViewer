package com.baek.viewer.controller;

import com.baek.viewer.model.BatchDashboardDailyDto;
import com.baek.viewer.model.BatchExecutionLog;
import com.baek.viewer.model.ScheduleConfig;
import com.baek.viewer.repository.BatchExecutionLogRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.BatchDashboardHistoryService;
import com.baek.viewer.service.ScheduleService;
import org.quartz.SchedulerException;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleConfigRepository scheduleRepo;
    private final BatchExecutionLogRepository historyRepo;
    private final BatchDashboardHistoryService batchDashboardHistoryService;

    public ScheduleController(ScheduleService scheduleService,
                              ScheduleConfigRepository scheduleRepo,
                              BatchExecutionLogRepository historyRepo,
                              BatchDashboardHistoryService batchDashboardHistoryService) {
        this.scheduleService = scheduleService;
        this.scheduleRepo = scheduleRepo;
        this.historyRepo = historyRepo;
        this.batchDashboardHistoryService = batchDashboardHistoryService;
    }

    @GetMapping
    public ResponseEntity<List<ScheduleConfig>> list() {
        List<ScheduleConfig> list = scheduleService.findAll();
        if (list.isEmpty()) {
            // 기본 스케줄이 없으면 재생성
            scheduleService.ensureAndApplyDefaults();
            list = scheduleService.findAll();
        }
        return ResponseEntity.ok(list);
    }

    /** 기본 스케줄 강제 재생성 */
    @PostMapping("/reset")
    public ResponseEntity<List<ScheduleConfig>> reset() {
        scheduleService.ensureAndApplyDefaults();
        return ResponseEntity.ok(scheduleService.findAll());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ScheduleConfig existing = scheduleRepo.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();

        applySchedulePatch(existing, body);

        return ResponseEntity.ok(scheduleService.saveAndApply(existing));
    }

    /**
     * 배치 1회 즉시 실행(스케줄 저장과 별개). 요청 본문은 PUT과 동일하게 주면 화면의 값으로 실행되며,
     * 본문 생략 시 DB에 저장된 설정으로 실행한다.
     */
    @PostMapping("/{id}/run")
    public ResponseEntity<?> runNow(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        ScheduleConfig stored = scheduleRepo.findById(id).orElse(null);
        if (stored == null) return ResponseEntity.notFound().build();
        ScheduleConfig snap = snapshotForRun(stored, body);
        try {
            scheduleService.triggerJobOnce(snap);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SchedulerException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "배치를 실행 큐에 넣었습니다. 완료까지 시간이 걸릴 수 있습니다."));
    }

    private static ScheduleConfig snapshotForRun(ScheduleConfig stored, Map<String, Object> body) {
        ScheduleConfig snap = new ScheduleConfig();
        BeanUtils.copyProperties(stored, snap, "id");
        applySchedulePatch(snap, body);
        return snap;
    }

    private static void applySchedulePatch(ScheduleConfig target, Map<String, Object> body) {
        if (body == null) return;
        if (body.containsKey("enabled"))        target.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("scheduleType"))   target.setScheduleType((String) body.get("scheduleType"));
        if (body.containsKey("runTime"))        target.setRunTime((String) body.get("runTime"));
        if (body.containsKey("runDay"))         target.setRunDay((String) body.get("runDay"));
        if (body.containsKey("intervalHours"))  target.setIntervalHours(body.get("intervalHours") != null ? ((Number) body.get("intervalHours")).intValue() : null);
        if (body.containsKey("cronExpression")) target.setCronExpression((String) body.get("cronExpression"));
        if (body.containsKey("jobParam"))       target.setJobParam(body.get("jobParam") != null ? body.get("jobParam").toString() : null);
    }

    /**
     * 배치 수행 이력 조회.
     * @param from      YYYY-MM-DD (포함, 해당일 00:00)
     * @param to        YYYY-MM-DD (포함, 해당일 23:59:59.999)
     * @param jobTypes  콤마 구분 jobType 목록. 미지정 시 빈 결과 반환 (배치 선택 필수)
     */
    @GetMapping("/history")
    public ResponseEntity<List<BatchExecutionLog>> history(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String jobTypes) {
        if (jobTypes == null || jobTypes.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        List<String> types = Arrays.stream(jobTypes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (types.isEmpty()) return ResponseEntity.ok(List.of());

        LocalDateTime fromTs = LocalDate.parse(from).atStartOfDay();
        LocalDateTime toTs   = LocalDate.parse(to).plusDays(1).atStartOfDay();

        return ResponseEntity.ok(historyRepo.findByRange(fromTs, toTs, types));
    }

    /**
     * 대시보드용 배치 이력 — 공개 GET. 동일 (일자, jobType) 당 1행, 당일 다회 수행은 마지막 수행을 대표로 {@code runCount}에 횟수 표기.
     *
     * @param days 오늘 포함 조회 일 수 (기본 7, 최대 60)
     */
    @GetMapping("/history/dashboard-daily")
    public ResponseEntity<List<BatchDashboardDailyDto>> historyDashboardDaily(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(batchDashboardHistoryService.dailySummary(days));
    }
}

