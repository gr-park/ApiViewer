package com.baek.viewer.controller;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ApiRecordSummary;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
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

    /** 비밀번호 확인 → 토큰 발급 */
    @PostMapping("/verify-password")
    public ResponseEntity<?> verifyPassword(@RequestBody Map<String, String> body) {
        String input = body.get("password");
        String stored = globalConfigRepository.findById(1L)
                .map(g -> g.getPassword()).orElse(null);
        if (stored == null || stored.isBlank()) stored = "lotte1!";
        boolean ok = stored.equals(input);
        if (ok) {
            String token = authService.issueToken();
            log.info("[인증 성공] 토큰 발급");
            return ResponseEntity.ok(Map.of("valid", true, "token", token));
        }
        log.warn("[인증 실패] 비밀번호 불일치");
        return ResponseEntity.ok(Map.of("valid", false));
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

    /** DB에서 API 목록 조회 — 경량 프로젝션 (fullComment, controllerComment, blockedReason 제외) */
    @GetMapping("/db/apis")
    public ResponseEntity<?> dbApis(@RequestParam(required = false) String repository,
                                     @RequestParam(required = false, defaultValue = "false") boolean blockTargetOnly) {
        long start = System.currentTimeMillis();
        List<ApiRecordSummary> records;
        if (blockTargetOnly) {
            records = recordRepository.findSummaryByStatusIn(List.of("최우선 차단대상","후순위 차단대상","검토필요 차단대상"));
        } else if (repository != null && !repository.isBlank()) {
            records = recordRepository.findSummaryByRepositoryName(repository);
        } else {
            records = recordRepository.findAllSummary();
        }
        log.info("[목록 조회] repo={}, blockTargetOnly={}, 건수={}, 소요={}ms",
                repository, blockTargetOnly, records.size(), System.currentTimeMillis() - start);
        Map<String, Object> response = new HashMap<>();
        response.put("total", records.size());
        response.put("apis", records);
        return ResponseEntity.ok(response);
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
     * Body: { "ids": [1,2,3], "status": "차단완료", "blockTarget": "최우선 차단대상", ... }
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

    /** 상태변경 플래그 해제 (IT 담당자 확인 후) */
    @PatchMapping("/db/clear-status-change")
    public ResponseEntity<?> clearStatusChange(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
            }
            int cleared = 0;
            for (Integer rawId : rawIds) {
                Optional<ApiRecord> opt = recordRepository.findById(rawId.longValue());
                if (opt.isPresent()) {
                    ApiRecord r = opt.get();
                    r.setStatusChanged(false);
                    r.setStatusChangeLog(null);
                    recordRepository.save(r);
                    cleared++;
                }
            }
            log.info("[상태변경 플래그 해제] 대상={}건, 해제={}건", rawIds.size(), cleared);
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

            if ("차단완료".equals(r.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "변경불가 상태의 레코드는 수정할 수 없습니다."));
            }

            boolean viewerChanged = false;
            if (body.containsKey("isNew"))             { r.setNew(Boolean.FALSE.equals(body.get("isNew")) ? false : Boolean.parseBoolean(String.valueOf(body.get("isNew")))); }
            if (body.containsKey("memo"))            { r.setMemo(body.get("memo") != null ? body.get("memo").toString() : null); viewerChanged = true; }
            if (body.containsKey("teamOverride"))    { r.setTeamOverride(body.get("teamOverride") != null ? body.get("teamOverride").toString() : null); viewerChanged = true; }
            if (body.containsKey("managerOverride")) { r.setManagerOverride(body.get("managerOverride") != null ? body.get("managerOverride").toString() : null); viewerChanged = true; }
            if (viewerChanged) {
                r.setModifiedAt(java.time.LocalDateTime.now());
                r.setModifiedIp(ip);
            }

            boolean reviewChanged = false;
            if (body.containsKey("reviewResult"))   { r.setReviewResult(body.get("reviewResult") != null ? body.get("reviewResult").toString() : null); reviewChanged = true; }
            if (body.containsKey("reviewOpinion"))  { r.setReviewOpinion(body.get("reviewOpinion") != null ? body.get("reviewOpinion").toString() : null); reviewChanged = true; }
            if (body.containsKey("reviewTeam"))     { r.setReviewTeam(body.get("reviewTeam") != null ? body.get("reviewTeam").toString() : null); reviewChanged = true; }
            if (body.containsKey("reviewManager"))  { r.setReviewManager(body.get("reviewManager") != null ? body.get("reviewManager").toString() : null); reviewChanged = true; }
            if (reviewChanged) {
                r.setReviewedAt(java.time.LocalDateTime.now());
                r.setReviewedIp(ip);
            }

            recordRepository.save(r);
            log.info("[레코드 수정] id={}, 필드={}", id, body.keySet());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[레코드 수정 실패] id={}, 오류={}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
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

    /** DB 전체 통계 */
    @GetMapping("/db/stats")
    public ResponseEntity<?> dbStats() {
        log.info("[통계 조회] GET /api/db/stats");
        List<ApiRecord> all = recordRepository.findAll();

        // repoName → RepoConfig 매핑
        Map<String, RepoConfig> repoConfigMap = repoConfigRepository.findAll().stream()
                .collect(Collectors.toMap(RepoConfig::getRepoName, r -> r, (a, b) -> a));

        // 상태별
        Map<String, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getStatus() != null ? r.getStatus() : "사용",
                        Collectors.counting()));

        // HTTP Method별 (전체)
        Map<String, Long> byMethod = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getHttpMethod() != null ? r.getHttpMethod() : "?",
                        Collectors.counting()));

        // 레포지토리별 — (팀, 레포) 조합으로 그룹핑하여 팀이 다르면 별도 행
        // effectiveTeam 함수
        java.util.function.BiFunction<ApiRecord, Map<String, RepoConfig>, String> effectiveTeam = (r, cfgMap) -> {
            if (r.getTeamOverride() != null && !r.getTeamOverride().isBlank()) return r.getTeamOverride();
            RepoConfig c = cfgMap.get(r.getRepositoryName());
            return (c != null && c.getTeamName() != null && !c.getTeamName().isBlank()) ? c.getTeamName() : "(팀 미지정)";
        };

        Map<String, List<ApiRecord>> byTeamRepoGroup = all.stream()
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
                    List<ApiRecord> records = e.getValue();
                    RepoConfig cfg = repoConfigMap.get(repoName);

                    Map<String, Long> statusDetail = records.stream()
                            .collect(Collectors.groupingBy(
                                    r -> r.getStatus() != null ? r.getStatus() : "사용",
                                    Collectors.counting()));
                    Map<String, Long> methodDetail = records.stream()
                            .collect(Collectors.groupingBy(
                                    r -> r.getHttpMethod() != null ? r.getHttpMethod() : "?",
                                    Collectors.counting()));
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
                    m.put("methodDetail",  methodDetail);
                    return m;
                }).collect(Collectors.toList());

        // 팀별 (teamOverride 우선)
        Map<String, Long> byTeamCount = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byTeamStatus = new LinkedHashMap<>();
        all.forEach(r -> {
            RepoConfig cfg = repoConfigMap.get(r.getRepositoryName());
            String team = (r.getTeamOverride() != null && !r.getTeamOverride().isBlank())
                    ? r.getTeamOverride()
                    : (cfg != null && cfg.getTeamName() != null && !cfg.getTeamName().isBlank())
                    ? cfg.getTeamName() : "(팀 미지정)";
            byTeamCount.merge(team, 1L, Long::sum);
            String status = r.getStatus() != null ? r.getStatus() : "사용";
            byTeamStatus.computeIfAbsent(team, k -> new LinkedHashMap<>()).merge(status, 1L, Long::sum);
        });
        List<Map<String, Object>> byTeam = byTeamCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team",         e.getKey());
                    m.put("count",        e.getValue());
                    m.put("statusDetail", byTeamStatus.getOrDefault(e.getKey(), Map.of()));
                    return m;
                }).collect(Collectors.toList());

        // 담당자별 (managerOverride/teamOverride 우선)
        Map<String, Long> byManagerCount = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byManagerStatus = new LinkedHashMap<>();
        Map<String, String> managerToTeam = new LinkedHashMap<>();
        all.forEach(r -> {
            RepoConfig cfg = repoConfigMap.get(r.getRepositoryName());
            String mgr = (r.getManagerOverride() != null && !r.getManagerOverride().isBlank())
                    ? r.getManagerOverride()
                    : (cfg != null && cfg.getManagerName() != null && !cfg.getManagerName().isBlank())
                    ? cfg.getManagerName() : "(미지정)";
            String team = (r.getTeamOverride() != null && !r.getTeamOverride().isBlank())
                    ? r.getTeamOverride()
                    : (cfg != null && cfg.getTeamName() != null && !cfg.getTeamName().isBlank())
                    ? cfg.getTeamName() : "(팀 미지정)";
            byManagerCount.merge(mgr, 1L, Long::sum);
            managerToTeam.putIfAbsent(mgr, team);
            String status = r.getStatus() != null ? r.getStatus() : "사용";
            byManagerStatus.computeIfAbsent(mgr, k -> new LinkedHashMap<>()).merge(status, 1L, Long::sum);
        });
        List<Map<String, Object>> byManager = byManagerCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("manager",      e.getKey());
                    m.put("team",         managerToTeam.getOrDefault(e.getKey(), "-"));
                    m.put("count",        e.getValue());
                    m.put("statusDetail", byManagerStatus.getOrDefault(e.getKey(), Map.of()));
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total",         all.size());
        result.put("byStatus",      byStatus);
        result.put("byMethod",      byMethod);
        result.put("byRepo",        byRepo);
        result.put("byTeam",        byTeam);
        result.put("byManager",     byManager);
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

    /** 특정 날짜의 로그 내용 조회 (type: system/business) */
    @GetMapping("/logs/view")
    public ResponseEntity<?> logView(@RequestParam String date,
                                      @RequestParam(defaultValue = "business") String type) {
        String prefix = "system".equals(type) ? "system" : "business";
        log.info("[로그 조회] type={}, date={}", prefix, date);
        try {
            Path logDir = Paths.get("./logs");
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path logFile = date.equals(today) ? logDir.resolve(prefix + ".log") : logDir.resolve(prefix + "-" + date + ".log");
            if (!Files.exists(logFile)) {
                return ResponseEntity.ok(Map.of("date", date, "content", "로그 파일이 없습니다."));
            }
            String content = Files.readString(logFile);
            return ResponseEntity.ok(Map.of("date", date, "content", content));
        } catch (IOException e) {
            return ResponseEntity.ok(Map.of("date", date, "content", "로그 읽기 실패: " + e.getMessage()));
        }
    }

    /** 전체 분석 데이터 삭제 */
    @DeleteMapping("/db/delete-all")
    public ResponseEntity<?> deleteAllRecords() {
        log.warn("[데이터 전체 삭제] DELETE /api/db/delete-all 요청");
        long count = recordRepository.count();
        recordRepository.deleteAll();
        log.info("[데이터 초기화] {}건 삭제", count);
        return ResponseEntity.ok(Map.of("deleted", count));
    }
}
