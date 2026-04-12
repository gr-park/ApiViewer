package com.baek.viewer.controller;

import com.baek.viewer.model.JiraConfig;
import com.baek.viewer.model.JiraUserMapping;
import com.baek.viewer.repository.JiraConfigRepository;
import com.baek.viewer.repository.JiraUserMappingRepository;
import com.baek.viewer.service.JiraService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jira")
public class JiraController {

    private static final Logger log = LoggerFactory.getLogger(JiraController.class);

    private final JiraService jiraService;
    private final JiraConfigRepository jiraConfigRepo;
    private final JiraUserMappingRepository userMappingRepo;

    public JiraController(JiraService jiraService,
                          JiraConfigRepository jiraConfigRepo,
                          JiraUserMappingRepository userMappingRepo) {
        this.jiraService = jiraService;
        this.jiraConfigRepo = jiraConfigRepo;
        this.userMappingRepo = userMappingRepo;
    }

    // ── 정방향 동기화 (URLViewer → Jira) ──

    @PostMapping("/sync/record/{id}")
    public ResponseEntity<?> syncRecord(@PathVariable Long id) {
        try {
            Map<String, Object> result = jiraService.syncRecordToJira(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("[Jira] 단건 동기화 실패 ({}): {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("[Jira] 설정 미비: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Jira] 단건 동기화 오류 ({}): {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sync/repo/{repoName}")
    public ResponseEntity<?> syncRepo(@PathVariable String repoName) {
        try {
            Map<String, Object> result = jiraService.syncRepoToJira(repoName);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Jira] 레포 동기화 오류 ({}): {}", repoName, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sync/all")
    public ResponseEntity<?> syncAll() {
        try {
            Map<String, Object> result = jiraService.syncAllToJira();
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Jira] 전체 동기화 오류: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 역방향 동기화 (Jira → URLViewer) ──

    @PostMapping("/pull/record/{id}")
    public ResponseEntity<?> pullRecord(@PathVariable Long id) {
        try {
            Map<String, Object> result = jiraService.syncRecordFromJira(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Jira] 단건 역방향 동기화 오류 ({}): {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pull/repo/{repoName}")
    public ResponseEntity<?> pullRepo(@PathVariable String repoName) {
        try {
            Map<String, Object> result = jiraService.syncRepoFromJira(repoName);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Jira] 레포 역방향 동기화 오류 ({}): {}", repoName, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pull/all")
    public ResponseEntity<?> pullAll() {
        try {
            Map<String, Object> result = jiraService.syncAllFromJira();
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Jira] 전체 역방향 동기화 오류: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Jira 설정 ──

    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        return jiraConfigRepo.findAll().stream().findFirst()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of()));
    }

    @PutMapping("/config")
    public ResponseEntity<?> saveConfig(@RequestBody JiraConfig config) {
        try {
            // singleton: id=1 고정
            config.setId(1L);
            JiraConfig existing = jiraConfigRepo.findById(1L).orElse(null);
            if (existing != null) {
                // 기존 설정의 createdAt, lastSyncedAt 보존
                if (config.getCreatedAt() == null) config.setCreatedAt(existing.getCreatedAt());
                if (config.getLastSyncedAt() == null) config.setLastSyncedAt(existing.getLastSyncedAt());
            }
            JiraConfig saved = jiraConfigRepo.save(config);
            log.info("[Jira] 설정 저장 완료: baseUrl={}, project={}", saved.getJiraBaseUrl(), saved.getProjectKey());
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("[Jira] 설정 저장 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/config/test")
    public ResponseEntity<?> testConnection() {
        try {
            Map<String, Object> result = jiraService.testConnection();
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── 담당자 매핑 ──

    @GetMapping("/mappings")
    public ResponseEntity<?> getMappings() {
        List<JiraUserMapping> mappings = userMappingRepo.findAll();
        return ResponseEntity.ok(mappings);
    }

    @PostMapping("/mappings")
    public ResponseEntity<?> saveMapping(@RequestBody JiraUserMapping mapping) {
        try {
            // 동일 urlviewerName이 있으면 업데이트
            if (mapping.getId() == null && mapping.getUrlviewerName() != null) {
                userMappingRepo.findByUrlviewerName(mapping.getUrlviewerName())
                        .ifPresent(existing -> mapping.setId(existing.getId()));
            }
            JiraUserMapping saved = userMappingRepo.save(mapping);
            log.info("[Jira] 담당자 매핑 저장: {} → {}",
                    saved.getUrlviewerName(), saved.getJiraAccountId());
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("[Jira] 담당자 매핑 저장 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/mappings/{id}")
    public ResponseEntity<?> deleteMapping(@PathVariable Long id) {
        try {
            if (!userMappingRepo.existsById(id)) {
                return ResponseEntity.badRequest().body(Map.of("error", "매핑 없음: id=" + id));
            }
            userMappingRepo.deleteById(id);
            log.info("[Jira] 담당자 매핑 삭제: id={}", id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (Exception e) {
            log.error("[Jira] 담당자 매핑 삭제 실패 ({}): {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
