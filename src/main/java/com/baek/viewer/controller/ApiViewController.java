package com.baek.viewer.controller;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ApiRecordStatsDto;
import com.baek.viewer.model.ApiRecordSummary;
import com.baek.viewer.model.BlockOverviewDto;
import com.baek.viewer.model.DeployScheduleDto;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.ExtractRequest;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.model.WhatapRequest;
import com.baek.viewer.model.WhatapResult;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.ApiExtractorService;
import com.baek.viewer.service.ApiStorageService;
import com.baek.viewer.service.WhatapService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.persistence.criteria.Predicate;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiViewController {

    private static final Logger log = LoggerFactory.getLogger(ApiViewController.class);

    private final ApiExtractorService extractorService;
    private final WhatapService whatapService;
    private final ApiRecordRepository recordRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final GlobalConfigRepository globalConfigRepository;
    private final ApiStorageService storageService;
    private final com.baek.viewer.service.AuthService authService;

    public ApiViewController(ApiExtractorService extractorService,
                             WhatapService whatapService,
                             ApiRecordRepository recordRepository,
                             RepoConfigRepository repoConfigRepository,
                             GlobalConfigRepository globalConfigRepository,
                             ApiStorageService storageService,
                             com.baek.viewer.service.AuthService authService) {
        this.extractorService = extractorService;
        this.whatapService = whatapService;
        this.recordRepository = recordRepository;
        this.repoConfigRepository = repoConfigRepository;
        this.globalConfigRepository = globalConfigRepository;
        this.storageService = storageService;
        this.authService = authService;
    }

    /** 토큰 유효성 확인 (페이지 로드 시 자동 검증용) + 남은 수명 반환 */
    @GetMapping("/auth/check")
    public ResponseEntity<?> checkAuth(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        boolean valid = token != null && authService.isValid(token);
        long remainingMs = valid ? authService.remainingMs(token) : 0L;
        return ResponseEntity.ok(Map.of("valid", valid, "remainingMs", remainingMs));
    }

    /** 비밀번호 확인 → 토큰 발급 + 쿠키 설정 */
    @PostMapping({"/verify-password", "/auth/verify"})
    public ResponseEntity<?> verifyPassword(@RequestBody Map<String, String> body,
                                            jakarta.servlet.http.HttpServletResponse response) {
        String input = body.get("password");
        String stored = globalConfigRepository.findById(1L)
                .map(g -> g.getPassword()).orElse(null);
        if (stored == null || stored.isBlank()) stored = "lotte1!";
        boolean ok = stored.equals(input);
        if (ok) {
            String token = authService.issueToken();
            log.info("[인증 성공] 토큰 발급");
            // HTML 페이지 보호용 쿠키 (세션 쿠키 — 브라우저 종료 시 삭제)
            // Set-Cookie 직접 작성: SameSite=Lax 명시 (크로스사이트 안전 + 도메인/ngrok 호환)
            String cookieValue = String.format("adminToken=%s; Path=/; SameSite=Lax", token);
            response.addHeader("Set-Cookie", cookieValue);
            long remainingMs = authService.remainingMs(token);
            return ResponseEntity.ok(Map.of("valid", true, "token", token, "remainingMs", remainingMs));
        }
        log.warn("[인증 실패] 비밀번호 불일치");
        return ResponseEntity.ok(Map.of("valid", false));
    }

    /** 로그아웃 — 서버 토큰 폐기 + 쿠키 제거 */
    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                     jakarta.servlet.http.HttpServletResponse response) {
        authService.revoke(token);
        // 쿠키 만료 — Max-Age=0
        response.addHeader("Set-Cookie", "adminToken=; Path=/; Max-Age=0; SameSite=Lax");
        log.info("[로그아웃] 토큰 폐기 완료");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        ip = ip.split(",")[0].trim();
        // IPv6 localhost → IPv4 변환
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";
        return ip;
    }

    /** 추출 실행 (비동기) */
    @PostMapping("/extract")
    public ResponseEntity<?> extract(@RequestBody ExtractRequest request, HttpServletRequest httpReq) {
        try {
            request.setClientIp(getClientIp(httpReq));
            log.info("[추출 시작] repo={}, ip={}", request.getRepositoryName(), request.getClientIp());
            extractorService.startExtractAsync(request);
            return ResponseEntity.accepted().body(Map.of("message", "추출 시작됨"));
        } catch (IllegalStateException e) {
            log.warn("[추출 실패] 중복 실행: {}", e.getMessage());
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[추출 오류] {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 추출 진행상황 조회 */
    @GetMapping("/progress")
    public ResponseEntity<?> progress() {
        return ResponseEntity.ok(extractorService.getProgress());
    }

    /** 캐시된 결과 조회 */
    @GetMapping("/list")
    public ResponseEntity<?> list() {
        List<ApiInfo> apis = extractorService.getCached();
        Map<String, Object> response = new HashMap<>();
        response.put("total", apis.size());
        response.put("deprecated", apis.stream().filter(a -> "Y".equals(a.getIsDeprecated())).count());
        response.put("apis", apis);
        return ResponseEntity.ok(response);
    }

    /** 추출 상태 확인 */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "extracting", extractorService.isExtracting(),
                "count", extractorService.getCached().size()
        ));
    }

    /** DB 저장된 레포지토리 목록 */
    @GetMapping("/db/repositories")
    public ResponseEntity<?> dbRepositories() {
        return ResponseEntity.ok(recordRepository.findAllRepositoryNames());
    }

    /** 페이징 미지정 전체 로드 시 최대 행수 — 초과하면 safety cap 적용 */
    private static final int MAX_UNPAGED_ROWS = 50_000;

    /**
     * 차단/추가검토대상 leaf 8종 — viewer 의 blockTargetOnly 필터 / 배포 분포 / Jira 동기화 후보 등에 사용.
     * (1)-(5) 현업요청 차단제외는 reviewResult 자동 → 일반 차단 워크플로우에서는 제외되지만 배포 등 전체 집계에 포함.
     */
    private static final List<String> BLOCK_TARGET_STATUSES = List.of(
            "(1)-(2) 호출0건+변경없음",
            "(1)-(3) 호출0건+변경있음(로그)",
            "(1)-(4) 업무종료",
            "(1)-(5) 현업요청 차단제외",
            "(2)-(1) 호출0건+로그건",
            "(2)-(2) 호출0건+변경있음",
            "(2)-(3) 호출 1~reviewThreshold건",
            "(2)-(4) 호출 reviewThreshold+1건↑");

    /** DB에서 API 목록 조회 — 경량 프로젝션 (fullComment, controllerComment, blockedReason 제외)
     *
     * 파라미터:
     *  - repository:  레포 필터 (단일)
     *  - repositories: 레포 필터 (복수, 콤마 구분 — 팀/담당자 resolve 용)
     *  - blockTargetOnly: 차단대상(3종) 만
     *  - status:     특정 상태 필터 (단일)
     *  - httpMethod: HTTP 메소드 필터
     *  - q:          검색어 (apiPath / methodName / memo LIKE)
     *  - alert:      "new" / "changed" / "reviewed" / "deleted" 필터
     *  - page, size: 지정 시 서버 페이지네이션 모드 ({apis, total, page, size, totalPages} 반환)
     *  - sort:       정렬 필드 (예: "id,desc") — 페이지 모드에서만 사용
     *
     * 페이지 모드 미지정 + 전체 로드가 MAX_UNPAGED_ROWS 초과 시 413 응답 + pagination 권고.
     */
    @GetMapping("/db/apis")
    public ResponseEntity<?> dbApis(@RequestParam(required = false) String repository,
                                     @RequestParam(required = false) String repositories,
                                     @RequestParam(required = false, defaultValue = "false") boolean blockTargetOnly,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Boolean logWorkExcluded,
                                     @RequestParam(required = false) Boolean recentLogOnly,
                                     @RequestParam(required = false) String httpMethod,
                                     @RequestParam(required = false) String isDeprecated,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String alert,
                                     @RequestParam(required = false) String ids,
                                     @RequestParam(required = false) String modifiedFrom,
                                     @RequestParam(required = false) String modifiedTo,
                                     @RequestParam(required = false) String cboFrom,
                                     @RequestParam(required = false) String cboTo,
                                     @RequestParam(required = false) String deployFrom,
                                     @RequestParam(required = false) String deployTo,
                                     @RequestParam(required = false) String deployManager,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size,
                                     @RequestParam(required = false) String sort) {
        long start = System.currentTimeMillis();
        boolean paged = (page != null);
        boolean hasRepo = repository != null && !repository.isBlank();

        // 복수 레포 필터 파싱
        List<String> repoList = null;
        if (repositories != null && !repositories.isBlank()) {
            repoList = Arrays.stream(repositories.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (repoList.isEmpty()) repoList = null;
        }

        // 동적 필터가 하나라도 있으면 Specification 경로 사용
        boolean hasDynamicFilter = (status != null && !status.isBlank())
                || (logWorkExcluded != null)
                || (recentLogOnly != null)
                || (httpMethod != null && !httpMethod.isBlank())
                || (isDeprecated != null && !isDeprecated.isBlank())
                || (q != null && !q.isBlank())
                || (alert != null && !alert.isBlank())
                || repoList != null
                || (ids != null && !ids.isBlank())
                || (modifiedFrom != null && !modifiedFrom.isBlank())
                || (modifiedTo != null && !modifiedTo.isBlank())
                || (cboFrom != null && !cboFrom.isBlank())
                || (cboTo != null && !cboTo.isBlank())
                || (deployFrom != null && !deployFrom.isBlank())
                || (deployTo != null && !deployTo.isBlank())
                || (deployManager != null && !deployManager.isBlank());

        if (paged || hasDynamicFilter) {
            int pageIdx  = paged ? Math.max(0, page) : 0;
            int pageSize = (size != null && size > 0) ? Math.min(size, 1000) : 200;
            Sort sortSpec = parseSort(sort);
            Pageable pageable = PageRequest.of(pageIdx, pageSize, sortSpec);

            Specification<ApiRecord> spec = buildSpec(repository, repoList, blockTargetOnly,
                    status, logWorkExcluded, recentLogOnly, httpMethod, isDeprecated, q, alert, ids,
                    modifiedFrom, modifiedTo, cboFrom, cboTo, deployFrom, deployTo, deployManager);

            Page<ApiRecord> entityPage = recordRepository.findAll(spec, pageable);
            // 엔티티 → 경량 summary Map 변환 (TEXT 필드 강제 제외)
            List<Map<String, Object>> summaryList = entityPage.getContent().stream()
                    .map(ApiViewController::toSummaryMap)
                    .collect(Collectors.toList());

            log.info("[목록 조회·필터] repo={}, status={}, method={}, q={}, alert={}, page={}/{}, size={}, total={}, 소요={}ms",
                    repository, status, httpMethod, q, alert, pageIdx, entityPage.getTotalPages(), pageSize,
                    entityPage.getTotalElements(), System.currentTimeMillis() - start);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("apis",       summaryList);
            response.put("total",      entityPage.getTotalElements());
            response.put("page",       pageIdx);
            response.put("size",       pageSize);
            response.put("totalPages", entityPage.getTotalPages());
            response.put("paged",      true);
            return ResponseEntity.ok(response);
        }

        // 비페이징(전량) 경로 — 대용량 safety cap
        if (!blockTargetOnly && !hasRepo) {
            long totalRows = recordRepository.count();
            if (totalRows > MAX_UNPAGED_ROWS) {
                log.warn("[목록 조회·safety cap] 전체={}건이 한계({})를 초과 — 페이지 모드 권고",
                        totalRows, MAX_UNPAGED_ROWS);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "데이터가 너무 많습니다. 레포 필터 또는 page/size 파라미터를 사용하세요.");
                err.put("total", totalRows);
                err.put("limit", MAX_UNPAGED_ROWS);
                err.put("pagingRequired", true);
                return ResponseEntity.status(413).body(err);
            }
        }

        List<ApiRecordSummary> records;
        if (blockTargetOnly) {
            records = recordRepository.findSummaryByStatusIn(BLOCK_TARGET_STATUSES);
        } else if (hasRepo) {
            records = recordRepository.findSummaryByRepositoryName(repository);
        } else {
            records = recordRepository.findAllSummary();
        }
        log.info("[목록 조회] repo={}, blockTargetOnly={}, 건수={}, 소요={}ms",
                repository, blockTargetOnly, records.size(), System.currentTimeMillis() - start);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", records.size());
        response.put("apis",  records);
        response.put("paged", false);
        return ResponseEntity.ok(response);
    }

    /**
     * 업로드 결과 필터용 — ID 배열을 바디로 받아 페이지 조회 (URL 길이 제한 우회)
     * Body: { "ids": [1,2,...], "page": 0, "size": 100, "sort": "id,desc" }
     */
    @PostMapping("/db/apis/by-ids")
    public ResponseEntity<?> dbApisByIds(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "ids 필수"));

        List<Long> idList = rawIds.stream().map(Number::longValue).toList();
        if (idList.size() > 5000)
            return ResponseEntity.badRequest().body(Map.of("error", "한 번에 조회 가능한 ID는 최대 5,000건입니다."));

        int pageIdx  = body.containsKey("page")  ? ((Number) body.get("page")).intValue()  : 0;
        int pageSize = body.containsKey("size")  ? ((Number) body.get("size")).intValue()  : 100;
        pageSize = Math.min(Math.max(pageSize, 1), 1000);
        String sortStr = body.containsKey("sort") ? (String) body.get("sort") : null;
        Sort sortSpec = parseSort(sortStr);
        Pageable pageable = PageRequest.of(pageIdx, pageSize, sortSpec);

        Specification<ApiRecord> spec = (root, query, cb) -> root.get("id").in(idList);
        Page<ApiRecord> entityPage = recordRepository.findAll(spec, pageable);

        List<Map<String, Object>> summaryList = entityPage.getContent().stream()
                .map(ApiViewController::toSummaryMap)
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apis",       summaryList);
        response.put("total",      entityPage.getTotalElements());
        response.put("page",       pageIdx);
        response.put("size",       pageSize);
        response.put("totalPages", entityPage.getTotalPages());
        response.put("paged",      true);
        return ResponseEntity.ok(response);
    }

    /** 동적 필터 Specification 빌더 */
    private Specification<ApiRecord> buildSpec(String repository, List<String> repoList, boolean blockTargetOnly,
                                                String status, Boolean logWorkExcluded, Boolean recentLogOnly,
                                                String httpMethod, String isDeprecated, String q, String alert,
                                                String ids, String modifiedFrom, String modifiedTo,
                                                String cboFrom, String cboTo,
                                                String deployFrom, String deployTo,
                                                String deployManager) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (repository != null && !repository.isBlank()) {
                ps.add(cb.equal(root.get("repositoryName"), repository));
            }
            if (repoList != null && !repoList.isEmpty()) {
                ps.add(root.get("repositoryName").in(repoList));
            }
            if (blockTargetOnly) {
                ps.add(root.get("status").in(BLOCK_TARGET_STATUSES));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (logWorkExcluded != null) {
                // logWorkExcluded=false 는 null 또는 false 모두 매칭 (과거 row 의 null 호환)
                if (logWorkExcluded) {
                    ps.add(cb.isTrue(root.get("logWorkExcluded")));
                } else {
                    ps.add(cb.or(
                            cb.isNull(root.get("logWorkExcluded")),
                            cb.isFalse(root.get("logWorkExcluded"))));
                }
            }
            if (recentLogOnly != null) {
                if (recentLogOnly) {
                    ps.add(cb.isTrue(root.get("recentLogOnly")));
                } else {
                    ps.add(cb.or(
                            cb.isNull(root.get("recentLogOnly")),
                            cb.isFalse(root.get("recentLogOnly"))));
                }
            }
            if (httpMethod != null && !httpMethod.isBlank()) {
                ps.add(cb.equal(root.get("httpMethod"), httpMethod));
            }
            if (isDeprecated != null && !isDeprecated.isBlank()) {
                ps.add(cb.equal(root.get("isDeprecated"), isDeprecated));
            }
            if (q != null && !q.isBlank()) {
                String pat = "%" + q.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("apiPath")),             pat),
                        cb.like(cb.lower(root.get("methodName")),          pat),
                        cb.like(cb.lower(root.get("apiOperationValue")),   pat),
                        cb.like(cb.lower(root.get("descriptionTag")),      pat),
                        cb.like(cb.lower(root.get("descriptionOverride")), pat),
                        cb.like(cb.lower(root.get("fullComment")),         pat),
                        cb.like(cb.lower(root.get("memo")),                pat)
                ));
            }
            if (alert != null && !alert.isBlank()) {
                switch (alert) {
                    case "new"      -> ps.add(cb.isTrue(root.get("isNew")));
                    case "changed"  -> ps.add(cb.isTrue(root.get("statusChanged")));
                    case "marking-incomplete" -> ps.add(cb.isTrue(root.get("blockMarkingIncomplete")));
                    case "reviewed" -> ps.add(cb.and(
                            cb.isNotNull(root.get("reviewResult")),
                            cb.notEqual(root.get("reviewResult"), "")));
                    case "deleted"  -> ps.add(cb.equal(root.get("status"), "삭제"));
                    default -> {}
                }
            }
            if (ids != null && !ids.isBlank()) {
                List<Long> idList = Arrays.stream(ids.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Long::parseLong).toList();
                if (!idList.isEmpty()) ps.add(root.get("id").in(idList));
            }
            if (modifiedFrom != null && !modifiedFrom.isBlank()) {
                try {
                    LocalDateTime from = LocalDateTime.parse(modifiedFrom.replace(" ", "T"));
                    ps.add(cb.greaterThanOrEqualTo(root.get("modifiedAt"), from));
                } catch (Exception ignored) {}
            }
            if (modifiedTo != null && !modifiedTo.isBlank()) {
                try {
                    LocalDateTime to = LocalDateTime.parse(modifiedTo.replace(" ", "T"));
                    ps.add(cb.lessThanOrEqualTo(root.get("modifiedAt"), to));
                } catch (Exception ignored) {}
            }
            // CBO 예정일자 / 배포 예정일자 범위 필터 (yyyy-MM-dd)
            if (cboFrom != null && !cboFrom.isBlank()) {
                try { ps.add(cb.greaterThanOrEqualTo(root.get("cboScheduledDate"), LocalDate.parse(cboFrom))); }
                catch (Exception ignored) {}
            }
            if (cboTo != null && !cboTo.isBlank()) {
                try { ps.add(cb.lessThanOrEqualTo(root.get("cboScheduledDate"), LocalDate.parse(cboTo))); }
                catch (Exception ignored) {}
            }
            if (deployFrom != null && !deployFrom.isBlank()) {
                try { ps.add(cb.greaterThanOrEqualTo(root.get("deployScheduledDate"), LocalDate.parse(deployFrom))); }
                catch (Exception ignored) {}
            }
            if (deployTo != null && !deployTo.isBlank()) {
                try { ps.add(cb.lessThanOrEqualTo(root.get("deployScheduledDate"), LocalDate.parse(deployTo))); }
                catch (Exception ignored) {}
            }
            if (deployManager != null && !deployManager.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("deployManager")), "%" + deployManager.toLowerCase() + "%"));
            }
            // alert!="deleted" 기본: 삭제 제외
            if (alert == null || !"deleted".equals(alert)) {
                ps.add(cb.or(cb.isNull(root.get("status")), cb.notEqual(root.get("status"), "삭제")));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** ApiRecord → 경량 summary Map — TEXT 컬럼(fullComment 등) 응답에서 제외 */
    private static Map<String, Object> toSummaryMap(ApiRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                 r.getId());
        m.put("repositoryName",     r.getRepositoryName());
        m.put("apiPath",            r.getApiPath());
        m.put("httpMethod",         r.getHttpMethod());
        m.put("lastAnalyzedAt",     r.getLastAnalyzedAt());
        m.put("createdIp",          r.getCreatedIp());
        m.put("modifiedAt",         r.getModifiedAt());
        m.put("modifiedIp",         r.getModifiedIp());
        m.put("reviewedIp",         r.getReviewedIp());
        m.put("status",             r.getStatus());
        m.put("statusOverridden",   r.isStatusOverridden());
        m.put("logWorkExcluded",    r.isLogWorkExcluded());
        m.put("recentLogOnly",      r.isRecentLogOnly());
        m.put("blockTarget",        r.getBlockTarget());
        m.put("blockCriteria",      r.getBlockCriteria());
        m.put("callCount",          r.getCallCount());
        m.put("callCountMonth",     r.getCallCountMonth());
        m.put("callCountWeek",      r.getCallCountWeek());
        m.put("methodName",         r.getMethodName());
        m.put("controllerName",     r.getControllerName());
        m.put("repoPath",           r.getRepoPath());
        m.put("isDeprecated",       r.getIsDeprecated());
        m.put("hasUrlBlock",        r.getHasUrlBlock());
        m.put("blockMarkingIncomplete", r.isBlockMarkingIncomplete());
        m.put("programId",          r.getProgramId());
        m.put("apiOperationValue",  r.getApiOperationValue());
        m.put("descriptionTag",     r.getDescriptionTag());
        m.put("fullUrl",            r.getFullUrl());
        m.put("memo",               r.getMemo());
        m.put("reviewResult",       r.getReviewResult());
        m.put("reviewOpinion",      r.getReviewOpinion());
        m.put("cboScheduledDate",   r.getCboScheduledDate());
        m.put("deployScheduledDate", r.getDeployScheduledDate());
        m.put("deployCsr",          r.getDeployCsr());
        m.put("deployManager",      r.getDeployManager());
        m.put("reviewTeam",         r.getReviewTeam());
        m.put("reviewManager",      r.getReviewManager());
        m.put("reviewedAt",         r.getReviewedAt());
        m.put("blockedDate",        r.getBlockedDate());
        m.put("statusChanged",      r.isStatusChanged());
        m.put("isNew",              r.isNew());
        m.put("dataSource",         r.getDataSource());
        m.put("statusChangeLog",    r.getStatusChangeLog());
        m.put("teamOverride",       r.getTeamOverride());
        m.put("managerOverride",    r.getManagerOverride());
        m.put("descriptionOverride", r.getDescriptionOverride());
        m.put("gitHistory",         r.getGitHistory());
        // Excel 내보내기 / 상세 표시에 필요한 TEXT 필드 (페이지 단위라 부담 작음)
        m.put("fullComment",        r.getFullComment());
        m.put("controllerComment",  r.getControllerComment());
        m.put("blockedReason",      r.getBlockedReason());
        m.put("requestPropertyValue",           r.getRequestPropertyValue());
        m.put("controllerRequestPropertyValue", r.getControllerRequestPropertyValue());
        m.put("controllerFilePath", r.getControllerFilePath());
        m.put("jiraIssueKey",       r.getJiraIssueKey());
        m.put("jiraIssueUrl",       r.getJiraIssueUrl());
        m.put("jiraSyncedAt",       r.getJiraSyncedAt());
        return m;
    }

    /** 정렬 파라미터 파싱: "id,desc" / "apiPath,asc" / null → UNSORTED */
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.unsorted();
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (field.isEmpty()) return Sort.unsorted();
        Sort.Direction dir = (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(dir, field);
    }

    /** viewer 배지용 서버 집계 — 전량 로드 없이 COUNT 쿼리만 사용 */
    @GetMapping("/db/apis/counts")
    public ResponseEntity<?> dbApisCounts(@RequestParam(required = false) String repository,
                                          @RequestParam(required = false) String repositories) {
        long start = System.currentTimeMillis();

        // 단일/복수 레포 파라미터 통합 → repos 리스트로 정규화
        List<String> repos = null;
        if (repositories != null && !repositories.isBlank()) {
            repos = Arrays.stream(repositories.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (repos.isEmpty()) repos = null;
        }
        if (repos == null && repository != null && !repository.isBlank()) {
            repos = List.of(repository);
        }
        boolean hasRepo = repos != null;
        final List<String> repoFilter = repos;

        List<Object[]> statusRows = hasRepo
                ? recordRepository.countGroupByStatusForRepos(repoFilter)
                : recordRepository.countGroupByStatus();
        List<Object[]> methodRows = hasRepo
                ? recordRepository.countGroupByMethodForRepos(repoFilter)
                : recordRepository.countGroupByMethod();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0L, deleted = 0L;
        for (Object[] row : statusRows) {
            String s = row[0] != null ? row[0].toString() : "사용";
            long c = ((Number) row[1]).longValue();
            byStatus.put(s, c);
            if ("삭제".equals(s)) deleted = c; else total += c;
        }
        Map<String, Long> byMethod = new LinkedHashMap<>();
        for (Object[] row : methodRows) {
            byMethod.put(row[0] != null ? row[0].toString() : "?", ((Number) row[1]).longValue());
        }

        long newCount        = hasRepo ? recordRepository.countNewForRepos(repoFilter)             : recordRepository.countNew();
        long changedCount    = hasRepo ? recordRepository.countStatusChangedForRepos(repoFilter)   : recordRepository.countStatusChanged();
        long reviewedCount   = hasRepo ? recordRepository.countReviewedForRepos(repoFilter)        : recordRepository.countReviewed();
        long deprecatedCount = hasRepo ? recordRepository.countDeprecatedForRepos(repoFilter)      : recordRepository.countDeprecated();
        long markingIncompleteCount = hasRepo
                ? recordRepository.countBlockMarkingIncompleteForRepos(repoFilter)
                : recordRepository.countBlockMarkingIncomplete();
        // (1)-(2) 호출0건+변경없음 — 옛 "최우선 차단대상" + logWorkExcluded=false. 이제 status 자체가 leaf 이므로 단순 countByStatus.
        long priorityPureCount = byStatus.getOrDefault("(1)-(2) 호출0건+변경없음", 0L);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total",        total);       // 삭제 제외
        response.put("deletedCount", deleted);
        response.put("newCount",     newCount);
        response.put("changedCount", changedCount);
        response.put("reviewedCount", reviewedCount);
        response.put("deprecated",   deprecatedCount);
        response.put("markingIncompleteCount", markingIncompleteCount);
        response.put("byStatus",     byStatus);
        response.put("byMethod",     byMethod);
        response.put("priorityPureCount", priorityPureCount);

        log.info("[목록 카운트] repos={}, total={}, 삭제={}, 소요={}ms", repoFilter, total, deleted, System.currentTimeMillis() - start);
        return ResponseEntity.ok(response);
    }

    /** 전체 선택/벌크 작업용 — 현재 필터에 해당하는 ID 목록만 반환 (경량) */
    @GetMapping("/db/apis/ids")
    public ResponseEntity<?> dbApisIds(@RequestParam(required = false) String repository,
                                        @RequestParam(required = false, defaultValue = "false") boolean blockTargetOnly) {
        long start = System.currentTimeMillis();
        List<Long> ids;
        if (blockTargetOnly) {
            ids = recordRepository.findIdsByStatusIn(BLOCK_TARGET_STATUSES);
        } else if (repository != null && !repository.isBlank()) {
            ids = recordRepository.findIdsByRepositoryName(repository);
        } else {
            ids = recordRepository.findAllIds();
        }
        log.info("[ID 목록] repo={}, blockTargetOnly={}, 건수={}, 소요={}ms",
                repository, blockTargetOnly, ids.size(), System.currentTimeMillis() - start);
        return ResponseEntity.ok(Map.of("total", ids.size(), "ids", ids));
    }

    /** 단건 상세 조회 (전체 필드 포함) */
    @GetMapping("/db/record/{id}")
    public ResponseEntity<?> getRecord(@PathVariable Long id) {
        log.info("[단건 조회] GET /api/db/record/{}", id);
        return recordRepository.findById(id)
                .map(r -> ResponseEntity.ok((Object) r))
                .orElse(ResponseEntity.notFound().build());
    }

    /** 일괄 변경 (상태/차단대상/차단대상기준/현업검토 등)
     * Body: { "ids": [1,2,3], "status": "차단완료", "blockTarget": "최우선 차단대상", "logWorkExcluded": true, ... }
     * ids 외 필드가 존재하면 해당 필드를 변경합니다. null/빈값은 해제.
     */
    @PatchMapping("/db/status")
    public ResponseEntity<?> updateStatus(@RequestBody Map<String, Object> body, HttpServletRequest httpReq) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
            }
            List<Long> ids = rawIds.stream().map(i -> i.longValue()).toList();

            Map<String, Object> fields = new LinkedHashMap<>(body);
            fields.remove("ids");

            int updated = storageService.updateBulk(ids, fields, getClientIp(httpReq));
            return ResponseEntity.ok(Map.of("updated", updated));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 알림 플래그 일괄 해제 — isNew + statusChanged 모두 (차단완료건 포함)
     *  대용량 최적화: findById 루프 제거, @Modifying 벌크 UPDATE 2건으로 처리. */
    @PatchMapping("/db/clear-alerts")
    public ResponseEntity<?> clearAlerts(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
            }
            List<Long> ids = rawIds.stream().map(Integer::longValue).toList();
            int clearedNew = 0, clearedStatus = 0;
            // IN 절 파라미터 한계 회피 위해 1000건씩 청크 분할
            for (int i = 0; i < ids.size(); i += 1000) {
                List<Long> chunk = ids.subList(i, Math.min(i + 1000, ids.size()));
                clearedNew    += recordRepository.bulkClearIsNew(chunk);
                clearedStatus += recordRepository.bulkClearStatusChanged(chunk);
            }
            int cleared = Math.max(clearedNew, clearedStatus);
            log.info("[알림 일괄해제] 대상={}건, isNew해제={}, statusChanged해제={}", ids.size(), clearedNew, clearedStatus);
            return ResponseEntity.ok(Map.of("cleared", cleared));
        } catch (Exception e) {
            log.error("[알림 일괄해제 실패] {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 상태변경 플래그 해제 (IT 담당자 확인 후)
     *  대용량 최적화: @Modifying 벌크 UPDATE. */
    @PatchMapping("/db/clear-status-change")
    public ResponseEntity<?> clearStatusChange(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
            }
            List<Long> ids = rawIds.stream().map(Integer::longValue).toList();
            int cleared = 0;
            for (int i = 0; i < ids.size(); i += 1000) {
                List<Long> chunk = ids.subList(i, Math.min(i + 1000, ids.size()));
                cleared += recordRepository.bulkClearStatusChanged(chunk);
            }
            log.info("[상태변경 플래그 해제] 대상={}건, 해제={}건", ids.size(), cleared);
            return ResponseEntity.ok(Map.of("cleared", cleared));
        } catch (Exception e) {
            log.error("[상태변경 플래그 해제 실패] {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 개별 레코드 필드 수정 (memo, reviewResult, reviewOpinion 등)
     * Body: { "memo": "내용", "reviewResult": "차단대상 제외", "reviewOpinion": "의견" }
     */
    @PatchMapping("/db/record/{id}")
    public ResponseEntity<?> updateRecord(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest httpReq) {
        try {
            String ip = getClientIp(httpReq);
            ApiRecord r = recordRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("레코드를 찾을 수 없습니다: " + id));

            // 확정완료(statusOverridden=true) 건은 내용/상태 수정 불가. 단, 확인 플래그 해제는 허용.
            if (r.isStatusOverridden()) {
                java.util.Set<String> allowed = java.util.Set.of("isNew", "statusChanged", "statusOverridden");
                boolean onlyFlagOps = !body.isEmpty() && body.keySet().stream().allMatch(allowed::contains);
                if (!onlyFlagOps) {
                    return ResponseEntity.badRequest().body(Map.of("error", "확정완료 상태의 레코드는 수정할 수 없습니다. 먼저 확정을 해제해 주세요."));
                }
            }

            boolean anyChanged = false;
            boolean reviewChanged = false;
            if (body.containsKey("isNew"))           { r.setNew(!Boolean.FALSE.equals(body.get("isNew")) && Boolean.parseBoolean(String.valueOf(body.get("isNew")))); }
            if (body.containsKey("statusOverridden")) {
                Object val = body.get("statusOverridden");
                r.setStatusOverridden(val instanceof Boolean ? (Boolean) val : "true".equals(String.valueOf(val)));
                anyChanged = true;
            }
            if (body.containsKey("status")) {
                String st = body.get("status") != null ? body.get("status").toString().trim() : null;
                if (st != null && !st.isBlank()) {
                    r.setStatus(st);
                    anyChanged = true;
                }
            }
            if (body.containsKey("blockedDate")) {
                String ds = body.get("blockedDate") != null ? body.get("blockedDate").toString().trim() : "";
                r.setBlockedDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds)); anyChanged = true;
            }
            if (body.containsKey("blockedReason"))  { r.setBlockedReason(body.get("blockedReason") != null ? body.get("blockedReason").toString() : null); anyChanged = true; }
            if (body.containsKey("blockCriteria"))  { r.setBlockCriteria(body.get("blockCriteria") != null ? body.get("blockCriteria").toString() : null); anyChanged = true; }
            if (body.containsKey("isDeprecated"))   { r.setIsDeprecated(body.get("isDeprecated") != null ? body.get("isDeprecated").toString() : null); anyChanged = true; }
            if (body.containsKey("memo"))            { r.setMemo(body.get("memo") != null ? body.get("memo").toString() : null); anyChanged = true; }
            if (body.containsKey("teamOverride"))    { r.setTeamOverride(body.get("teamOverride") != null ? body.get("teamOverride").toString() : null); anyChanged = true; }
            if (body.containsKey("managerOverride")) {
                Object mv = body.get("managerOverride");
                String mgrVal = (mv == null) ? null : mv.toString();
                if (mgrVal != null && mgrVal.isBlank()) mgrVal = null;
                r.setManagerOverride(mgrVal);
                // 수동 지정 플래그: 값 있으면 ON (매핑이 덮어쓰지 않음), 비우면 OFF (매핑 재갱신 허용)
                r.setManagerOverridden(mgrVal != null);
                anyChanged = true;
            }
            if (body.containsKey("descriptionOverride")) {
                Object v = body.get("descriptionOverride");
                String s = v == null ? null : v.toString().trim();
                r.setDescriptionOverride(s == null || s.isEmpty() ? null : s);
                anyChanged = true;
            }
            if (body.containsKey("reviewResult"))    { r.setReviewResult(body.get("reviewResult") != null ? body.get("reviewResult").toString() : null); anyChanged = true; reviewChanged = true; }
            if (body.containsKey("reviewOpinion"))   { r.setReviewOpinion(body.get("reviewOpinion") != null ? body.get("reviewOpinion").toString() : null); anyChanged = true; reviewChanged = true; }
            if (body.containsKey("cboScheduledDate")) {
                String ds = body.get("cboScheduledDate") != null ? body.get("cboScheduledDate").toString().trim() : "";
                r.setCboScheduledDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds)); anyChanged = true;
            }
            if (body.containsKey("deployScheduledDate")) {
                String ds = body.get("deployScheduledDate") != null ? body.get("deployScheduledDate").toString().trim() : "";
                r.setDeployScheduledDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds)); anyChanged = true;
            }
            if (body.containsKey("deployCsr")) { r.setDeployCsr(body.get("deployCsr") != null ? body.get("deployCsr").toString() : null); anyChanged = true; }
            if (body.containsKey("reviewTeam"))      { r.setReviewTeam(body.get("reviewTeam") != null ? body.get("reviewTeam").toString() : null); anyChanged = true; reviewChanged = true; }
            if (body.containsKey("reviewManager"))   { r.setReviewManager(body.get("reviewManager") != null ? body.get("reviewManager").toString() : null); anyChanged = true; reviewChanged = true; }

            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            if (anyChanged) {
                // 모든 사용자 변경 시 modifiedAt 갱신
                r.setModifiedAt(now);
                r.setModifiedIp(ip);
            }
            if (reviewChanged) {
                // 현업 검토 필드는 reviewedAt/reviewedIp도 추가로 기록
                r.setReviewedAt(now);
                r.setReviewedIp(ip);
            }

            recordRepository.save(r);
            log.info("[레코드 수정] id={}, 필드={}", id, body.keySet());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("modifiedAt", r.getModifiedAt() != null ? r.getModifiedAt().toString() : null);
            resp.put("reviewedAt", r.getReviewedAt() != null ? r.getReviewedAt().toString() : null);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("[레코드 수정 실패] id={}, 오류={}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 현업검토 엑셀 업로드 — 공개 엔드포인트 (비관리자 접근 가능).
     * Body: [{ repositoryName, apiPath, httpMethod, reviewResult, reviewOpinion, reviewTeam, reviewManager }]
     * 응답: { matched, unmatched:[{repositoryName, apiPath, httpMethod}], skipped }
     */
    @PatchMapping("/db/review/bulk")
    public ResponseEntity<?> bulkUploadReview(@RequestBody List<Map<String, String>> items, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int matched = 0, skipped = 0;
        List<Map<String, String>> unmatched = new ArrayList<>();

        for (Map<String, String> item : items) {
            String repoName  = item.get("repositoryName");
            String apiPath   = item.get("apiPath");
            String httpMethod = item.get("httpMethod");
            String reviewResult  = item.get("reviewResult");
            String reviewOpinion = item.get("reviewOpinion");
            String reviewTeam    = item.get("reviewTeam");
            String reviewManager = item.get("reviewManager");

            if (repoName == null || apiPath == null || httpMethod == null) {
                skipped++;
                continue;
            }

            Optional<ApiRecord> opt = recordRepository.findByRepositoryNameAndApiPathAndHttpMethod(repoName, apiPath, httpMethod);
            if (opt.isEmpty()) {
                Map<String, String> u = new LinkedHashMap<>();
                u.put("repositoryName", repoName);
                u.put("apiPath", apiPath);
                u.put("httpMethod", httpMethod);
                unmatched.add(u);
                continue;
            }

            ApiRecord r = opt.get();
            boolean changed = false;
            if (reviewResult  != null) { r.setReviewResult(reviewResult.isBlank()  ? null : reviewResult);  changed = true; }
            if (reviewOpinion != null) { r.setReviewOpinion(reviewOpinion.isBlank() ? null : reviewOpinion); changed = true; }
            if (reviewTeam    != null) { r.setReviewTeam(reviewTeam.isBlank()    ? null : reviewTeam);    changed = true; }
            if (reviewManager != null) { r.setReviewManager(reviewManager.isBlank() ? null : reviewManager); changed = true; }

            if (changed) {
                r.setReviewedAt(now);
                r.setReviewedIp(ip);
                r.setModifiedAt(now);
                r.setModifiedIp(ip);
                recordRepository.save(r);
                matched++;
            } else {
                skipped++;
            }
        }

        log.info("[현업검토 일괄 업로드] 매칭={}건, 미매칭={}건, 스킵={}건, ip={}", matched, unmatched.size(), skipped, ip);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("matched",   matched);
        resp.put("unmatched", unmatched);
        resp.put("skipped",   skipped);
        return ResponseEntity.ok(resp);
    }

    /** Whatap 호출건수 DB 반영
     * Body: { "repoName": "my-project", "callCounts": { "/api/foo": 10, "/api/bar": 0 } }
     */
    @PostMapping("/db/call-counts")
    public ResponseEntity<?> updateCallCounts(@RequestBody Map<String, Object> body) {
        try {
            String repoName = (String) body.get("repoName");
            @SuppressWarnings("unchecked")
            Map<String, Object> rawCounts = (Map<String, Object>) body.get("callCounts");

            if (repoName == null || repoName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "repoName이 필요합니다."));
            }
            if (rawCounts == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "callCounts가 필요합니다."));
            }

            Map<String, Long> pathToCount = new HashMap<>();
            rawCounts.forEach((k, v) -> {
                if (v instanceof Number n) pathToCount.put(k, n.longValue());
            });

            storageService.updateCallCounts(repoName, pathToCount);
            return ResponseEntity.ok(Map.of("updated", pathToCount.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** DB 전체 통계 (삭제건은 합계에서 제외, 별도 카운트)
     * 대용량 최적화: 경량 DTO 쿼리로 TEXT 컬럼 제외 로드 + countByStatus 로 삭제 건수만 별도 집계. */
    @GetMapping("/db/stats")
    public ResponseEntity<?> dbStats() {
        long start = System.currentTimeMillis();

        // 삭제 제외 전체 — 경량 DTO (status, httpMethod, repoName, team/managerOverride, apiPath, lastAnalyzedAt 만 로드)
        List<ApiRecordStatsDto> all = recordRepository.findAllForStats();
        long deletedCount = recordRepository.countByStatus("삭제");

        // repoName → RepoConfig 매핑
        Map<String, RepoConfig> repoConfigMap = repoConfigRepository.findAll().stream()
                .collect(Collectors.toMap(RepoConfig::getRepoName, r -> r, (a, b) -> a));

        // 상태별
        Map<String, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getStatus() != null ? r.getStatus() : "사용",
                        Collectors.counting()));

        // (1)-(2) 호출0건+변경없음 전체 카운트 — UI 에서 별도 표시용
        long priorityPureCount = all.stream()
                .filter(r -> "(1)-(2) 호출0건+변경없음".equals(r.getStatus()))
                .count();

        // HTTP Method별 (전체)
        Map<String, Long> byMethod = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getHttpMethod() != null ? r.getHttpMethod() : "?",
                        Collectors.counting()));

        // effectiveTeam 함수
        java.util.function.BiFunction<ApiRecordStatsDto, Map<String, RepoConfig>, String> effectiveTeam = (r, cfgMap) -> {
            if (r.getTeamOverride() != null && !r.getTeamOverride().isBlank()) return r.getTeamOverride();
            RepoConfig c = cfgMap.get(r.getRepositoryName());
            return (c != null && c.getTeamName() != null && !c.getTeamName().isBlank()) ? c.getTeamName() : "(팀 미지정)";
        };

        // 레포지토리별 — (팀, 레포) 조합으로 그룹핑하여 팀이 다르면 별도 행
        Map<String, List<ApiRecordStatsDto>> byTeamRepoGroup = all.stream()
                .collect(Collectors.groupingBy(r -> effectiveTeam.apply(r, repoConfigMap) + "|" + r.getRepositoryName()));

        List<Map<String, Object>> byRepo = byTeamRepoGroup.entrySet().stream()
                .sorted((a, b) -> {
                    String[] ka = a.getKey().split("\\|", 2);
                    String[] kb = b.getKey().split("\\|", 2);
                    int tc = ka[0].compareTo(kb[0]);
                    return tc != 0 ? tc : Integer.compare(b.getValue().size(), a.getValue().size());
                })
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    String teamVal = parts[0];
                    String repoName = parts[1];
                    List<ApiRecordStatsDto> records = e.getValue();
                    RepoConfig cfg = repoConfigMap.get(repoName);

                    Map<String, Long> statusDetail = records.stream()
                            .collect(Collectors.groupingBy(
                                    r -> r.getStatus() != null ? r.getStatus() : "사용",
                                    Collectors.counting()));
                    Map<String, Long> methodDetail = records.stream()
                            .collect(Collectors.groupingBy(
                                    r -> r.getHttpMethod() != null ? r.getHttpMethod() : "?",
                                    Collectors.counting()));
                    long groupPriorityPure = records.stream()
                            .filter(r -> "(1)-(2) 호출0건+변경없음".equals(r.getStatus()))
                            .count();
                    String lastDate = records.stream()
                            .filter(r -> r.getLastAnalyzedAt() != null)
                            .map(r -> r.getLastAnalyzedAt().toString().replace("T"," ").substring(0, Math.min(r.getLastAnalyzedAt().toString().length(), 16)))
                            .max(Comparator.naturalOrder()).orElse("-");

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("repo",          repoName);
                    m.put("count",         records.size());
                    m.put("team",          teamVal);
                    m.put("businessName",  cfg != null && cfg.getBusinessName()  != null ? cfg.getBusinessName()  : "-");
                    m.put("lastAnalyzedDate", lastDate);
                    m.put("statusDetail",  statusDetail);
                    m.put("priorityPure",  groupPriorityPure);
                    m.put("methodDetail",  methodDetail);
                    return m;
                }).collect(Collectors.toList());

        // 팀별 (teamOverride 우선)
        Map<String, Long> byTeamCount = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byTeamStatus = new LinkedHashMap<>();
        Map<String, Long> byTeamPriorityPure = new LinkedHashMap<>();
        all.forEach(r -> {
            String team = effectiveTeam.apply(r, repoConfigMap);
            byTeamCount.merge(team, 1L, Long::sum);
            String status = r.getStatus() != null ? r.getStatus() : "사용";
            byTeamStatus.computeIfAbsent(team, k -> new LinkedHashMap<>()).merge(status, 1L, Long::sum);
            if ("(1)-(2) 호출0건+변경없음".equals(status)) {
                byTeamPriorityPure.merge(team, 1L, Long::sum);
            }
        });
        List<Map<String, Object>> byTeam = byTeamCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team",         e.getKey());
                    m.put("count",        e.getValue());
                    m.put("statusDetail", byTeamStatus.getOrDefault(e.getKey(), Map.of()));
                    m.put("priorityPure", byTeamPriorityPure.getOrDefault(e.getKey(), 0L));
                    return m;
                }).collect(Collectors.toList());

        // 담당자별 (managerOverride > 프로그램ID매핑 > 팀대표)
        // 레포별 매핑 JSON을 미리 1회 파싱 후 캐시 (레코드마다 파싱 방지)
        Map<String, List<Map<String, String>>> mappingCache = new HashMap<>();
        for (RepoConfig cfg : repoConfigMap.values()) {
            mappingCache.put(cfg.getRepoName(), parseManagerMappings(cfg.getManagerMappings()));
        }

        Map<String, Long> byManagerCount = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byManagerStatus = new LinkedHashMap<>();
        Map<String, String> managerToTeam = new LinkedHashMap<>();
        Map<String, Long> byManagerPriorityPure = new LinkedHashMap<>();
        all.forEach(r -> {
            RepoConfig cfg = repoConfigMap.get(r.getRepositoryName());
            List<Map<String, String>> mappings = mappingCache.getOrDefault(r.getRepositoryName(), List.of());
            String mgr = resolveManager(r.getManagerOverride(), r.getApiPath(), cfg, mappings);
            String team = effectiveTeam.apply(r, repoConfigMap);
            byManagerCount.merge(mgr, 1L, Long::sum);
            managerToTeam.putIfAbsent(mgr, team);
            String status = r.getStatus() != null ? r.getStatus() : "사용";
            byManagerStatus.computeIfAbsent(mgr, k -> new LinkedHashMap<>()).merge(status, 1L, Long::sum);
            if ("(1)-(2) 호출0건+변경없음".equals(status)) {
                byManagerPriorityPure.merge(mgr, 1L, Long::sum);
            }
        });
        List<Map<String, Object>> byManager = byManagerCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("manager",      e.getKey());
                    m.put("team",         managerToTeam.getOrDefault(e.getKey(), "-"));
                    m.put("count",        e.getValue());
                    m.put("statusDetail", byManagerStatus.getOrDefault(e.getKey(), Map.of()));
                    m.put("priorityPure", byManagerPriorityPure.getOrDefault(e.getKey(), 0L));
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total",         all.size());       // 활성 URL (삭제 제외)
        result.put("deletedCount",  deletedCount);     // 삭제 이력 (별도)
        result.put("byStatus",      byStatus);
        result.put("priorityPureCount", priorityPureCount);
        result.put("byMethod",      byMethod);
        result.put("byRepo",        byRepo);
        result.put("byTeam",        byTeam);
        result.put("byManager",     byManager);
        log.info("[통계 조회] 건수={}, 삭제={}, 소요={}ms", all.size(), deletedCount, System.currentTimeMillis() - start);
        return ResponseEntity.ok(result);
    }

    /**
     * 배포일자 분포 통계 — 차단완료 + 차단대상(최우선/후순위/검토필요대상) 만 집계.
     * 행: 팀/담당자/레포 별 (탭). 열: 배포일자(YYYY-MM-DD) + "예정"(차단대상 중 일자 미정) + "차단완료".
     * 담당자 컬럼은 deployManager 우선, 없으면 managerOverride → 매핑 → 팀대표 폴백.
     */
    @GetMapping("/db/stats/deploy-schedule")
    public ResponseEntity<?> dbDeployScheduleStats() {
        long start = System.currentTimeMillis();

        List<String> targetStatuses = new ArrayList<>(BLOCK_TARGET_STATUSES);
        targetStatuses.add("(1)-(1) 차단완료");
        List<DeployScheduleDto> all = recordRepository.findForDeploySchedule(targetStatuses);

        Map<String, RepoConfig> repoConfigMap = repoConfigRepository.findAll().stream()
                .collect(Collectors.toMap(RepoConfig::getRepoName, r -> r, (a, b) -> a));
        Map<String, List<Map<String, String>>> mappingCache = new HashMap<>();
        for (RepoConfig cfg : repoConfigMap.values()) {
            mappingCache.put(cfg.getRepoName(), parseManagerMappings(cfg.getManagerMappings()));
        }

        java.util.function.Function<DeployScheduleDto, String> teamOf = r -> {
            if (r.getTeamOverride() != null && !r.getTeamOverride().isBlank()) return r.getTeamOverride();
            RepoConfig c = repoConfigMap.get(r.getRepositoryName());
            return (c != null && c.getTeamName() != null && !c.getTeamName().isBlank()) ? c.getTeamName() : "(팀 미지정)";
        };
        java.util.function.Function<DeployScheduleDto, String> managerOf = r -> {
            if (r.getDeployManager() != null && !r.getDeployManager().isBlank()) return r.getDeployManager();
            RepoConfig c = repoConfigMap.get(r.getRepositoryName());
            List<Map<String, String>> mappings = mappingCache.getOrDefault(r.getRepositoryName(), List.of());
            return resolveManager(r.getManagerOverride(), r.getApiPath(), c, mappings);
        };

        // 모든 차단대상 레코드의 deployScheduledDate (null 제외) 수집 → 정렬된 unique 컬럼
        List<String> dateColumns = all.stream()
                .filter(r -> BLOCK_TARGET_STATUSES.contains(r.getStatus()))
                .map(DeployScheduleDto::getDeployScheduledDate)
                .filter(Objects::nonNull)
                .map(LocalDate::toString)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // 그룹별 집계 헬퍼 — keyFn 으로 그룹 키 추출 후 byDate / scheduled / completed / total 누적
        java.util.function.BiFunction<java.util.function.Function<DeployScheduleDto, String>,
                List<DeployScheduleDto>,
                Map<String, Map<String, Object>>> aggregate = (keyFn, records) -> {
            Map<String, Map<String, Object>> acc = new LinkedHashMap<>();
            for (DeployScheduleDto r : records) {
                String key = keyFn.apply(r);
                Map<String, Object> bucket = acc.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("byDate", new LinkedHashMap<String, Long>());
                    m.put("scheduled", 0L);
                    m.put("completed", 0L);
                    m.put("total", 0L);
                    return m;
                });
                bucket.put("total", (Long) bucket.get("total") + 1L);
                if ("(1)-(1) 차단완료".equals(r.getStatus())) {
                    bucket.put("completed", (Long) bucket.get("completed") + 1L);
                } else if (r.getDeployScheduledDate() == null) {
                    bucket.put("scheduled", (Long) bucket.get("scheduled") + 1L);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Long> byDate = (Map<String, Long>) bucket.get("byDate");
                    byDate.merge(r.getDeployScheduledDate().toString(), 1L, Long::sum);
                }
            }
            return acc;
        };

        // 팀별
        Map<String, Map<String, Object>> teamAcc = aggregate.apply(teamOf, all);
        List<Map<String, Object>> byTeam = teamAcc.entrySet().stream()
                .sorted((a, b) -> Long.compare((Long) b.getValue().get("total"), (Long) a.getValue().get("total")))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team", e.getKey());
                    m.putAll(e.getValue());
                    return m;
                }).collect(Collectors.toList());

        // 담당자별 — 그룹 키는 팀+담당자 조합 (팀 다르면 별도 행)
        Map<String, String> mgrToTeam = new HashMap<>();
        java.util.function.Function<DeployScheduleDto, String> mgrKey = r -> {
            String t = teamOf.apply(r);
            String m = managerOf.apply(r);
            String composite = t + "|" + m;
            mgrToTeam.putIfAbsent(composite, t);
            return composite;
        };
        Map<String, Map<String, Object>> mgrAcc = aggregate.apply(mgrKey, all);
        List<Map<String, Object>> byManager = mgrAcc.entrySet().stream()
                .sorted((a, b) -> Long.compare((Long) b.getValue().get("total"), (Long) a.getValue().get("total")))
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team",    parts[0]);
                    m.put("manager", parts.length > 1 ? parts[1] : "(미지정)");
                    m.putAll(e.getValue());
                    return m;
                }).collect(Collectors.toList());

        // 레포별 — 그룹 키는 팀+레포명 조합 (호환: 다른 팀 오버라이드가 있으면 별도 행)
        java.util.function.Function<DeployScheduleDto, String> repoKey = r -> teamOf.apply(r) + "|" + r.getRepositoryName();
        Map<String, Map<String, Object>> repoAcc = aggregate.apply(repoKey, all);
        List<Map<String, Object>> byRepo = repoAcc.entrySet().stream()
                .sorted((a, b) -> {
                    String[] ka = a.getKey().split("\\|", 2);
                    String[] kb = b.getKey().split("\\|", 2);
                    int tc = ka[0].compareTo(kb[0]);
                    return tc != 0 ? tc : Long.compare((Long) b.getValue().get("total"), (Long) a.getValue().get("total"));
                })
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    String teamVal = parts[0];
                    String repoName = parts[1];
                    RepoConfig cfg = repoConfigMap.get(repoName);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team",         teamVal);
                    m.put("repo",         repoName);
                    m.put("businessName", cfg != null && cfg.getBusinessName() != null ? cfg.getBusinessName() : "-");
                    m.putAll(e.getValue());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates",     dateColumns);
        result.put("byTeam",    byTeam);
        result.put("byManager", byManager);
        result.put("byRepo",    byRepo);
        result.put("totalRecords", all.size());

        log.info("[배포일자 통계] 건수={}, 일자수={}, 소요={}ms",
                all.size(), dateColumns.size(), System.currentTimeMillis() - start);
        return ResponseEntity.ok(result);
    }

    /**
     * 차단대상 진행사항 대시보드 — 사용 / (1) 차단대상 / (2) 추가검토대상 3-tier × 13컬럼 그룹별 집계.
     * 모든 분류는 leaf status 직접 매칭 (보조 플래그 분기 없음).
     *
     * 컬럼:
     *   사용: status='사용'
     *   (1) 차단대상:
     *     (1)-(1) 차단완료
     *     (1)-(2) 호출0건+변경없음
     *     (1)-(3) 호출0건+변경있음(로그)
     *     (1)-(4) 업무종료
     *     (1)-(5) 현업요청 차단제외
     *     (1)-(6) "차단대상 → 사용" (수동, 라벨 그대로)
     *   (2) 추가검토대상:
     *     (2)-(1) 호출0건+로그건
     *     (2)-(2) 호출0건+변경있음
     *     (2)-(3) 호출 1~reviewThreshold건
     *     (2)-(4) 호출 reviewThreshold+1건↑
     *     (2)-(5) "검토필요 → 사용" (수동, 라벨 그대로)
     */
    @GetMapping("/db/stats/block-overview")
    public ResponseEntity<?> dbBlockOverview() {
        long start = System.currentTimeMillis();
        int reviewThreshold = globalConfigRepository.findById(1L)
                .map(GlobalConfig::getReviewThreshold).orElse(3);

        List<BlockOverviewDto> all = recordRepository.findForBlockOverview();

        Map<String, RepoConfig> repoConfigMap = repoConfigRepository.findAll().stream()
                .collect(Collectors.toMap(RepoConfig::getRepoName, r -> r, (a, b) -> a));
        Map<String, List<Map<String, String>>> mappingCache = new HashMap<>();
        for (RepoConfig cfg : repoConfigMap.values()) {
            mappingCache.put(cfg.getRepoName(), parseManagerMappings(cfg.getManagerMappings()));
        }

        java.util.function.Function<BlockOverviewDto, String> teamOf = r -> {
            if (r.getTeamOverride() != null && !r.getTeamOverride().isBlank()) return r.getTeamOverride();
            RepoConfig c = repoConfigMap.get(r.getRepositoryName());
            return (c != null && c.getTeamName() != null && !c.getTeamName().isBlank()) ? c.getTeamName() : "(팀 미지정)";
        };
        java.util.function.Function<BlockOverviewDto, String> managerOf = r -> {
            RepoConfig c = repoConfigMap.get(r.getRepositoryName());
            List<Map<String, String>> mappings = mappingCache.getOrDefault(r.getRepositoryName(), List.of());
            return resolveManager(r.getManagerOverride(), r.getApiPath(), c, mappings);
        };

        // 그룹 키별 13컬럼 카운터 누적 헬퍼 (Map<key, int[13]>)
        java.util.function.BiFunction<java.util.function.Function<BlockOverviewDto, String>,
                List<BlockOverviewDto>, Map<String, long[]>> aggregate = (keyFn, recs) -> {
            Map<String, long[]> acc = new LinkedHashMap<>();
            for (BlockOverviewDto r : recs) {
                long[] c = acc.computeIfAbsent(keyFn.apply(r), k -> new long[13]);
                String s = r.getStatus();

                // 0: 사용
                if ("사용".equals(s)) c[0]++;
                // 1: (1)-(1) 차단완료
                else if ("(1)-(1) 차단완료".equals(s)) c[1]++;
                // 2: (1)-(2) 호출0건+변경없음
                else if ("(1)-(2) 호출0건+변경없음".equals(s)) c[2]++;
                // 3: (1)-(3) 호출0건+변경있음(로그)
                else if ("(1)-(3) 호출0건+변경있음(로그)".equals(s)) c[3]++;
                // 4: (1)-(4) 업무종료
                else if ("(1)-(4) 업무종료".equals(s)) c[4]++;
                // 5: (1)-(5) 현업요청 차단제외
                else if ("(1)-(5) 현업요청 차단제외".equals(s)) c[5]++;
                // 6: (1)-(6) = "차단대상 → 사용" 수동
                else if ("차단대상 → 사용".equals(s)) c[6]++;
                // 7: (2)-(1) 호출0건+로그건
                else if ("(2)-(1) 호출0건+로그건".equals(s)) c[7]++;
                // 8: (2)-(2) 호출0건+변경있음
                else if ("(2)-(2) 호출0건+변경있음".equals(s)) c[8]++;
                // 9: (2)-(3) 호출 1~reviewThreshold건
                else if ("(2)-(3) 호출 1~reviewThreshold건".equals(s)) c[9]++;
                // 10: (2)-(4) 호출 reviewThreshold+1건↑
                else if ("(2)-(4) 호출 reviewThreshold+1건↑".equals(s)) c[10]++;
                // 11: (2)-(5) = "검토필요 → 사용" 수동
                else if ("검토필요 → 사용".equals(s)) c[11]++;

                // 12: 총합 (각 행의 grandTotal)
                c[12]++;
            }
            return acc;
        };

        java.util.function.Function<Map.Entry<String, long[]>, Map<String, Object>> rowOf = e -> {
            long[] c = e.getValue();
            long blockTotal = c[1] + c[2] + c[3] + c[4] + c[5] + c[6];
            long holdTotal  = c[7] + c[8] + c[9] + c[10] + c[11];
            Map<String, Object> m = new LinkedHashMap<>();
            // upper-tier counts
            m.put("use",        c[0]);
            m.put("blockTotal", blockTotal);
            m.put("blockDone",  c[1]);
            m.put("btTopPure",  c[2]);
            m.put("btTopLog",  c[3]);
            m.put("btLow",      c[4]);
            m.put("exReview",   c[5]);
            m.put("exManUse",   c[6]);
            m.put("holdTotal",  holdTotal);
            m.put("rev0Log",    c[7]);
            m.put("rev0Chg",    c[8]);
            m.put("revLow",     c[9]);
            m.put("revHigh",    c[10]);
            m.put("holdManUse", c[11]);
            m.put("grandTotal", c[12]);
            return m;
        };

        // 팀별
        Map<String, long[]> teamAcc = aggregate.apply(teamOf, all);
        List<Map<String, Object>> byTeam = teamAcc.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[12], a.getValue()[12]))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team", e.getKey());
                    m.putAll(rowOf.apply(e));
                    return m;
                }).collect(Collectors.toList());

        // 담당자별 (팀+담당자)
        java.util.function.Function<BlockOverviewDto, String> mgrKey = r -> teamOf.apply(r) + "|" + managerOf.apply(r);
        Map<String, long[]> mgrAcc = aggregate.apply(mgrKey, all);
        List<Map<String, Object>> byManager = mgrAcc.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[12], a.getValue()[12]))
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team",    parts[0]);
                    m.put("manager", parts.length > 1 ? parts[1] : "(미지정)");
                    m.putAll(rowOf.apply(e));
                    return m;
                }).collect(Collectors.toList());

        // 레포별 (팀+레포)
        java.util.function.Function<BlockOverviewDto, String> repoKey = r -> teamOf.apply(r) + "|" + r.getRepositoryName();
        Map<String, long[]> repoAcc = aggregate.apply(repoKey, all);
        List<Map<String, Object>> byRepo = repoAcc.entrySet().stream()
                .sorted((a, b) -> {
                    String[] ka = a.getKey().split("\\|", 2);
                    String[] kb = b.getKey().split("\\|", 2);
                    int tc = ka[0].compareTo(kb[0]);
                    return tc != 0 ? tc : Long.compare(b.getValue()[12], a.getValue()[12]);
                })
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    String teamVal = parts[0];
                    String repoName = parts[1];
                    RepoConfig cfg = repoConfigMap.get(repoName);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team", teamVal);
                    m.put("repo", repoName);
                    m.put("businessName", cfg != null && cfg.getBusinessName() != null ? cfg.getBusinessName() : "-");
                    m.putAll(rowOf.apply(e));
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewThreshold", reviewThreshold);
        result.put("byTeam",    byTeam);
        result.put("byManager", byManager);
        result.put("byRepo",    byRepo);
        result.put("totalRecords", all.size());

        log.info("[차단대상 진행사항] 건수={}, 팀={}, 담당자={}, 레포={}, 소요={}ms",
                all.size(), byTeam.size(), byManager.size(), byRepo.size(), System.currentTimeMillis() - start);
        return ResponseEntity.ok(result);
    }

    /** Whatap 호출건수 조회 */
    @PostMapping("/whatap/stats")
    public ResponseEntity<?> whatapStats(@RequestBody WhatapRequest request) {
        try {
            log.info("[Whatap 조회 요청] pcode={}", request.getPcode());
            WhatapResult result = whatapService.fetchStats(request);
            log.info("[Whatap 조회 응답] 수집 API {}건", result.getApiCount());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("[Whatap 조회 실패] 입력 오류: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Whatap 조회 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 로그 조회 API ──────────────────────────────────────────

    /** 로그 파일이 존재하는 날짜 목록 (type: system/business) */
    @GetMapping("/logs/dates")
    public ResponseEntity<?> logDates(@RequestParam(defaultValue = "business") String type) {
        String prefix = "system".equals(type) ? "system" : "business";
        try {
            Path logDir = Paths.get("./logs");
            if (!Files.exists(logDir)) return ResponseEntity.ok(List.of());
            List<String> dates = Files.list(logDir)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(prefix + "-") && n.endsWith(".log"))
                    .map(n -> n.replace(prefix + "-", "").replace(".log", ""))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            if (Files.exists(logDir.resolve(prefix + ".log")) && !dates.contains(today)) {
                dates.add(0, today);
            }
            return ResponseEntity.ok(dates);
        } catch (IOException e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * 특정 날짜의 로그 내용 조회 (type: system/business).
     * tail: 뒤에서 N줄만 반환. 0이면 전체. 미지정 시 글로벌 설정(logTailLines) 사용.
     */
    @GetMapping("/logs/view")
    public ResponseEntity<?> logView(@RequestParam String date,
                                      @RequestParam(defaultValue = "business") String type,
                                      @RequestParam(defaultValue = "false") boolean quiet,
                                      @RequestParam(required = false) Integer tail) {
        String prefix = "system".equals(type) ? "system" : "business";
        // tail 미지정 시 글로벌 설정 사용
        int tailLines = tail != null ? tail
                : globalConfigRepository.findById(1L).map(g -> g.getLogTailLines()).orElse(1000);
        if (!quiet) log.info("[로그 조회] type={}, date={}, tail={}", prefix, date, tailLines);
        try {
            Path logDir = Paths.get("./logs");
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path logFile = date.equals(today) ? logDir.resolve(prefix + ".log") : logDir.resolve(prefix + "-" + date + ".log");
            if (!Files.exists(logFile)) {
                return ResponseEntity.ok(Map.of("date", date, "content", "로그 파일이 없습니다.",
                        "totalLines", 0, "shownLines", 0, "fileSizeBytes", 0, "truncated", false));
            }
            long fileSize = Files.size(logFile);
            List<String> allLines = Files.readAllLines(logFile);
            int totalLines = allLines.size();
            boolean truncated = tailLines > 0 && totalLines > tailLines;
            List<String> lines = truncated ? allLines.subList(totalLines - tailLines, totalLines) : allLines;
            String content = String.join("\n", lines);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("date", date);
            result.put("content", content);
            result.put("totalLines", totalLines);
            result.put("shownLines", lines.size());
            result.put("fileSizeBytes", fileSize);
            result.put("truncated", truncated);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.ok(Map.of("date", date, "content", "로그 읽기 실패: " + e.getMessage(),
                    "totalLines", 0, "shownLines", 0, "fileSizeBytes", 0, "truncated", false));
        }
    }

    /**
     * 분석 데이터 삭제 (ApiRecord).
     * ?repoName=xxx 지정 시 해당 레포만, 없으면 전체 삭제.
     */
    @DeleteMapping("/db/delete-all")
    public ResponseEntity<?> deleteAllRecords(@RequestParam(required = false) String repoName) {
        log.warn("[분석데이터 삭제] DELETE /api/db/delete-all repoName={}", repoName);
        int deleted;
        boolean allRepos = repoName == null || repoName.isBlank() || "ALL".equalsIgnoreCase(repoName);
        if (allRepos) {
            deleted = (int) recordRepository.count();
            recordRepository.bulkDeleteAll();
        } else {
            deleted = recordRepository.bulkDeleteByRepo(repoName);
        }
        log.info("[분석데이터 삭제 완료] {}건 삭제 ({})", deleted, allRepos ? "TRUNCATE" : "bulk DELETE");
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * 삭제 상태 레코드 영구 삭제 (관리자 전용).
     * Body: { "ids": [1,2,3] } — 지정된 id들 중 status='삭제'인 것만 물리 삭제.
     */
    @DeleteMapping("/db/purge-deleted")
    public ResponseEntity<?> purgeDeletedRecords(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
            }
            int purged = 0, skipped = 0;
            for (Integer rawId : rawIds) {
                Optional<ApiRecord> opt = recordRepository.findById(rawId.longValue());
                if (opt.isEmpty()) continue;
                ApiRecord r = opt.get();
                if (!"삭제".equals(r.getStatus())) { skipped++; continue; }
                recordRepository.deleteById(r.getId());
                purged++;
            }
            log.warn("[삭제건 영구삭제] 대상={}건, 영구삭제={}건, 스킵={}건 (삭제상태 아님)", rawIds.size(), purged, skipped);
            return ResponseEntity.ok(Map.of("purged", purged, "skipped", skipped));
        } catch (Exception e) {
            log.error("[삭제건 영구삭제 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 선택 레코드 강제 삭제 (관리자 전용).
     * Body: { "ids": [1,2,3] } — 상태 무관하게 지정된 id들을 물리 삭제.
     */
    @DeleteMapping("/db/records")
    public ResponseEntity<?> deleteRecords(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
            }
            int deleted = 0, skipped = 0;
            for (Integer rawId : rawIds) {
                if (recordRepository.existsById(rawId.longValue())) {
                    recordRepository.deleteById(rawId.longValue());
                    deleted++;
                } else {
                    skipped++;
                }
            }
            log.warn("[레코드 강제삭제] 대상={}건, 삭제={}건, 스킵={}건 (ID없음)", rawIds.size(), deleted, skipped);
            return ResponseEntity.ok(Map.of("deleted", deleted, "skipped", skipped));
        } catch (Exception e) {
            log.error("[레코드 강제삭제 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** managerMappings JSON → List<Map<programId, managerName>> 파싱 (실패 시 빈 리스트) */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseManagerMappings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> raw = om.readValue(json, List.class);
            List<Map<String, String>> out = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) {
                Object pid = m.get("programId");
                Object mgr = m.get("managerName");
                if (pid != null && mgr != null) {
                    out.add(Map.of("programId", pid.toString(), "managerName", mgr.toString()));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[managerMappings 파싱 실패] {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 담당자 결정: managerOverride > 프로그램ID별 매핑 > 팀 대표(managerName) > "(미지정)"
     */
    private String resolveManager(ApiRecord r, RepoConfig cfg, List<Map<String, String>> mappings) {
        return resolveManager(r.getManagerOverride(), r.getApiPath(), cfg, mappings);
    }

    /** DTO/엔티티 공용 — 담당자 결정 로직 */
    private String resolveManager(String managerOverride, String apiPath, RepoConfig cfg, List<Map<String, String>> mappings) {
        if (managerOverride != null && !managerOverride.isBlank()) {
            return managerOverride;
        }
        if (mappings != null && !mappings.isEmpty()) {
            String pathUpper = apiPath != null ? apiPath.toUpperCase() : "";
            for (Map<String, String> m : mappings) {
                String pid = m.get("programId");
                if (pid != null && !pid.isBlank() && pathUpper.contains(pid.toUpperCase())) {
                    return m.get("managerName");
                }
            }
        }
        if (cfg != null && cfg.getManagerName() != null && !cfg.getManagerName().isBlank()) {
            return cfg.getManagerName();
        }
        return "(미지정)";
    }
}
