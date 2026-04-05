package com.baek.viewer.service;

import com.baek.viewer.model.DbSizeHistory;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.ApmCallDataRepository;
import com.baek.viewer.repository.DbSizeHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * DB 파일 사이즈 모니터링 서비스 (크로스 플랫폼).
 * - 현재 DB 파일 사이즈 + 디스크 사용량 조회
 * - 일별 스냅샷 기록
 * - 증가 추이 조회 (최근 N일)
 */
@Service
public class DbMonitorService {

    private static final Logger log = LoggerFactory.getLogger(DbMonitorService.class);

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private final DbSizeHistoryRepository historyRepo;
    private final ApiRecordRepository apiRecordRepo;
    private final ApmCallDataRepository apmRepo;

    public DbMonitorService(DbSizeHistoryRepository historyRepo,
                            ApiRecordRepository apiRecordRepo,
                            ApmCallDataRepository apmRepo) {
        this.historyRepo = historyRepo;
        this.apiRecordRepo = apiRecordRepo;
        this.apmRepo = apmRepo;
    }

    /** H2 datasource URL에서 실제 파일 경로 추출 — OS 무관 */
    private Path resolveDbFilePath() {
        // ex) "jdbc:h2:file:./data/api-viewer-db"
        String prefix = "jdbc:h2:file:";
        int idx = datasourceUrl.indexOf(prefix);
        String raw = idx >= 0 ? datasourceUrl.substring(idx + prefix.length()) : "./data/api-viewer-db";
        // ; 뒤 파라미터 제거
        int semi = raw.indexOf(';');
        if (semi >= 0) raw = raw.substring(0, semi);
        // H2 실제 파일: {base}.mv.db
        return Paths.get(raw + ".mv.db").toAbsolutePath().normalize();
    }

    /** 현재 DB 파일 사이즈 + 시스템 디스크 사용량 */
    public Map<String, Object> getCurrent() {
        Path dbFile = resolveDbFilePath();
        long dbSize = 0;
        try { if (Files.exists(dbFile)) dbSize = Files.size(dbFile); } catch (Exception e) {}

        // trace/log 파일도 확인 (H2 여러 파일 중 .mv.db만 체크)
        // 디스크 공간은 DB 파일이 저장된 디렉토리 기준 (OS 무관 — Java 표준 API)
        File root = dbFile.getParent() != null ? dbFile.getParent().toFile() : new File(".");
        if (!root.exists()) root = new File(".");
        long total = root.getTotalSpace();
        long usable = root.getUsableSpace();
        long used = total - usable;

        long apiRecCount = apiRecordRepo.count();
        long apmCount = apmRepo.count();

        // 오늘 기준 증가량 (어제 스냅샷 대비)
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        long todayGrowthBytes = 0, todayGrowthApm = 0;
        var yesterdaySnap = historyRepo.findBySnapshotDate(yesterday);
        if (yesterdaySnap.isPresent()) {
            todayGrowthBytes = dbSize - yesterdaySnap.get().getDbSizeBytes();
            todayGrowthApm = apmCount - yesterdaySnap.get().getApmCallDataCount();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbFilePath", dbFile.toString());
        result.put("dbSizeBytes", dbSize);
        result.put("diskTotalBytes", total);
        result.put("diskUsedBytes", used);
        result.put("diskUsableBytes", usable);
        result.put("diskUsedPct", total > 0 ? Math.round((double) used / total * 1000) / 10.0 : 0);
        result.put("apiRecordCount", apiRecCount);
        result.put("apmCallDataCount", apmCount);
        result.put("todayGrowthBytes", todayGrowthBytes);
        result.put("todayGrowthApm", todayGrowthApm);
        result.put("osName", System.getProperty("os.name"));
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("timestamp", LocalDateTime.now().toString());
        return result;
    }

    /** 서버 기동 직후 오늘 스냅샷 보장 (부팅 시 1회) — 일별 배치는 DB_SNAPSHOT Quartz Job이 담당 */
    @EventListener(ApplicationReadyEvent.class)
    public void snapshotOnStartup() {
        try {
            takeSnapshot();
            log.info("[DB 모니터] 기동 시 스냅샷 완료");
        } catch (Exception e) {
            log.warn("[DB 모니터] 기동 시 스냅샷 실패: {}", e.getMessage());
        }
    }

    /** 오늘 날짜 스냅샷 기록 (없으면 INSERT, 있으면 UPDATE) */
    @Transactional
    public DbSizeHistory takeSnapshot() {
        LocalDate today = LocalDate.now();
        Path dbFile = resolveDbFilePath();
        long dbSize = 0;
        try { if (Files.exists(dbFile)) dbSize = Files.size(dbFile); } catch (Exception e) {}
        long apiRecCount = apiRecordRepo.count();
        long apmCount = apmRepo.count();

        DbSizeHistory snap = historyRepo.findBySnapshotDate(today)
                .orElseGet(DbSizeHistory::new);
        snap.setSnapshotDate(today);
        snap.setDbSizeBytes(dbSize);
        snap.setApiRecordCount(apiRecCount);
        snap.setApmCallDataCount(apmCount);
        snap.setCreatedAt(LocalDateTime.now());
        historyRepo.save(snap);
        log.info("[DB 스냅샷] {} — DB {}MB, ApiRecord {}, ApmCallData {}",
                today, dbSize / 1024 / 1024, apiRecCount, apmCount);
        return snap;
    }

    /** 최근 N일 증가 추이 */
    public List<Map<String, Object>> getHistory(int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(1, days));
        List<DbSizeHistory> list = historyRepo.findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(from);
        List<Map<String, Object>> out = new ArrayList<>();
        long prevSize = 0, prevApm = 0;
        boolean first = true;
        for (DbSizeHistory s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", s.getSnapshotDate().toString());
            m.put("dbSizeBytes", s.getDbSizeBytes());
            m.put("apiRecordCount", s.getApiRecordCount());
            m.put("apmCallDataCount", s.getApmCallDataCount());
            m.put("deltaBytes", first ? 0 : s.getDbSizeBytes() - prevSize);
            m.put("deltaApm", first ? 0 : s.getApmCallDataCount() - prevApm);
            out.add(m);
            prevSize = s.getDbSizeBytes();
            prevApm = s.getApmCallDataCount();
            first = false;
        }
        return out;
    }
}
