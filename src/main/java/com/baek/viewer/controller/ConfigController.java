package com.baek.viewer.controller;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.YamlConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final RepoConfigRepository repoRepo;
    private final GlobalConfigRepository globalRepo;
    private final YamlConfigService yamlConfigService;

    public ConfigController(RepoConfigRepository repoRepo,
                            GlobalConfigRepository globalRepo,
                            YamlConfigService yamlConfigService) {
        this.repoRepo = repoRepo;
        this.globalRepo = globalRepo;
        this.yamlConfigService = yamlConfigService;
    }

    // ── 공통 설정 ──────────────────────────────────────────
    @GetMapping("/global")
    public ResponseEntity<?> getGlobal() {
        log.info("[공통설정 조회] GET /api/config/global");
        return ResponseEntity.ok(globalRepo.findById(1L).orElse(new GlobalConfig()));
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
            ctx.getLogger("com.baek.viewer.service.WhatapApmService").setLevel(lv);
            ctx.getLogger("com.baek.viewer.service.JenniferApmService").setLevel(lv);
            ctx.getLogger("com.baek.viewer.service.ApiExtractorService").setLevel(lv);
            log.info("[로그레벨 변경] APM/분석 서비스 → {}", lv);
        } catch (Exception e) {
            log.warn("[APM 로그레벨 변경 실패] {}", e.getMessage());
        }
    }

    // ── 레포 설정 목록 ────────────────────────────────────
    @GetMapping("/repos")
    public ResponseEntity<?> listRepos() {
        log.info("[레포설정 목록 조회] GET /api/config/repos");
        return ResponseEntity.ok(repoRepo.findAllByOrderByRepoNameAsc());
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
        log.info("[레포설정 추가] repoName={}", config.getRepoName());
        return ResponseEntity.ok(repoRepo.save(config));
    }

    // ── 레포 수정 ─────────────────────────────────────────
    @PutMapping("/repos/{id}")
    public ResponseEntity<?> updateRepo(@PathVariable Long id, @RequestBody RepoConfig config) {
        if (!repoRepo.existsById(id)) return ResponseEntity.notFound().build();
        config.setId(id);
        log.info("[레포설정 수정] id={}, repoName={}", id, config.getRepoName());
        return ResponseEntity.ok(repoRepo.save(config));
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
}
