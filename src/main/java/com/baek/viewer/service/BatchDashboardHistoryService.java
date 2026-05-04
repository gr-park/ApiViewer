package com.baek.viewer.service;

import com.baek.viewer.model.BatchDashboardDailyDto;
import com.baek.viewer.model.BatchExecutionLog;
import com.baek.viewer.repository.BatchExecutionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 표시용 배치 이력 집계 (일자·배치별 1행).
 */
@Service
public class BatchDashboardHistoryService {

    private static final Logger log = LoggerFactory.getLogger(BatchDashboardHistoryService.class);
    private static final int SUMMARY_MAX = 400;

    private final BatchExecutionLogRepository repository;

    public BatchDashboardHistoryService(BatchExecutionLogRepository repository) {
        this.repository = repository;
    }

    /**
     * @param days 조회할 달력 일 수 (1=오늘만, 7=오늘 포함 최근 7일). 상한 60.
     */
    public List<BatchDashboardDailyDto> dailySummary(int days) {
        int d = Math.min(60, Math.max(1, days));
        LocalDate today = LocalDate.now();
        LocalDateTime fromTs = today.minusDays(d - 1L).atStartOfDay();
        LocalDateTime toTs = today.plusDays(1).atStartOfDay();
        log.debug("[배치대시보드] 집계 기간: {} ~ (미포함) {}, days={}", fromTs, toTs, d);

        List<BatchExecutionLog> logs = repository.findAllInRangeOrderByStartTimeDesc(fromTs, toTs);
        log.debug("[배치대시보드] 원본 로그 {}건", logs.size());

        // startTime DESC 순이므로 (일자|jobType) 첫 등장 = 해당 일의 마지막 수행
        Map<String, Agg> byKey = new HashMap<>();
        for (BatchExecutionLog b : logs) {
            if (b.getStartTime() == null || b.getJobType() == null) continue;
            LocalDate day = b.getStartTime().toLocalDate();
            String key = day + "|" + b.getJobType();
            Agg a = byKey.computeIfAbsent(key, k -> new Agg());
            a.runCount++;
            if (a.latest == null) {
                a.latest = b;
            }
        }

        List<BatchDashboardDailyDto> out = new ArrayList<>();
        for (Agg a : byKey.values()) {
            if (a.latest == null) continue;
            BatchExecutionLog r = a.latest;
            BatchDashboardDailyDto dto = new BatchDashboardDailyDto();
            dto.setRunDate(r.getStartTime().toLocalDate().toString());
            dto.setJobType(r.getJobType());
            dto.setDescription(r.getDescription());
            dto.setRunCount(a.runCount);
            dto.setLastStartTime(r.getStartTime());
            dto.setLastEndTime(r.getEndTime());
            dto.setDurationMs(r.getDurationMs());
            dto.setItemCount(r.getItemCount());
            dto.setFailItemCount(r.getFailItemCount());
            dto.setStatus(r.getStatus());
            dto.setResultSummary(truncate(r.getResultSummary(), SUMMARY_MAX));
            out.add(dto);
        }

        // 실패(FAIL 등) 먼저, 그다음 일자 내림차순, 배치명
        out.sort(Comparator
                .comparing((BatchDashboardDailyDto row) -> isSuccessStatus(row.getStatus()) ? 1 : 0)
                .thenComparing(BatchDashboardDailyDto::getRunDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(BatchDashboardDailyDto::getJobType, Comparator.nullsLast(String::compareTo)));
        log.debug("[배치대시보드] 집계 행 {}건", out.size());
        return out;
    }

    private static boolean isSuccessStatus(String status) {
        return status != null && "SUCCESS".equalsIgnoreCase(status.trim());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static final class Agg {
        int runCount;
        BatchExecutionLog latest;
    }
}
