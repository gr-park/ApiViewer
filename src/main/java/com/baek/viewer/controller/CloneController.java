package com.baek.viewer.controller;

import com.baek.viewer.service.CloneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clone")
public class CloneController {

    private static final Logger log = LoggerFactory.getLogger(CloneController.class);

    private final CloneService cloneService;

    public CloneController(CloneService cloneService) {
        this.cloneService = cloneService;
    }

    /** Bitbucket 레포 목록 조회 (페이지네이션) */
    @GetMapping("/repos")
    public ResponseEntity<?> listRepos(@RequestParam(defaultValue = "0") int start) {
        log.info("[Bitbucket 레포 조회] GET /api/clone/repos?start={}", start);
        try {
            return ResponseEntity.ok(cloneService.listRepos(start));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Bitbucket 레포 조회 오류] {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Clone 실행 (최대 5개)
     * Body: {"repos": [{"slug": "...", "cloneUrl": "..."}, ...]}
     */
    @PostMapping("/execute")
    public ResponseEntity<?> executeClone(@RequestBody Map<String, Object> body) {
        log.info("[Clone 실행] POST /api/clone/execute");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> repos = (List<Map<String, String>>) body.get("repos");
            String jobId = cloneService.startClone(repos);
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Clone 실행 오류] {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Clone 진행 상태 조회 */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        List<CloneService.RepoCloneStatus> statuses = cloneService.getJobStatus(jobId);
        if (statuses == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statuses.stream().map(CloneService.RepoCloneStatus::toMap).toList());
    }

    /**
     * sh 스크립트 생성
     * Body: {"repos": [{"slug": "...", "cloneUrl": "..."}, ...]}
     */
    @PostMapping("/script")
    public ResponseEntity<?> generateScript(@RequestBody Map<String, Object> body) {
        log.info("[Clone 스크립트 생성] POST /api/clone/script");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> repos = (List<Map<String, String>>) body.get("repos");
            if (repos == null || repos.isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "레포지토리를 선택하세요."));
            String script = cloneService.generateScript(repos);
            return ResponseEntity.ok(Map.of("script", script));
        } catch (Exception e) {
            log.error("[스크립트 생성 오류] {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
