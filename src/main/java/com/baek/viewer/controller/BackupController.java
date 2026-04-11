package com.baek.viewer.controller;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 분석데이터(api_record) / 호출이력(apm_call_data) 단일 스냅샷 백업·복구.
 * 백업 테이블: api_record_backup, apm_call_data_backup (최초 실행 시 자동 생성).
 * 전략: 단일 스냅샷 덮어쓰기 — 백업 시마다 해당 범위 기존 행을 지우고 재삽입.
 */
@RestController
@RequestMapping("/api/db")
public class BackupController {

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);

    @PersistenceContext
    private EntityManager em;

    private final GlobalConfigRepository configRepository;

    public BackupController(GlobalConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    // ─── 백업 상태 조회 ────────────────────────────────────────────────────────

    @GetMapping("/backup/status")
    public ResponseEntity<?> backupStatus() {
        GlobalConfig cfg = configRepository.findById(1L).orElse(new GlobalConfig());
        String meta = cfg.getLastBackupMeta();
        if (meta == null || meta.isBlank()) {
            return ResponseEntity.ok(Map.of("analysis", Map.of("count", 0),
                    "callHistory", Map.of("count", 0)));
        }
        // JSON 파싱은 단순 Jackson 의존 없이 그대로 문자열 반환
        // 클라이언트가 JSON.parse로 처리
        return ResponseEntity.ok(Map.of("raw", meta));
    }

    // ─── 분석데이터 백업 ─────────────────────────────────────────────────────

    @PostMapping("/backup/analysis")
    @Transactional
    public ResponseEntity<?> backupAnalysis(@RequestParam(required = false) String repoName,
                                             HttpServletRequest req) {
        try {
            ensureBackupTable("api_record", "api_record_backup");
            int backed;
            if (repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName)) {
                em.createNativeQuery("TRUNCATE TABLE api_record_backup").executeUpdate();
                backed = em.createNativeQuery("INSERT INTO api_record_backup SELECT * FROM api_record")
                        .executeUpdate();
                log.info("[분석데이터 백업] 전체 {} 건, ip={}", backed, getIp(req));
            } else {
                em.createNativeQuery("DELETE FROM api_record_backup WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                backed = em.createNativeQuery("INSERT INTO api_record_backup SELECT * FROM api_record WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                log.info("[분석데이터 백업] repo={}, {} 건, ip={}", repoName, backed, getIp(req));
            }
            updateBackupMeta("analysis", backed);
            return ResponseEntity.ok(Map.of("backed", backed, "backedAt", LocalDateTime.now().toString()));
        } catch (Exception e) {
            log.error("[분석데이터 백업 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 호출이력 백업 ────────────────────────────────────────────────────────

    @PostMapping("/backup/call-history")
    @Transactional
    public ResponseEntity<?> backupCallHistory(@RequestParam(required = false) String repoName,
                                                HttpServletRequest req) {
        try {
            ensureBackupTable("apm_call_data", "apm_call_data_backup");
            int backed;
            if (repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName)) {
                em.createNativeQuery("TRUNCATE TABLE apm_call_data_backup").executeUpdate();
                backed = em.createNativeQuery("INSERT INTO apm_call_data_backup SELECT * FROM apm_call_data")
                        .executeUpdate();
                log.info("[호출이력 백업] 전체 {} 건, ip={}", backed, getIp(req));
            } else {
                em.createNativeQuery("DELETE FROM apm_call_data_backup WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                backed = em.createNativeQuery("INSERT INTO apm_call_data_backup SELECT * FROM apm_call_data WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                log.info("[호출이력 백업] repo={}, {} 건, ip={}", repoName, backed, getIp(req));
            }
            updateBackupMeta("callHistory", backed);
            return ResponseEntity.ok(Map.of("backed", backed, "backedAt", LocalDateTime.now().toString()));
        } catch (Exception e) {
            log.error("[호출이력 백업 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 분석데이터 복구 ─────────────────────────────────────────────────────

    @PostMapping("/restore/analysis")
    @Transactional
    public ResponseEntity<?> restoreAnalysis(@RequestParam(required = false) String repoName,
                                              HttpServletRequest req) {
        try {
            ensureBackupTable("api_record", "api_record_backup");
            // 빈 백업 안전장치
            long backupCount = countTable("api_record_backup", repoName);
            if (backupCount == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "백업이 없습니다. 먼저 백업을 실행하세요."));
            }
            int restored;
            if (repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName)) {
                em.createNativeQuery("DELETE FROM api_record").executeUpdate();
                restored = em.createNativeQuery("INSERT INTO api_record SELECT * FROM api_record_backup")
                        .executeUpdate();
                log.info("[분석데이터 복구] 전체 {} 건, ip={}", restored, getIp(req));
            } else {
                em.createNativeQuery("DELETE FROM api_record WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                restored = em.createNativeQuery("INSERT INTO api_record SELECT * FROM api_record_backup WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                log.info("[분석데이터 복구] repo={}, {} 건, ip={}", repoName, restored, getIp(req));
            }
            return ResponseEntity.ok(Map.of("restored", restored, "restoredAt", LocalDateTime.now().toString()));
        } catch (Exception e) {
            log.error("[분석데이터 복구 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 호출이력 복구 ────────────────────────────────────────────────────────

    @PostMapping("/restore/call-history")
    @Transactional
    public ResponseEntity<?> restoreCallHistory(@RequestParam(required = false) String repoName,
                                                 HttpServletRequest req) {
        try {
            ensureBackupTable("apm_call_data", "apm_call_data_backup");
            long backupCount = countTable("apm_call_data_backup", repoName);
            if (backupCount == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "백업이 없습니다. 먼저 백업을 실행하세요."));
            }
            int restored;
            if (repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName)) {
                em.createNativeQuery("DELETE FROM apm_call_data").executeUpdate();
                restored = em.createNativeQuery("INSERT INTO apm_call_data SELECT * FROM apm_call_data_backup")
                        .executeUpdate();
                log.info("[호출이력 복구] 전체 {} 건, ip={}", restored, getIp(req));
            } else {
                em.createNativeQuery("DELETE FROM apm_call_data WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                restored = em.createNativeQuery("INSERT INTO apm_call_data SELECT * FROM apm_call_data_backup WHERE repository_name = :repo")
                        .setParameter("repo", repoName).executeUpdate();
                log.info("[호출이력 복구] repo={}, {} 건, ip={}", repoName, restored, getIp(req));
            }
            return ResponseEntity.ok(Map.of("restored", restored, "restoredAt", LocalDateTime.now().toString()));
        } catch (Exception e) {
            log.error("[호출이력 복구 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 백업 테이블이 없으면 원본과 동일 구조로 빈 테이블 생성 (H2: IF NOT EXISTS 지원) */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void ensureBackupTable(String src, String dst) {
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS " + dst + " AS SELECT * FROM " + src + " WHERE 1=0")
                .executeUpdate();
    }

    private long countTable(String table, String repoName) {
        try {
            if (repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName)) {
                Object r = em.createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult();
                return ((Number) r).longValue();
            } else {
                Object r = em.createNativeQuery("SELECT COUNT(*) FROM " + table + " WHERE repository_name = :repo")
                        .setParameter("repo", repoName).getSingleResult();
                return ((Number) r).longValue();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateBackupMeta(String type, int count) {
        try {
            GlobalConfig cfg = configRepository.findById(1L).orElse(new GlobalConfig());
            String raw = cfg.getLastBackupMeta();
            // 단순 Map 기반 JSON 빌드 (Jackson 직접 사용 없이)
            Map<String, Object> meta = new LinkedHashMap<>();
            if (raw != null && !raw.isBlank()) {
                // 기존 메타가 있으면 파싱 없이 재조립 — 클라이언트가 파싱
                // type별 덮어쓰기: JSON 문자열 직접 조작
                meta.put("_raw", raw); // 임시 보관
            }
            // 새 메타는 항상 서버 측에서 재빌드
            String analysisJson = "{}";
            String callHistoryJson = "{}";
            if (raw != null && raw.contains("\"analysis\"")) {
                int s = raw.indexOf("\"analysis\"");
                int e = raw.indexOf("}", s);
                if (e > s) analysisJson = raw.substring(s + 11, e + 1).trim();
            }
            if (raw != null && raw.contains("\"callHistory\"")) {
                int s = raw.indexOf("\"callHistory\"");
                int e = raw.indexOf("}", s);
                if (e > s) callHistoryJson = raw.substring(s + 14, e + 1).trim();
            }
            String newEntry = "{\"at\":\"" + LocalDateTime.now() + "\",\"count\":" + count + "}";
            if ("analysis".equals(type)) analysisJson = newEntry;
            else callHistoryJson = newEntry;

            cfg.setLastBackupMeta("{\"analysis\":" + analysisJson + ",\"callHistory\":" + callHistoryJson + "}");
            configRepository.save(cfg);
        } catch (Exception ex) {
            log.warn("[백업 메타 업데이트 실패] {}", ex.getMessage());
        }
    }

    private String getIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";
        return ip;
    }
}
