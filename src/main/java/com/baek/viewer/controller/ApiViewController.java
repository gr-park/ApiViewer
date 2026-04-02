package com.baek.viewer.controller;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ExtractRequest;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.model.WhatapRequest;
import com.baek.viewer.model.WhatapResult;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.service.ApiExtractorService;
import com.baek.viewer.service.ApiStorageService;
import com.baek.viewer.service.WhatapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiViewController {

    private final ApiExtractorService extractorService;
    private final WhatapService whatapService;
    private final ApiRecordRepository recordRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final ApiStorageService storageService;

    public ApiViewController(ApiExtractorService extractorService,
                             WhatapService whatapService,
                             ApiRecordRepository recordRepository,
                             RepoConfigRepository repoConfigRepository,
                             ApiStorageService storageService) {
        this.extractorService = extractorService;
        this.whatapService = whatapService;
        this.recordRepository = recordRepository;
        this.repoConfigRepository = repoConfigRepository;
        this.storageService = storageService;
    }

    /** 추출 실행 (비동기) */
    @PostMapping("/extract")
    public ResponseEntity<?> extract(@RequestBody ExtractRequest request) {
        try {
            extractorService.startExtractAsync(request);
            return ResponseEntity.accepted().body(Map.of("message", "추출 시작됨"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
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

    /** DB에서 API 목록 조회 (repository 미입력 시 전체 조회) */
    @GetMapping("/db/apis")
    public ResponseEntity<?> dbApis(@RequestParam(required = false) String repository) {
        List<ApiRecord> records = (repository != null && !repository.isBlank())
                ? recordRepository.findByRepositoryName(repository)
                : recordRepository.findAll();
        Map<String, Object> response = new HashMap<>();
        response.put("total", records.size());
        response.put("apis", records);
        return ResponseEntity.ok(response);
    }

    /** 상태/차단대상/차단대상기준 일괄 변경
     * Body: { "ids": [1,2,3], "status": "차단완료", "blockTarget": "최우선 차단대상", "blockCriteria": "IT담당자검토건" }
     * - status: 없으면 변경 안 함, null/빈값이면 자동계산 복원, 값이면 수동 설정
     * - blockTarget/blockCriteria: 없으면 변경 안 함, null/빈값이면 해제, 값이면 설정
     */
    @PatchMapping("/db/status")
    public ResponseEntity<?> updateStatus(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
            }
            List<Long> ids = rawIds.stream().map(i -> i.longValue()).toList();

            boolean updateStatus = body.containsKey("status");
            String status = (String) body.get("status");

            boolean updateBlock = body.containsKey("blockTarget") || body.containsKey("blockCriteria");
            String blockTarget = body.containsKey("blockTarget")
                    ? (body.get("blockTarget") != null ? body.get("blockTarget").toString() : null) : null;
            String blockCriteria = body.containsKey("blockCriteria")
                    ? (body.get("blockCriteria") != null ? body.get("blockCriteria").toString() : null) : null;

            // 빈 문자열은 null로 처리 (해제)
            if (blockTarget != null && blockTarget.isBlank()) blockTarget = null;
            if (blockCriteria != null && blockCriteria.isBlank()) blockCriteria = null;

            int updated = storageService.updateBulk(ids, status, updateStatus,
                    blockTarget, blockCriteria, updateBlock);
            return ResponseEntity.ok(Map.of("updated", updated));
        } catch (Exception e) {
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

        // 레포지토리별
        Map<String, List<ApiRecord>> byRepoGroup = all.stream()
                .collect(Collectors.groupingBy(ApiRecord::getRepositoryName));

        List<Map<String, Object>> byRepo = byRepoGroup.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<ApiRecord>> e) -> e.getValue().size()).reversed())
                .map(e -> {
                    String repoName = e.getKey();
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
                            .filter(r -> r.getLastAnalyzedDate() != null)
                            .map(r -> r.getLastAnalyzedDate().toString())
                            .max(Comparator.naturalOrder()).orElse("-");

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("repo",          repoName);
                    m.put("count",         records.size());
                    m.put("team",          cfg != null && cfg.getTeamName()      != null ? cfg.getTeamName()      : "-");
                    m.put("manager",       cfg != null && cfg.getManagerName()   != null ? cfg.getManagerName()   : "-");
                    m.put("businessName",  cfg != null && cfg.getBusinessName()  != null ? cfg.getBusinessName()  : "-");
                    m.put("lastAnalyzedDate", lastDate);
                    m.put("statusDetail",  statusDetail);
                    m.put("methodDetail",  methodDetail);
                    return m;
                }).collect(Collectors.toList());

        // 팀별
        Map<String, Long> byTeamCount = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byTeamStatus = new LinkedHashMap<>();
        all.forEach(r -> {
            RepoConfig cfg = repoConfigMap.get(r.getRepositoryName());
            String team = (cfg != null && cfg.getTeamName() != null && !cfg.getTeamName().isBlank())
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

        // 담당자별 (팀 포함)
        Map<String, Long> byManagerCount = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byManagerStatus = new LinkedHashMap<>();
        Map<String, String> managerToTeam = new LinkedHashMap<>();
        all.forEach(r -> {
            RepoConfig cfg = repoConfigMap.get(r.getRepositoryName());
            String mgr  = (cfg != null && cfg.getManagerName() != null && !cfg.getManagerName().isBlank())
                    ? cfg.getManagerName() : "(미지정)";
            String team = (cfg != null && cfg.getTeamName() != null && !cfg.getTeamName().isBlank())
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

        // 차단대상별
        Map<String, Long> byBlockTarget = new LinkedHashMap<>();
        all.forEach(r -> {
            String bt = r.getBlockTarget();
            String key = (bt != null && !bt.isBlank()) ? bt : "(미지정)";
            byBlockTarget.merge(key, 1L, Long::sum);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total",         all.size());
        result.put("byStatus",      byStatus);
        result.put("byBlockTarget", byBlockTarget);
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
            WhatapResult result = whatapService.fetchStats(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
