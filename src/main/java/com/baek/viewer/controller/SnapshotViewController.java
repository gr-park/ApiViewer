package com.baek.viewer.controller;

import com.baek.viewer.model.ApiRecordSnapshot;
import com.baek.viewer.repository.ApiRecordSnapshotRepository;
import com.baek.viewer.repository.ApiRecordSnapshotRowRepository;
import com.baek.viewer.service.SnapshotService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 스냅샷 조회 전용(읽기) API.
 * - viewer.html 에서 기준일자 조회 기능에 사용
 * - 쓰기(생성/삭제/정리/diff)는 /api/snapshots/** (관리자 보호) 경로에서 수행
 */
@RestController
@RequestMapping("/api/snapshot-view")
public class SnapshotViewController {

    private final ApiRecordSnapshotRepository snapshotRepository;
    private final ApiRecordSnapshotRowRepository snapshotRowRepository;
    private final SnapshotService snapshotService;

    public SnapshotViewController(ApiRecordSnapshotRepository snapshotRepository,
                                  ApiRecordSnapshotRowRepository snapshotRowRepository,
                                  SnapshotService snapshotService) {
        this.snapshotRepository = snapshotRepository;
        this.snapshotRowRepository = snapshotRowRepository;
        this.snapshotService = snapshotService;
    }

    /**
     * 기준일자(YYYY-MM-DD) 이전(<=) 중 가장 최신 스냅샷 1건 resolve.
     * repositories/repository 파라미터가 있으면 해당 repo 들 중 1개 이상 row가 존재하는 스냅샷으로 제한.
     */
    @GetMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestParam String date,
                                     @RequestParam(required = false) String repository,
                                     @RequestParam(required = false) String repositories) {
        LocalDate d;
        try {
            d = LocalDate.parse(date.trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "date는 YYYY-MM-DD 형식이어야 합니다."));
        }

        LocalDateTime cutoff = d.atTime(LocalTime.MAX);
        List<String> repos = parseRepos(repository, repositories);
        Long id = snapshotRepository.findLatestSnapshotIdAtOrBefore(cutoff, (repos == null || repos.isEmpty()) ? null : repos);
        if (id == null) return ResponseEntity.status(404).body(Map.of("error", "해당 기준일자 이전 스냅샷이 없습니다."));

        Optional<ApiRecordSnapshot> sOpt = snapshotRepository.findById(id);
        if (sOpt.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "snapshot not found"));

        Map<String, Object> resp = new LinkedHashMap<>(snapshotService.toMeta(sOpt.get()));
        resp.put("resolvedBy", Map.of(
                "date", d.toString(),
                "cutoff", cutoff.toString(),
                "repos", repos
        ));
        return ResponseEntity.ok(resp);
    }

    /** 특정 스냅샷 시점의 URL 목록 조회 (페이지네이션 + 필터) */
    @GetMapping("/{id}/records")
    public ResponseEntity<?> records(@PathVariable Long id,
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     @RequestParam(required = false, defaultValue = "200") int size,
                                     @RequestParam(required = false) String repository,
                                     @RequestParam(required = false) String repositories,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String statusGroup,
                                     @RequestParam(required = false) String httpMethod,
                                     @RequestParam(required = false) String isDeprecated,
                                     @RequestParam(required = false) Boolean testSuspect,
                                     @RequestParam(required = false) Boolean markingIncomplete,
                                     @RequestParam(required = false) String q) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        List<String> repos = parseRepos(repository, repositories);
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

    /** viewer 배지용 서버 집계 — 스냅샷 row 기준 COUNT 쿼리만 사용 */
    @GetMapping("/{id}/counts")
    public ResponseEntity<?> counts(@PathVariable Long id,
                                    @RequestParam(required = false) String repository,
                                    @RequestParam(required = false) String repositories) {
        long start = System.currentTimeMillis();
        List<String> repos = parseRepos(repository, repositories);
        boolean hasRepo = (repos != null && !repos.isEmpty());

        List<Object[]> statusRows = snapshotRowRepository.countGroupByStatus(id, hasRepo ? repos : null);
        List<Object[]> methodRows = snapshotRowRepository.countGroupByMethod(id, hasRepo ? repos : null);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0L;
        for (Object[] row : statusRows) {
            String s = row[0] != null ? row[0].toString() : "사용";
            long c = ((Number) row[1]).longValue();
            byStatus.put(s, c);
            total += c;
        }
        Map<String, Long> byMethod = new LinkedHashMap<>();
        for (Object[] row : methodRows) {
            byMethod.put(row[0] != null ? row[0].toString() : "?", ((Number) row[1]).longValue());
        }

        long newCount        = snapshotRowRepository.countNew(id, hasRepo ? repos : null);
        long changedCount    = snapshotRowRepository.countStatusChanged(id, hasRepo ? repos : null);
        long reviewedCount   = snapshotRowRepository.countReviewed(id, hasRepo ? repos : null);
        long deprecatedCount = snapshotRowRepository.countDeprecated(id, hasRepo ? repos : null);
        long markingIncompleteCount = snapshotRowRepository.countBlockMarkingIncomplete(id, hasRepo ? repos : null);
        long testSuspectCount = snapshotRowRepository.countTestSuspect(id, hasRepo ? repos : null);
        long priorityPureCount = byStatus.getOrDefault("①-① 차단대상", 0L);

        long blockResidual = byStatus.getOrDefault("①-① 차단대상", 0L);
        long blockException = byStatus.getOrDefault("①-② 담당자 판단", 0L)
                + byStatus.getOrDefault("①-③ 현업요청 제외대상", 0L)
                + byStatus.getOrDefault("①-④ 사용으로 변경", 0L);
        long reviewSum = byStatus.getOrDefault("②-① 호출0건+변경있음", 0L)
                + byStatus.getOrDefault("②-② 호출 3건 이하+변경없음", 0L)
                + byStatus.getOrDefault("②-③ 사용으로 변경", 0L);
        Map<String, Long> byCategory = new LinkedHashMap<>();
        byCategory.put("totalExcludingDeleted", total);
        byCategory.put("use",            byStatus.getOrDefault("사용", 0L));
        byCategory.put("blockDone",      byStatus.getOrDefault("차단완료", 0L));
        byCategory.put("blockResidual",  blockResidual);
        byCategory.put("blockException", blockException);
        byCategory.put("review",         reviewSum);
        byCategory.put("deleted",        0L);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total",        total);
        response.put("deletedCount", 0L);
        response.put("newCount",     newCount);
        response.put("changedCount", changedCount);
        response.put("reviewedCount", reviewedCount);
        response.put("deprecated",   deprecatedCount);
        response.put("markingIncompleteCount", markingIncompleteCount);
        response.put("testSuspectCount", testSuspectCount);
        response.put("byStatus",     byStatus);
        response.put("byCategory",   byCategory);
        response.put("byMethod",     byMethod);
        response.put("priorityPureCount", priorityPureCount);

        response.put("snapshotId", id);
        response.put("repos", repos);
        response.put("tookMs", System.currentTimeMillis() - start);
        return ResponseEntity.ok(response);
    }

    private List<String> parseRepos(String repository, String repositories) {
        List<String> repos = null;
        if (repositories != null && !repositories.isBlank()) {
            repos = Arrays.stream(repositories.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (repos.isEmpty()) repos = null;
        }
        if (repos == null && repository != null && !repository.isBlank()) repos = List.of(repository.trim());
        return repos;
    }

    private String blankToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isBlank() ? null : s;
    }
}

