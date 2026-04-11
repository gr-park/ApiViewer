package com.baek.viewer.controller;

import com.baek.viewer.service.BackupService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 분석데이터(api_record) / 호출이력(apm_call_data) 단일 스냅샷 백업·복구 HTTP 레이어.
 * 핵심 로직은 BackupService에 위임.
 */
@RestController
@RequestMapping("/api/db")
public class BackupController {

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    // ─── 백업 상태 조회 ────────────────────────────────────────────────────────

    @GetMapping("/backup/status")
    public ResponseEntity<?> backupStatus() {
        String meta = backupService.getLastBackupMeta();
        if (meta == null || meta.isBlank()) {
            return ResponseEntity.ok(Map.of("analysis", Map.of("count", 0),
                    "callHistory", Map.of("count", 0)));
        }
        return ResponseEntity.ok(Map.of("raw", meta));
    }

    // ─── 분석데이터 백업 ─────────────────────────────────────────────────────

    @PostMapping("/backup/analysis")
    public ResponseEntity<?> backupAnalysis(@RequestParam(required = false) String repoName,
                                             HttpServletRequest req) {
        try {
            int backed = backupService.backupAnalysis(repoName, getIp(req));
            return ResponseEntity.ok(Map.of("backed", backed, "backedAt", LocalDateTime.now().toString()));
        } catch (Exception e) {
            log.error("[분석데이터 백업 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 호출이력 백업 ────────────────────────────────────────────────────────

    @PostMapping("/backup/call-history")
    public ResponseEntity<?> backupCallHistory(@RequestParam(required = false) String repoName,
                                                HttpServletRequest req) {
        try {
            int backed = backupService.backupCallHistory(repoName, getIp(req));
            return ResponseEntity.ok(Map.of("backed", backed, "backedAt", LocalDateTime.now().toString()));
        } catch (Exception e) {
            log.error("[호출이력 백업 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 분석데이터 복구 ─────────────────────────────────────────────────────

    @PostMapping("/restore/analysis")
    public ResponseEntity<?> restoreAnalysis(@RequestParam(required = false) String repoName,
                                              HttpServletRequest req) {
        try {
            int restored = backupService.restoreAnalysis(repoName, getIp(req));
            return ResponseEntity.ok(Map.of("restored", restored, "restoredAt", LocalDateTime.now().toString()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[분석데이터 복구 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 호출이력 복구 ────────────────────────────────────────────────────────

    @PostMapping("/restore/call-history")
    public ResponseEntity<?> restoreCallHistory(@RequestParam(required = false) String repoName,
                                                 HttpServletRequest req) {
        try {
            int restored = backupService.restoreCallHistory(repoName, getIp(req));
            return ResponseEntity.ok(Map.of("restored", restored, "restoredAt", LocalDateTime.now().toString()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[호출이력 복구 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    private String getIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";
        return ip;
    }
}
