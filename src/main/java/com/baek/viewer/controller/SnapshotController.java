package com.baek.viewer.controller;

import com.baek.viewer.model.ApiRecordSnapshot;
import com.baek.viewer.repository.ApiRecordSnapshotRowRepository;
import com.baek.viewer.repository.ApiRecordSnapshotRepository;
import com.baek.viewer.service.SnapshotService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private static final Logger log = LoggerFactory.getLogger(SnapshotController.class);

    private final SnapshotService snapshotService;
    private final ApiRecordSnapshotRepository snapshotRepository;
    private final ApiRecordSnapshotRowRepository snapshotRowRepository;

    public SnapshotController(SnapshotService snapshotService,
                              ApiRecordSnapshotRepository snapshotRepository,
                              ApiRecordSnapshotRowRepository snapshotRowRepository) {
        this.snapshotService = snapshotService;
        this.snapshotRepository = snapshotRepository;
        this.snapshotRowRepository = snapshotRowRepository;
    }

    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        ip = ip.split(",")[0].trim();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";
        return ip;
    }

    /** 수동 스냅샷 생성 (전체 또는 repoName 지정). */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String type = body != null && body.get("type") != null ? String.valueOf(body.get("type")).trim() : "MANUAL";
        String label = body != null && body.get("label") != null ? String.valueOf(body.get("label")).trim() : null;
        String repoName = body != null && body.get("repoName") != null ? String.valueOf(body.get("repoName")).trim() : null;
        if (label != null && label.isBlank()) label = null;
        if (repoName != null && repoName.isBlank()) repoName = null;

        ApiRecordSnapshot s = snapshotService.createSnapshot(type, label, repoName, getClientIp(req));
        return ResponseEntity.ok(snapshotService.toMeta(s));
    }

    /** 스냅샷 목록 (기본 최근 200개, 날짜 범위 옵션) */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false, defaultValue = "0") int page,
                                  @RequestParam(required = false, defaultValue = "200") int size) {
        LocalDateTime f = null, t = null;
        try {
            if (from != null && !from.isBlank()) f = LocalDate.parse(from.trim()).atStartOfDay();
            if (to != null && !to.isBlank()) t = LocalDate.parse(to.trim()).atTime(LocalTime.MAX);
        } catch (Exception ignored) {}
        if (f == null) f = LocalDate.of(2000, 1, 1).atStartOfDay();
        if (t == null) t = LocalDate.of(2999, 12, 31).atTime(LocalTime.MAX);

        var p = snapshotRepository.findBySnapshotAtBetweenOrderBySnapshotAtDesc(f, t, PageRequest.of(Math.max(0, page), Math.max(1, size)));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", p.getTotalElements());
        resp.put("page", p.getNumber());
        resp.put("size", p.getSize());
        resp.put("totalPages", p.getTotalPages());
        resp.put("snapshots", p.getContent().stream().map(snapshotService::toMeta).toList());
        return ResponseEntity.ok(resp);
    }

    /** 스냅샷 메타 단건 조회 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return snapshotService.getSnapshot(id)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(snapshotService.toMeta(s)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "snapshot not found")));
    }

    /** 특정 스냅샷 시점의 URL 목록 조회 (페이지네이션 + 필터) */
    @GetMapping("/{id}/records")
    public ResponseEntity<?> records(@PathVariable Long id,
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     @RequestParam(required = false, defaultValue = "200") int size,
                                     @RequestParam(required = false) String repo,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String statusGroup,
                                     @RequestParam(required = false) String httpMethod,
                                     @RequestParam(required = false) String isDeprecated,
                                     @RequestParam(required = false) Boolean testSuspect,
                                     @RequestParam(required = false) Boolean markingIncomplete,
                                     @RequestParam(required = false) String q) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        List<String> repos = null;
        String r = blankToNull(repo);
        if (r != null) repos = List.of(r);
        Page<?> p = snapshotRowRepository.pageByFilters(id, repos,
                blankToNull(status), blankToNull(statusGroup), blankToNull(httpMethod), blankToNull(isDeprecated),
                testSuspect, markingIncomplete, blankToNull(q), pageable);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", p.getTotalElements());
        resp.put("page", p.getNumber());
        resp.put("size", p.getSize());
        resp.put("totalPages", p.getTotalPages());
        resp.put("records", p.getContent());
        return ResponseEntity.ok(resp);
    }

    /** 두 스냅샷 diff (new/deleted/changed/all) */
    @GetMapping("/diff")
    public ResponseEntity<?> diff(@RequestParam Long fromId,
                                  @RequestParam Long toId,
                                  @RequestParam(required = false, defaultValue = "all") String mode,
                                  @RequestParam(required = false) Integer limit) {
        try {
            return ResponseEntity.ok(snapshotService.diff(fromId, toId, mode, limit));
        } catch (Exception e) {
            log.error("[SNAPSHOT] diff 실패: from={}, to={}, err={}", fromId, toId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 보관주기 초과 스냅샷 정리 */
    @PostMapping("/cleanup")
    public ResponseEntity<?> cleanup(@RequestBody(required = false) Map<String, Object> body) {
        Integer retentionDays = null;
        try {
            if (body != null && body.get("retentionDays") != null) {
                retentionDays = Integer.parseInt(String.valueOf(body.get("retentionDays")));
            }
        } catch (Exception ignored) {}
        return ResponseEntity.ok(snapshotService.cleanupOldSnapshots(retentionDays));
    }

    /** 스냅샷 단건 삭제 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOne(@PathVariable Long id) {
        return ResponseEntity.ok(snapshotService.deleteSnapshot(id));
    }

    /** 스냅샷 선택 삭제 (다건) — /api/snapshots?ids=1,2,3 */
    @DeleteMapping
    public ResponseEntity<?> deleteMany(@RequestParam(required = false) String ids) {
        if (ids == null || ids.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids required"));
        }
        List<Long> arr = List.of(ids.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try { return Long.parseLong(s); } catch (Exception e) { return null; }
                })
                .filter(x -> x != null && x > 0)
                .toList();
        return ResponseEntity.ok(snapshotService.deleteSnapshots(arr));
    }

    /** 스냅샷 기간 삭제 (관리자) */
    @DeleteMapping("/by-date")
    public ResponseEntity<?> deleteByDate(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        LocalDateTime f = null, t = null;
        try { if (from != null && !from.isBlank()) f = LocalDate.parse(from.trim()).atStartOfDay(); } catch (Exception ignored) {}
        try { if (to != null && !to.isBlank()) t = LocalDate.parse(to.trim()).atTime(LocalTime.MAX); } catch (Exception ignored) {}
        return ResponseEntity.ok(snapshotService.deleteSnapshotsByDate(f, t));
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isBlank() ? null : s;
    }
}

