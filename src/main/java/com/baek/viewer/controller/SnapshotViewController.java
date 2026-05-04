package com.baek.viewer.controller;

import com.baek.viewer.model.ApiRecordSnapshot;
import com.baek.viewer.repository.ApiRecordSnapshotRepository;
import com.baek.viewer.repository.ApiRecordSnapshotRowRepository;
import com.baek.viewer.service.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;

/**
 * 스냅샷 조회 전용(읽기) API.
 * - viewer.html 에서 기준일자 조회 기능에 사용
 * - 쓰기(생성/삭제/정리/diff)는 /api/snapshots/** (관리자 보호) 경로에서 수행
 */
@RestController
@RequestMapping("/api/snapshot-view")
public class SnapshotViewController {

    private static final Logger log = LoggerFactory.getLogger(SnapshotViewController.class);

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
     * 특정 날짜(YYYY-MM-DD) 하루 범위의 스냅샷 목록(최신순).
     * [정책] 스냅샷은 항상 '시점 기준 전체(풀)'이므로 repositories/repository 파라미터는 무시한다.
     *        (응답 메타에는 요청된 값을 그대로 표기 — 클라이언트 호환)
     */
    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestParam String date,
                                  @RequestParam(required = false) String repository,
                                  @RequestParam(required = false) String repositories,
                                  @RequestParam(required = false, defaultValue = "200") int size) {
        LocalDate d;
        try {
            d = LocalDate.parse(date.trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "date는 YYYY-MM-DD 형식이어야 합니다."));
        }

        int limit = Math.min(500, Math.max(1, size));
        LocalDateTime from = d.atStartOfDay();
        LocalDateTime to = d.atTime(LocalTime.MAX);
        List<String> requestedRepos = parseRepos(repository, repositories);

        try {
            // 일자 범위 내 모든 스냅샷(=전체 풀) 최신순 — PG는 (:repos IS NULL OR IN(:repos)) 타입 추론 실패하므로 레포 미필터 전용 쿼리 사용
            List<Long> ids = snapshotRepository.findIdsBySnapshotAtBetween(from, to, PageRequest.of(0, limit));
            Map<Long, ApiRecordSnapshot> byId = new HashMap<>();
            for (ApiRecordSnapshot s : snapshotRepository.findAllById(ids)) {
                byId.put(s.getId(), s);
            }

            List<Map<String, Object>> metas = ids.stream()
                    .map(byId::get)
                    .filter(x -> x != null)
                    .map(snapshotService::toMeta)
                    .toList();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("date", d.toString());
            resp.put("from", from.toString());
            resp.put("to", to.toString());
            resp.put("requestedRepos", requestedRepos == null ? List.of() : requestedRepos);
            resp.put("size", limit);
            resp.put("snapshots", metas);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[SNAPSHOT_VIEW] list 실패: date={}, requestedRepos={}, err={}", d, requestedRepos, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 기준일자(YYYY-MM-DD) 이전(<=) 중 가장 최신 스냅샷 1건 resolve.
     * [정책] 항상 '전체(풀) 스냅샷'만 사용한다. repositories/repository 파라미터는 무시(응답 메타에만 표기).
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
        List<String> requestedRepos = parseRepos(repository, repositories);

        // 전체 스냅샷(sourceRepo 비어있음)만 단일 경로로 resolve
        Long id = snapshotRepository.findLatestGlobalSnapshotIdAtOrBefore(cutoff);
        if (id == null) return ResponseEntity.status(404).body(Map.of("error", "해당 기준일자 이전 스냅샷이 없습니다."));

        Optional<ApiRecordSnapshot> sOpt = snapshotRepository.findById(id);
        if (sOpt.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "snapshot not found"));

        Map<String, Object> resp = new LinkedHashMap<>(snapshotService.toMeta(sOpt.get()));
        Map<String, Object> resolvedBy = new LinkedHashMap<>();
        resolvedBy.put("date", d.toString());
        resolvedBy.put("cutoff", cutoff.toString());
        resolvedBy.put("requestedRepos", requestedRepos == null ? List.of() : requestedRepos);
        resp.put("resolvedBy", resolvedBy);
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
                                     @RequestParam(required = false) Boolean pathParams,
                                     @RequestParam(required = false) Boolean markingIncomplete,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size), parseSnapshotSort(sort));
        List<String> repos = parseRepos(repository, repositories);
        Page<?> p = snapshotRowRepository.pageByFilters(id, repos,
                blankToNull(status), blankToNull(statusGroup), blankToNull(httpMethod), blankToNull(isDeprecated),
                testSuspect, pathParams, markingIncomplete, blankToNull(q), pageable);

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
        long deleted = 0L;
        for (Object[] row : statusRows) {
            String s = row[0] != null ? row[0].toString() : "사용";
            long c = ((Number) row[1]).longValue();
            byStatus.put(s, c);
            if ("삭제".equals(s)) deleted = c;
            else total += c;
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
        long pathParamPatternCount = snapshotRowRepository.countPathParamPattern(id, hasRepo ? repos : null);
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
        byCategory.put("deleted",        deleted);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total",        total);
        response.put("deletedCount", deleted);
        response.put("newCount",     newCount);
        response.put("changedCount", changedCount);
        response.put("reviewedCount", reviewedCount);
        response.put("deprecated",   deprecatedCount);
        response.put("markingIncompleteCount", markingIncompleteCount);
        response.put("testSuspectCount", testSuspectCount);
        response.put("pathParamPatternCount", pathParamPatternCount);
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

    private static final Set<String> SNAPSHOT_SORT_FIELDS = Set.of(
            "id", "apiPath", "httpMethod", "status", "callCount", "callCountMonth", "callCountWeek",
            "lastAnalyzedAt", "modifiedAt", "blockedDate", "repositoryName", "teamOverride", "managerOverride",
            "cboScheduledDate", "deployScheduledDate", "pathParamPattern", "methodName", "controllerName");

    private static Sort parseSnapshotSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.unsorted();
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (field.isEmpty() || !SNAPSHOT_SORT_FIELDS.contains(field)) {
            return Sort.unsorted();
        }
        Sort.Direction dir = (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(dir, field);
    }
}

