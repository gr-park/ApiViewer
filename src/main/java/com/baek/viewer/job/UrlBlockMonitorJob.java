package com.baek.viewer.job;

import com.baek.viewer.model.BlockedTxRow;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import com.baek.viewer.service.JenniferBlockMonitorService;
import com.baek.viewer.service.WhatapTxSearchService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 차단 모니터링 배치 — 와탭+Jennifer 활성 레포 전체에 대해
 * {@link WhatapTxSearchService#search} / {@link JenniferBlockMonitorService#search} 를 호출해
 * 차단 인입 건수를 집계·로그만 남긴다 (DB 저장 없음).
 * <p>
 * jobParam 형식: {@code R[0137]B[01]T[01]} (예: R1B1T1)<br>
 * R0=당일만, R1=어제~오늘(당일 포함), R3=당일 포함 3일, R7=당일 포함 7일<br>
 * B1=봇 제외, B0=봇 포함 · T1=IT담당자 모니터링 테스트 시간대 제외, T0=미적용
 */
public class UrlBlockMonitorJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(UrlBlockMonitorJob.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern PARAM = Pattern.compile("^R([0137])B([01])T([01])$");

    @Autowired private ScheduleConfigRepository scheduleRepo;
    @Autowired private GlobalConfigRepository globalRepo;
    @Autowired private WhatapTxSearchService whatapTxSearchService;
    @Autowired private JenniferBlockMonitorService jenniferBlockMonitorService;

    @Override
    public void execute(JobExecutionContext context) {
        String rawParam = context.getJobDetail().getJobDataMap().getString("jobParam");
        Parsed p = parseParam(rawParam);
        long t0 = System.currentTimeMillis();

        log.info("════════════════════════════════════════════════════════════");
        log.info("[BLOCK_URL_MONITOR] 배치 시작 param={} (R={}, B={}, T={})", rawParam, p.rangeCode, p.excludeBot, p.excludeItTestWindow);
        try {
            LocalDate today = LocalDate.now(KST);
            LocalDate[] range = resolveDateRange(today, p.rangeCode);
            LocalDate from = range[0];
            LocalDate to = range[1];
            log.debug("[BLOCK_URL_MONITOR] KST 오늘={}, 조회기간 {} ~ {}", today, from, to);

            GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
            // 배치에서는 프론트가 봇 키워드를 주입하지 않으므로(GlobalConfig 기본값을 그대로 사용)
            List<String> botKeywords = splitKeywords(gc.getBotKeywords());

            List<BlockedTxRow> rows = new ArrayList<>();
            log.debug("[BLOCK_URL_MONITOR] 와탭 검색 호출 excludeBot={} botKeywords={}", p.excludeBot, botKeywords.size());
            rows.addAll(whatapTxSearchService.search(null, null, null, from, to, p.excludeBot, botKeywords));
            log.debug("[BLOCK_URL_MONITOR] Jennifer 검색 호출 excludeBot={} botKeywords={}", p.excludeBot, botKeywords.size());
            rows.addAll(jenniferBlockMonitorService.search(null, null, from, to, p.excludeBot, botKeywords));

            rows.sort(Comparator.comparing((BlockedTxRow a) -> a.getEndtime() == null ? "" : a.getEndtime()).reversed());

            int rawCount = rows.size();
            if (p.excludeItTestWindow) {
                log.debug("[BLOCK_URL_MONITOR] IT테스트시간대 제외 적용: {} ~ {}", gc.getBlockMonitorExcludeStart(), gc.getBlockMonitorExcludeEnd());
                rows = rows.stream().filter(r -> !inItTestExcludeWindow(r, gc)).toList();
            } else {
                log.debug("[BLOCK_URL_MONITOR] IT테스트시간대 제외 미적용");
            }
            int afterFilter = rows.size();
            long elapsed = System.currentTimeMillis() - t0;
            String msg = String.format("성공 — 기간 %s~%s / 원본 %d건 / 필터 후 %d건 / 봇제외=%s / IT테스트시간대제외=%s / %dms",
                    from, to, rawCount, afterFilter, p.excludeBot, p.excludeItTestWindow, elapsed);
            log.info("[BLOCK_URL_MONITOR] {}", msg);
            log.info("════════════════════════════════════════════════════════════");
            updateResult(msg);
            context.setResult(java.util.Map.of(
                    "status", "SUCCESS",
                    "count", afterFilter,
                    "summary", msg));
        } catch (Exception e) {
            log.error("[BLOCK_URL_MONITOR] 배치 실패: {}", e.getMessage(), e);
            log.info("════════════════════════════════════════════════════════════");
            updateResult("실패: " + e.getMessage());
            context.setResult(java.util.Map.of(
                    "status", "FAIL",
                    "summary", "실패: " + e.getMessage(),
                    "message", String.valueOf(e)));
        }
    }

    private record Parsed(int rangeCode, boolean excludeBot, boolean excludeItTestWindow) {}

    private static Parsed parseParam(String s) {
        if (s == null || s.isBlank()) return new Parsed(1, true, true);
        Matcher m = PARAM.matcher(s.trim());
        if (!m.matches()) {
            log.warn("[BLOCK_URL_MONITOR] jobParam 형식 불명 — 기본 R1B1T1 사용: {}", s);
            return new Parsed(1, true, true);
        }
        int r = Integer.parseInt(m.group(1));
        boolean b = "1".equals(m.group(2));
        boolean t = "1".equals(m.group(3));
        return new Parsed(r, b, t);
    }

    /** [from, to] inclusive, KST 달력 기준 */
    private static LocalDate[] resolveDateRange(LocalDate today, int rangeCode) {
        return switch (rangeCode) {
            case 0 -> new LocalDate[]{today, today};
            case 1 -> new LocalDate[]{today.minusDays(1), today};
            case 3 -> new LocalDate[]{today.minusDays(2), today};
            case 7 -> new LocalDate[]{today.minusDays(6), today};
            default -> new LocalDate[]{today.minusDays(1), today};
        };
    }

    private static int parseHmToMin(String s) {
        if (s == null || s.isBlank()) return -1;
        String t = s.trim();
        try {
            if (t.length() <= 5 && t.contains(":")) {
                String[] p = t.split(":");
                int h = Integer.parseInt(p[0].trim());
                int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
                return h * 60 + m;
            }
        } catch (Exception ignored) { }
        Matcher mm = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(t);
        if (mm.find()) {
            try {
                return Integer.parseInt(mm.group(1)) * 60 + Integer.parseInt(mm.group(2));
            } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    private static List<String> splitKeywords(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        String s = csv.replace('\n', ',').replace('\r', ',');
        String[] parts = s.split(",");
        List<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            out.add(t);
        }
        return out;
    }

    private static boolean inItTestExcludeWindow(BlockedTxRow row, GlobalConfig gc) {
        String startS = gc.getBlockMonitorExcludeStart();
        String endS = gc.getBlockMonitorExcludeEnd();
        int sM = parseHmToMin(startS);
        int eM = parseHmToMin(endS);
        if (sM < 0 || eM < 0 || sM >= eM) return false;
        String ts = row.getEndtime();
        if (ts == null || ts.isBlank()) return false;
        int tM = parseHmToMin(ts);
        if (tM < 0) return false;
        return tM >= sM && tM < eM;
    }

    private void updateResult(String result) {
        scheduleRepo.findByJobType("BLOCK_URL_MONITOR").ifPresent(c -> {
            c.setLastRunAt(LocalDateTime.now());
            c.setLastRunResult(result);
            scheduleRepo.save(c);
        });
    }
}
