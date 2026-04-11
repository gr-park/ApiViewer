package com.baek.viewer.service;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 분석데이터(api_record) / 호출이력(apm_call_data) 백업·복구 핵심 로직.
 * BackupController(HTTP 레이어) 와 DataBackupJob(배치 레이어) 에서 공통 사용.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    @PersistenceContext
    private EntityManager em;

    private final GlobalConfigRepository configRepository;

    public BackupService(GlobalConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    // ─── 분석데이터 백업 ───────────────────────────────────────────────────────

    @Transactional
    public int backupAnalysis(String repoName, String ip) {
        ensureBackupTable("api_record", "api_record_backup");
        int backed;
        if (isAll(repoName)) {
            em.createNativeQuery("TRUNCATE TABLE api_record_backup").executeUpdate();
            backed = em.createNativeQuery("INSERT INTO api_record_backup SELECT * FROM api_record")
                    .executeUpdate();
            log.info("[분석데이터 백업] 전체 {}건, ip={}", backed, ip);
        } else {
            em.createNativeQuery("DELETE FROM api_record_backup WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            backed = em.createNativeQuery(
                    "INSERT INTO api_record_backup SELECT * FROM api_record WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            log.info("[분석데이터 백업] repo={}, {}건, ip={}", repoName, backed, ip);
        }
        updateBackupMeta("analysis", backed);
        return backed;
    }

    // ─── 호출이력 백업 ─────────────────────────────────────────────────────────

    @Transactional
    public int backupCallHistory(String repoName, String ip) {
        ensureBackupTable("apm_call_data", "apm_call_data_backup");
        int backed;
        if (isAll(repoName)) {
            em.createNativeQuery("TRUNCATE TABLE apm_call_data_backup").executeUpdate();
            backed = em.createNativeQuery("INSERT INTO apm_call_data_backup SELECT * FROM apm_call_data")
                    .executeUpdate();
            log.info("[호출이력 백업] 전체 {}건, ip={}", backed, ip);
        } else {
            em.createNativeQuery("DELETE FROM apm_call_data_backup WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            backed = em.createNativeQuery(
                    "INSERT INTO apm_call_data_backup SELECT * FROM apm_call_data WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            log.info("[호출이력 백업] repo={}, {}건, ip={}", repoName, backed, ip);
        }
        updateBackupMeta("callHistory", backed);
        return backed;
    }

    // ─── 분석데이터 복구 ───────────────────────────────────────────────────────

    @Transactional
    public int restoreAnalysis(String repoName, String ip) {
        ensureBackupTable("api_record", "api_record_backup");
        long backupCount = countTable("api_record_backup", repoName);
        if (backupCount == 0) throw new IllegalStateException("백업이 없습니다. 먼저 백업을 실행하세요.");
        int restored;
        if (isAll(repoName)) {
            em.createNativeQuery("DELETE FROM api_record").executeUpdate();
            restored = em.createNativeQuery("INSERT INTO api_record SELECT * FROM api_record_backup")
                    .executeUpdate();
            log.info("[분석데이터 복구] 전체 {}건, ip={}", restored, ip);
        } else {
            em.createNativeQuery("DELETE FROM api_record WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            restored = em.createNativeQuery(
                    "INSERT INTO api_record SELECT * FROM api_record_backup WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            log.info("[분석데이터 복구] repo={}, {}건, ip={}", repoName, restored, ip);
        }
        return restored;
    }

    // ─── 호출이력 복구 ─────────────────────────────────────────────────────────

    @Transactional
    public int restoreCallHistory(String repoName, String ip) {
        ensureBackupTable("apm_call_data", "apm_call_data_backup");
        long backupCount = countTable("apm_call_data_backup", repoName);
        if (backupCount == 0) throw new IllegalStateException("백업이 없습니다. 먼저 백업을 실행하세요.");
        int restored;
        if (isAll(repoName)) {
            em.createNativeQuery("DELETE FROM apm_call_data").executeUpdate();
            restored = em.createNativeQuery("INSERT INTO apm_call_data SELECT * FROM apm_call_data_backup")
                    .executeUpdate();
            log.info("[호출이력 복구] 전체 {}건, ip={}", restored, ip);
        } else {
            em.createNativeQuery("DELETE FROM apm_call_data WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            restored = em.createNativeQuery(
                    "INSERT INTO apm_call_data SELECT * FROM apm_call_data_backup WHERE repository_name = :repo")
                    .setParameter("repo", repoName).executeUpdate();
            log.info("[호출이력 복구] repo={}, {}건, ip={}", repoName, restored, ip);
        }
        return restored;
    }

    // ─── 상태 조회 ─────────────────────────────────────────────────────────────

    public String getLastBackupMeta() {
        GlobalConfig cfg = configRepository.findById(1L).orElse(new GlobalConfig());
        return cfg.getLastBackupMeta();
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** 백업 테이블이 없으면 원본과 동일 구조로 빈 테이블 생성 */
    @Transactional
    public void ensureBackupTable(String src, String dst) {
        em.createNativeQuery("CREATE TABLE IF NOT EXISTS " + dst + " AS SELECT * FROM " + src + " WHERE 1=0")
                .executeUpdate();
    }

    public long countTable(String table, String repoName) {
        try {
            if (isAll(repoName)) {
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

    private boolean isAll(String repoName) {
        return repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName);
    }

    private void updateBackupMeta(String type, int count) {
        try {
            GlobalConfig cfg = configRepository.findById(1L).orElse(new GlobalConfig());
            String raw = cfg.getLastBackupMeta();
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
}
