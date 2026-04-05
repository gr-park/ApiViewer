package com.baek.viewer.controller;

import com.baek.viewer.service.DbMonitorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DB 파일 사이즈 모니터링 API (관리자 전용).
 */
@RestController
@RequestMapping("/api/db/monitor")
public class DbMonitorController {

    private final DbMonitorService dbMonitorService;

    public DbMonitorController(DbMonitorService dbMonitorService) {
        this.dbMonitorService = dbMonitorService;
    }

    /** 현재 DB 파일 사이즈 + 디스크 사용량 (호출 시 오늘 스냅샷 자동 기록) */
    @GetMapping("/current")
    public ResponseEntity<?> current() {
        // 조회하면서 오늘 스냅샷이 없으면 자동 기록 (lazy snapshot)
        try { dbMonitorService.takeSnapshot(); } catch (Exception ignored) {}
        return ResponseEntity.ok(dbMonitorService.getCurrent());
    }

    /** 최근 N일 증가 추이 (기본 30일) */
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(Map.of("history", dbMonitorService.getHistory(days)));
    }

    /** 수동 스냅샷 (지금 기록) */
    @PostMapping("/snapshot")
    public ResponseEntity<?> snapshot() {
        var s = dbMonitorService.takeSnapshot();
        return ResponseEntity.ok(Map.of(
                "date", s.getSnapshotDate().toString(),
                "dbSizeBytes", s.getDbSizeBytes(),
                "apiRecordCount", s.getApiRecordCount(),
                "apmCallDataCount", s.getApmCallDataCount()
        ));
    }
}
