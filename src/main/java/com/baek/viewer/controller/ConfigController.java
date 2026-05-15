package com.baek.viewer.controller;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.AuthService;
import com.baek.viewer.service.YamlConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final RepoConfigRepository repoRepo;
    private final GlobalConfigRepository globalRepo;
    private final YamlConfigService yamlConfigService;
    private final com.baek.viewer.service.ApmMatchReportService apmMatchReportService;
    private final com.baek.viewer.service.NavNoticeService navNoticeService;
    private final AuthService authService;

    public ConfigController(RepoConfigRepository repoRepo,
                            GlobalConfigRepository globalRepo,
                            YamlConfigService yamlConfigService,
                            com.baek.viewer.service.ApmMatchReportService apmMatchReportService,
                            com.baek.viewer.service.NavNoticeService navNoticeService,
                            AuthService authService) {
        this.repoRepo = repoRepo;
        this.globalRepo = globalRepo;
        this.yamlConfigService = yamlConfigService;
        this.apmMatchReportService = apmMatchReportService;
        this.navNoticeService = navNoticeService;
        this.authService = authService;
    }

    // ── 공통 설정 ──────────────────────────────────────────
    @GetMapping("/global")
    public ResponseEntity<?> getGlobal() {
        log.info("[공통설정 조회] GET /api/config/global");
        return ResponseEntity.ok(globalRepo.findById(1L).orElse(new GlobalConfig()));
    }

    /**
     * 대시보드·전역 네비 노란 배너용 — ops_digest AI 요약만 공개 (토큰·기타 설정 제외)
     */
    @GetMapping("/ops-digest-summary")
    public ResponseEntity<Map<String, String>> getOpsDigestSummary() {
        return globalRepo.findById(1L)
                .map(gc -> {
                    String text = gc.getAiLastOpsDigest();
                    if (text == null || text.isBlank()) {
                        return ResponseEntity.ok(Map.of("text", "", "at", ""));
                    }
                    String at = gc.getAiLastOpsDigestAt() != null
                            ? gc.getAiLastOpsDigestAt().toString() : "";
                    return ResponseEntity.ok(Map.of("text", text.trim(), "at", at));
                })
                .orElse(ResponseEntity.ok(Map.of("text", "", "at", "")));
    }

    /**
     * 대시보드/전역 네비 배너용 — APM↔URL 매칭 진단 요약(공개).
     * 설정/상세는 admin-only 페이지에서 별도 호출.
     */
    @GetMapping("/apm-match-summary")
    public ResponseEntity<?> getApmMatchSummary() {
        return globalRepo.findById(1L)
                .map(gc -> {
                    String at = gc.getApmMatchReportAt() != null ? gc.getApmMatchReportAt().toString() : "";
                    String json = gc.getApmMatchReport();
                    if (json == null || json.isBlank()) {
                        return ResponseEntity.ok(Map.of("ok", true, "at", at, "mismatchRepoCount", 0, "totalMismatchPaths", 0));
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = new ObjectMapper().readValue(json, new TypeReference<>() {});
                        int mismatchRepoCount = ((Number) m.getOrDefault("mismatchRepoCount", 0)).intValue();
                        long totalMismatchPaths = ((Number) m.getOrDefault("totalMismatchPaths", 0)).longValue();
                        int days = ((Number) m.getOrDefault("periodDays", 365)).intValue();
                        return ResponseEntity.ok(Map.of(
                                "ok", true,
                                "at", at,
                                "periodDays", days,
                                "mismatchRepoCount", mismatchRepoCount,
                                "totalMismatchPaths", totalMismatchPaths
                        ));
                    } catch (Exception e) {
                        return ResponseEntity.ok(Map.of("ok", false, "at", at, "error", "parse_failed"));
                    }
                })
                .orElse(ResponseEntity.ok(Map.of("ok", true, "at", "", "mismatchRepoCount", 0, "totalMismatchPaths", 0)));
    }

    /** 설정 상세 화면용 — 저장된 리포트 조회. refresh=1이면 즉시 재생성 후 반환. */
    @GetMapping("/apm-match-report")
    public ResponseEntity<?> getApmMatchReport(@RequestParam(value = "refresh", required = false) String refresh) {
        boolean doRefresh = "1".equals(refresh) || "true".equalsIgnoreCase(refresh);
        if (doRefresh) {
            return ResponseEntity.ok(apmMatchReportService.regenerateAndStore(365, 30));
        }
        return ResponseEntity.ok(apmMatchReportService.getStoredReport());
    }

    /**
     * 대시보드/전역 네비 배너용 — URL 분석(Extract) 문제파일 요약(공개).
     * 설정/상세는 admin-only 로 별도 조회한다.
     */
    @GetMapping("/extract-issue-summary")
    public ResponseEntity<?> getExtractIssueSummary(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        return globalRepo.findById(1L)
                .map(gc -> {
                    String at = time(gc.getExtractIssueReportAt());
                    String dismissedAt = time(gc.getExtractIssueDismissedAt());
                    boolean dismissed = isExtractIssueDismissed(gc);
                    String json = gc.getExtractIssueReport();
                    if (json == null || json.isBlank()) {
                        return ResponseEntity.ok(Map.of(
                                "ok", true,
                                "at", at,
                                "dismissed", false,
                                "dismissedAt", dismissedAt,
                                "errorFileCount", 0,
                                "zeroFileCount", 0,
                                "warnFileCount", 0
                        ));
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = new ObjectMapper().readValue(json, new TypeReference<>() {});
                        int err = ((Number) m.getOrDefault("errorFileCount", 0)).intValue();
                        int zero = ((Number) m.getOrDefault("zeroFileCount", 0)).intValue();
                        int warn = ((Number) m.getOrDefault("warnFileCount", 0)).intValue();
                        String repo = String.valueOf(m.getOrDefault("repoName", ""));
                        String label = String.valueOf(m.getOrDefault("label", ""));
                        return ResponseEntity.ok(Map.of(
                                "ok", true,
                                "at", at,
                                "dismissed", dismissed,
                                "dismissedAt", dismissedAt,
                                "repoName", repo,
                                "label", label,
                                "errorFileCount", err,
                                "zeroFileCount", zero,
                                "warnFileCount", warn
                        ));
                    } catch (Exception e) {
                        return ResponseEntity.ok(Map.of(
                                "ok", false,
                                "at", at,
                                "dismissed", dismissed,
                                "dismissedAt", dismissedAt,
                                "error", "parse_failed"
                        ));
                    }
                })
                .orElse(ResponseEntity.ok(Map.of(
                        "ok", true,
                        "at", "",
                        "dismissed", false,
                        "dismissedAt", "",
                        "errorFileCount", 0,
                        "zeroFileCount", 0,
                        "warnFileCount", 0
                )));
    }

    /** 설정/상세 화면용 — 저장된 문제파일 리포트 조회 (admin-only). */
    @GetMapping("/extract-issue-report")
    public ResponseEntity<?> getExtractIssueReport() {
        return globalRepo.findById(1L)
                .map(gc -> {
                    String at = gc.getExtractIssueReportAt() != null ? gc.getExtractIssueReportAt().toString() : "";
                    String json = gc.getExtractIssueReport();
                    if (json == null || json.isBlank()) {
                        return ResponseEntity.ok(Map.of("ok", true, "at", at));
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = new ObjectMapper().readValue(json, new TypeReference<>() {});
                        m.put("ok", true);
                        m.put("at", at);
                        return ResponseEntity.ok(m);
                    } catch (Exception e) {
                        return ResponseEntity.ok(Map.of("ok", false, "at", at, "error", "parse_failed"));
                    }
                })
                .orElse(ResponseEntity.ok(Map.of("ok", true, "at", "")));
    }

    /** extract.html 종료 시점에 문제파일 요약 저장 (admin-only). */
    @PostMapping("/extract-issue-report")
    public ResponseEntity<?> saveExtractIssueReport(@RequestBody Map<String, Object> body) {
        try {
            GlobalConfig gc = globalRepo.findById(1L).orElseGet(GlobalConfig::new);
            String json = new ObjectMapper().writeValueAsString(body != null ? body : Map.of());
            gc.setExtractIssueReport(json);
            gc.setExtractIssueReportAt(LocalDateTime.now());
            // 새 리포트가 저장되면 기존 전역 dismiss 기준은 무효화한다.
            gc.setExtractIssueDismissReportAt(null);
            gc.setExtractIssueDismissedAt(null);
            globalRepo.save(gc);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", "save_failed"));
        }
    }

    /** extract issue 상단 배너를 현재 리포트 기준으로 전역 숨김 처리 (admin-only). */
    @PostMapping("/extract-issue-dismiss")
    public ResponseEntity<?> dismissExtractIssueBanner(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        if (!authService.isAdmin(adminToken)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "admin_required"));
        }
        GlobalConfig gc = globalRepo.findById(1L).orElseGet(GlobalConfig::new);
        gc.setExtractIssueDismissReportAt(gc.getExtractIssueReportAt());
        gc.setExtractIssueDismissedAt(LocalDateTime.now());
        globalRepo.save(gc);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "dismissed", isExtractIssueDismissed(gc),
                "dismissedAt", time(gc.getExtractIssueDismissedAt())
        ));
    }

    @PutMapping("/global")
    public ResponseEntity<?> saveGlobal(@RequestBody GlobalConfig config) {
        log.info("[공통설정 저장] PUT /api/config/global");
        config = globalRepo.save(config);
        // APM 로그 레벨 동적 변경 (Logback)
        applyApmLogLevel(config.getApmLogLevel());
        return ResponseEntity.ok(config);
    }

    /** Logback 로거 레벨을 동적으로 변경 (APM 서비스 패키지) */
    private void applyApmLogLevel(String level) {
        try {
            ch.qos.logback.classic.Level lv = "DEBUG".equalsIgnoreCase(level)
                    ? ch.qos.logback.classic.Level.DEBUG : ch.qos.logback.classic.Level.INFO;
            ch.qos.logback.classic.LoggerContext ctx =
                    (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.getLogger("com.baek.viewer").setLevel(lv);
            log.info("[로그레벨 변경] com.baek.viewer → {}", lv);
        } catch (Exception e) {
            log.warn("[APM 로그레벨 변경 실패] {}", e.getMessage());
        }
    }

    // ── 레포 설정 목록 ────────────────────────────────────
    @GetMapping("/repos")
    public ResponseEntity<?> listRepos() {
        log.info("[레포설정 목록 조회] GET /api/config/repos");
        return ResponseEntity.ok(repoRepo.findAllForDisplay());
    }

    /**
     * 레포 표시 순서를 일괄 저장.
     * - payload: 레포 id 배열 (원하는 순서대로)
     * - 서버가 0..n-1로 연속 displayOrder 를 부여한다 (null은 남기지 않음)
     */
    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/repos/display-order")
    public ResponseEntity<?> saveRepoDisplayOrder(@RequestBody List<Long> ids) {
        if (ids == null) ids = List.of();
        log.info("[레포 표시순서 저장] PUT /api/config/repos/display-order size={}", ids.size());
        Map<Long, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            if (id != null && !orderMap.containsKey(id)) {
                orderMap.put(id, i);
            }
        }
        List<RepoConfig> all = repoRepo.findAll();
        for (RepoConfig rc : all) {
            Integer v = orderMap.get(rc.getId());
            // 목록에 없는 레포는 null 유지 → 정렬 시 맨 아래로 가도록
            rc.setDisplayOrder(v);
        }
        repoRepo.saveAll(all);
        return ResponseEntity.ok(Map.of("ok", true, "count", ids.size()));
    }

    // ── 배치 Git 동기화 경고 (조회 화면 배너용, 공개) ─────────
    @GetMapping("/repos/sync-warnings")
    public ResponseEntity<?> listSyncWarnings() {
        List<Map<String, Object>> warnings = new java.util.ArrayList<>();
        for (RepoConfig rc : repoRepo.findAll()) {
            if ("FAIL".equalsIgnoreCase(rc.getLastSyncStatus())) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("repoName", rc.getRepoName());
                m.put("lastSyncAt", rc.getLastSyncAt());
                m.put("message", rc.getLastSyncMessage());
                warnings.add(m);
            }
        }
        return ResponseEntity.ok(warnings);
    }

    // ── 레포 단건 조회 ────────────────────────────────────
    @GetMapping("/repos/{id}")
    public ResponseEntity<?> getRepo(@PathVariable Long id) {
        log.info("[레포설정 단건 조회] GET /api/config/repos/{}", id);
        return repoRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── 레포 추가 ─────────────────────────────────────────
    @PostMapping("/repos")
    public ResponseEntity<?> createRepo(@RequestBody RepoConfig config) {
        if (repoRepo.findByRepoName(config.getRepoName()).isPresent()) {
            log.warn("[레포설정 추가 실패] 중복 레포명: {}", config.getRepoName());
            return ResponseEntity.badRequest().body(Map.of("error", "이미 존재하는 레포지토리명입니다: " + config.getRepoName()));
        }
        resolveApmProfileFallback(config);
        log.info("[레포설정 추가] repoName={}", config.getRepoName());
        return ResponseEntity.ok(repoRepo.save(config));
    }

    // ── 레포 수정 ─────────────────────────────────────────
    @PutMapping("/repos/{id}")
    public ResponseEntity<?> updateRepo(@PathVariable Long id, @RequestBody RepoConfig config) {
        if (!repoRepo.existsById(id)) return ResponseEntity.notFound().build();
        config.setId(id);
        resolveApmProfileFallback(config);
        log.info("[레포설정 수정] id={}, repoName={}", id, config.getRepoName());
        return ResponseEntity.ok(repoRepo.save(config));
    }

    /**
     * 레포 저장 시 whatapUrl/Cookie, jenniferUrl/BearerToken 이 비어있으면
     * 공통 프로필(GlobalConfig.whatapProfiles/jenniferProfiles)에서 폴백한다.
     * YAML 가져오기와 동일한 로직 — 설정 화면에서 "비워두면 공통 프로필 사용"과 일치시킨다.
     */
    private void resolveApmProfileFallback(RepoConfig config) {
        GlobalConfig gc = globalRepo.findById(1L).orElse(null);
        if (gc == null) return;

        // 와탭 프로필 폴백
        if (config.getWhatapProfileName() != null && !config.getWhatapProfileName().isBlank()) {
            try {
                List<Map<String, String>> profiles = gc.getWhatapProfiles() != null
                        ? new ObjectMapper().readValue(gc.getWhatapProfiles(), new TypeReference<>() {})
                        : List.of();
                profiles.stream()
                        .filter(p -> config.getWhatapProfileName().equals(p.get("name")))
                        .findFirst()
                        .ifPresent(prof -> {
                            if (config.getWhatapUrl() == null || config.getWhatapUrl().isBlank()) {
                                config.setWhatapUrl(prof.get("url"));
                                log.debug("[레포설정] 와탭 URL 프로필 폴백: profile={}, url={}", config.getWhatapProfileName(), prof.get("url"));
                            }
                            if (config.getWhatapCookie() == null || config.getWhatapCookie().isBlank()) {
                                config.setWhatapCookie(prof.get("cookie"));
                                log.debug("[레포설정] 와탭 Cookie 프로필 폴백: profile={}", config.getWhatapProfileName());
                            }
                        });
            } catch (Exception e) {
                log.warn("[레포설정] 와탭 프로필 파싱 실패: {}", e.getMessage());
            }
        }

        // 제니퍼 프로필 폴백
        if (config.getJenniferProfileName() != null && !config.getJenniferProfileName().isBlank()) {
            try {
                List<Map<String, String>> profiles = gc.getJenniferProfiles() != null
                        ? new ObjectMapper().readValue(gc.getJenniferProfiles(), new TypeReference<>() {})
                        : List.of();
                profiles.stream()
                        .filter(p -> config.getJenniferProfileName().equals(p.get("name")))
                        .findFirst()
                        .ifPresent(prof -> {
                            if (config.getJenniferUrl() == null || config.getJenniferUrl().isBlank()) {
                                config.setJenniferUrl(prof.get("url"));
                                log.debug("[레포설정] 제니퍼 URL 프로필 폴백: profile={}, url={}", config.getJenniferProfileName(), prof.get("url"));
                            }
                            if (config.getJenniferBearerToken() == null || config.getJenniferBearerToken().isBlank()) {
                                config.setJenniferBearerToken(prof.get("bearerToken"));
                                log.debug("[레포설정] 제니퍼 토큰 프로필 폴백: profile={}", config.getJenniferProfileName());
                            }
                        });
            } catch (Exception e) {
                log.warn("[레포설정] 제니퍼 프로필 파싱 실패: {}", e.getMessage());
            }
        }
    }

    // ── 레포 삭제 ─────────────────────────────────────────
    @DeleteMapping("/repos/{id}")
    public ResponseEntity<?> deleteRepo(@PathVariable Long id) {
        if (!repoRepo.existsById(id)) return ResponseEntity.notFound().build();
        log.info("[레포설정 삭제] id={}", id);
        repoRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "삭제 완료"));
    }

    // ── YAML 파일 임포트 (경로 미입력 시 기본값 사용) ────
    @PostMapping("/import-yaml")
    public ResponseEntity<?> importYaml(@RequestBody Map<String, String> body) {
        String filePath = body.get("filePath");
        if (filePath == null || filePath.isBlank())
            filePath = yamlConfigService.getDefaultConfigPath();
        log.info("[YAML 임포트] 파일경로={}", filePath);
        try {
            Map<String, Object> result = yamlConfigService.importFromYaml(filePath.trim());
            log.info("[YAML 임포트 완료] 추가={}, 업데이트={}", result.get("added"), result.get("updated"));
            return ResponseEntity.ok(result);
        } catch (java.io.FileNotFoundException e) {
            log.warn("[YAML 임포트 실패] 파일 없음: {}", filePath);
            return ResponseEntity.badRequest().body(Map.of("error", "파일을 찾을 수 없습니다: " + filePath));
        } catch (Exception e) {
            log.error("[YAML 임포트 실패] {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", "임포트 실패: " + e.getMessage()));
        }
    }

    /** YAML 내용 직접 임포트 (파일 업로드용) */
    @PostMapping("/import-yaml-content")
    public ResponseEntity<?> importYamlContent(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "YAML 내용이 비어 있습니다."));
        log.info("[YAML 내용 임포트] 크기={}bytes", content.length());
        try {
            Map<String, Object> result = yamlConfigService.importFromYamlContent(content);
            log.info("[YAML 내용 임포트 완료] 추가={}, 업데이트={}", result.get("added"), result.get("updated"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[YAML 내용 임포트 실패] {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", "임포트 실패: " + e.getMessage()));
        }
    }

    // ── 포털 쪽지(관리자) ───────────────────────────────────
    @PostMapping("/portal-notice")
    public ResponseEntity<?> savePortalNotice(@RequestBody Map<String, String> body) {
        try {
            navNoticeService.setPortalNotice(body != null ? body.get("text") : null);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/assignee-notices")
    public ResponseEntity<?> sendAssigneeNotices(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> raw = body != null ? (List<Number>) body.get("assigneeIds") : null;
            List<Long> ids = new ArrayList<>();
            if (raw != null) {
                for (Number n : raw) {
                    if (n != null) ids.add(n.longValue());
                }
            }
            String text = body != null && body.get("text") != null ? body.get("text").toString() : "";
            int sent = navNoticeService.sendAssigneeNotices(ids, text);
            return ResponseEntity.ok(Map.of("success", true, "sent", sent));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin-inbox-summary")
    public ResponseEntity<?> adminInboxSummary() {
        return ResponseEntity.ok(navNoticeService.adminInboxSummary());
    }

    @PostMapping("/messages-from-assignees/{id}/dismiss")
    public ResponseEntity<?> dismissMessageFromAssignee(@PathVariable long id) {
        try {
            navNoticeService.dismissAssigneeToAdminMessage(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reply-to-assignee-message")
    public ResponseEntity<?> replyToAssigneeMessage(@RequestBody Map<String, Object> body) {
        try {
            if (body == null || body.get("assigneeMessageId") == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "assigneeMessageId 가 필요합니다."));
            }
            Object mid = body.get("assigneeMessageId");
            long assigneeMessageId;
            if (mid instanceof Number) {
                assigneeMessageId = ((Number) mid).longValue();
            } else {
                assigneeMessageId = Long.parseLong(mid.toString().trim());
            }
            String text = body.get("text") != null ? body.get("text").toString() : "";
            navNoticeService.replyFromAdminToAssigneeMessage(assigneeMessageId, text);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "assigneeMessageId 형식이 올바르지 않습니다."));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isExtractIssueDismissed(GlobalConfig gc) {
        if (gc == null) return false;
        LocalDateTime reportAt = gc.getExtractIssueReportAt();
        LocalDateTime dismissReportAt = gc.getExtractIssueDismissReportAt();
        return reportAt != null && dismissReportAt != null && reportAt.equals(dismissReportAt);
    }

    private String time(LocalDateTime value) {
        return value != null ? value.toString() : "";
    }
}
