package com.baek.viewer.controller;

import com.baek.viewer.service.CloneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

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

    /** 서버 파일시스템 디렉토리 목록 조회 (폴더 탐색기용) */
    @GetMapping("/dirs")
    public ResponseEntity<?> listDirs(@RequestParam(defaultValue = "") String path) {
        log.debug("[디렉토리 탐색] path={}", path);
        try {
            File dir = path.isBlank()
                    ? new File(System.getProperty("user.home"))
                    : new File(path);

            if (!dir.exists() || !dir.isDirectory()) {
                return ResponseEntity.badRequest().body(Map.of("error", "유효하지 않은 경로입니다: " + path));
            }

            String current = dir.getCanonicalPath();
            File parent = dir.getParentFile();
            String parentPath = parent != null ? parent.getCanonicalPath() : null;

            File[] children = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
            List<Map<String, String>> dirs = new ArrayList<>();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(f -> f.getName().toLowerCase()));
                for (File f : children) {
                    dirs.add(Map.of("name", f.getName(), "path", f.getCanonicalPath()));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("current", current);
            result.put("parent", parentPath);
            result.put("dirs", dirs);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[디렉토리 탐색 오류] path={}, error={}", path, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** OS 네이티브 폴더 선택 다이얼로그 실행 후 선택된 경로 반환 */
    @GetMapping("/pick-dir")
    public ResponseEntity<?> pickDir() {
        log.debug("[폴더 선택 다이얼로그] 실행 요청");
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("mac")) {
                pb = new ProcessBuilder("osascript", "-e", "POSIX path of (choose folder)");
            } else if (os.contains("win")) {
                pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "Add-Type -AssemblyName System.Windows.Forms;" +
                        "$f = New-Object System.Windows.Forms.FolderBrowserDialog;" +
                        "if ($f.ShowDialog() -eq 'OK') { $f.SelectedPath }");
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 운영체제입니다: " + os));
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            int exitCode = process.waitFor();

            if (exitCode != 0 || output.isBlank()) {
                log.debug("[폴더 선택 다이얼로그] 취소됨 또는 오류 (exitCode={})", exitCode);
                return ResponseEntity.ok(Map.of("cancelled", true));
            }

            // macOS osascript 결과 끝에 개행 포함 가능 → trim 처리됨
            log.debug("[폴더 선택 다이얼로그] 선택 경로: {}", output);
            return ResponseEntity.ok(Map.of("path", output));
        } catch (Exception e) {
            log.error("[폴더 선택 다이얼로그 오류] {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
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
