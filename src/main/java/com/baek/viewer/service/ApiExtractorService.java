package com.baek.viewer.service;

import com.baek.viewer.ai.InternalOpenAiCompatibleClient;
import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ExtractRequest;
import com.baek.viewer.model.GlobalConfig;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.*;
import java.util.stream.Collectors;

@Service
public class ApiExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ApiExtractorService.class);

    // ANSI 컬러(콘솔) — logback 색상 컨버터가 있어도, "정말 눈에 띄게" 하고 싶을 때 쓰는 직접 코드.
    // (화면 로그(extractLogs)에는 ANSI를 넣지 않고 텍스트 태그로만 표시)
    private static final String ANSI_PURPLE = "\u001B[35m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static String purpleBanner(String msg) {
        return ANSI_PURPLE + ANSI_BOLD + msg + ANSI_RESET;
    }

    private static final List<String> MAPPING_ANNS = Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    /**
     * JavaParser 파싱 전 단일따옴표 리터럴을 보정할 어노테이션 allowlist.
     * - 매핑 어노테이션: 경로 문자열에서 자주 발생
     * - Swagger v3: summary/name 등에서 잘못된 단일따옴표가 종종 섞임
     * - Security: @Secured('ROLE_...') 스타일 실수
     *
     * 원칙: "어노테이션 괄호 내부"에서만 최소 변환 (일반 코드 영역은 손대지 않음).
     */
    private static final Set<String> NORMALIZE_ANN_NAMES;

    static {
        Set<String> s = new HashSet<>(MAPPING_ANNS);
        // Spring Security
        s.add("Secured");
        // Security 기타(문법 실수로 단일따옴표를 쓰는 케이스 방어)
        // - @PreAuthorize('...'), @PostAuthorize('...'), @RolesAllowed('ROLE_X')
        // - PermitAll/DenyAll 은 파라미터가 없어 영향 없음(추가해도 무해)
        s.addAll(List.of("PreAuthorize", "PostAuthorize", "RolesAllowed", "PermitAll", "DenyAll"));
        // Swagger/OpenAPI v3 (io.swagger.v3.oas.annotations.*)
        s.addAll(List.of(
                "Operation", "Parameter",
                "ApiResponse", "ApiResponses",
                "Schema", "Content",
                "Tag", "Tags",
                "Hidden",
                "RequestBody",
                "Header", "Headers",
                "Link", "Links",
                "Server", "Servers",
                "Callback", "Callbacks",
                "ExternalDocumentation",
                "Extension", "Extensions",
                "ExampleObject",
                "SecurityRequirement", "SecurityRequirements"
        ));
        NORMALIZE_ANN_NAMES = Collections.unmodifiableSet(s);
    }

    /**
     * JavaParser는 문법 오류가 있는 소스(예: 매핑 애노테이션에서 {@code '/path'} 단일따옴표 사용)를 그대로는 파싱할 수 없다.
     * 원본 파일은 손대지 않고, 파싱 직전에 메모리 문자열만 최소 보정해 성공률을 올린다.
     */
    private record NormalizationResult(String source, boolean changed, List<String> notes) {
    }

    /**
     * Regex 폴백: 매핑 애노테이션 직후 ~ 메서드 시그니처 사이에 {@code @Secured}, 긴 {@code @Operation}+{@code @ApiResponses} 등이
     * 연속될 수 있어 기존 1k 윈도우는 누락을 유발했다. JavaParser와 동일 구간을 더 넓게 본다.
     */
    private static final int RX_AFTER_MAPPING_SCAN_CHARS = 12_000;

    private static final int RX_OPERATION_VALUE_MAX_LEN = 200;

    private static final Pattern RX_MAPPING_HEAD = Pattern.compile(
            "@(GetMapping|PostMapping|RequestMapping|PutMapping|DeleteMapping|PatchMapping)\\s*");

    private static final Pattern RX_CLASS_REQUEST_MAPPING_HEAD = Pattern.compile("@RequestMapping\\s*");

    private static final Pattern RX_METHOD_WITH_ACCESS = Pattern.compile(
            "(?:public|private|protected)\\s+[\\w<>,\\s?]+\\s+(\\w+)\\s*\\(");

    /** 패키지 프라이빗 등: {@code Type name(} — 키워드 오탐은 {@link #RX_METHOD_TYPEISH_BLACKLIST} 로 걸러낸다. */
    private static final Pattern RX_METHOD_PKG_PRIVATE = Pattern.compile(
            "([\\w<>,\\s?]+)\\s+(\\w+)\\s*\\(");

    private static final Set<String> RX_METHOD_TYPEISH_BLACKLIST = Set.of(
            "if", "for", "while", "switch", "catch", "try", "else", "do", "return", "throw", "new", "synchronized");

    private static final Set<String> RX_METHOD_NAME_BLACKLIST = Set.of(
            "true", "false", "null");

    private static volatile boolean JAVA_PARSER_CONFIGURED = false;

    private static void ensureJavaParserConfigured() {
        if (JAVA_PARSER_CONFIGURED) return;
        synchronized (ApiExtractorService.class) {
            if (JAVA_PARSER_CONFIGURED) return;
            // Swagger v3의 @Operation(description=\"\"\"...\"\"\") 같은 Java 텍스트블록 문법은
            // JavaParser 언어 레벨 설정이 낮으면 파싱 실패 → Regex 폴백으로 빠질 수 있다.
            StaticJavaParser.getConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            JAVA_PARSER_CONFIGURED = true;
        }
    }

    /**
     * devtools/리로드/외부 코드 영향으로 StaticJavaParser configuration 이 바뀌는 케이스가 있어,
     * parse 직전에 매번 language level 을 강제한다. (성능 영향은 미미)
     */
    private static void forceJava17BeforeParse(String relPathForLog) {
        try {
            ParserConfiguration cfg = StaticJavaParser.getConfiguration();
            cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            ParserConfiguration.LanguageLevel lvl = cfg.getLanguageLevel();
            log.info(purpleBanner("[JP-CONFIG] languageLevel=" + lvl + " cfg@" + System.identityHashCode(cfg)
                    + " relPath=" + (relPathForLog == null ? "" : relPathForLog)));
        } catch (Exception e) {
            log.warn("[JP-CONFIG] 설정/출력 실패 relPath={}", relPathForLog, e);
        }
    }

    @Value("${api.viewer.git-bin-path:git}")
    private String defaultGitBinPath;

    private final ApiStorageService storageService;
    private final ApmCollectionService apmCollectionService;
    private final com.baek.viewer.repository.RepoConfigRepository repoConfigRepository;
    private final com.baek.viewer.repository.GlobalConfigRepository globalConfigRepository;
    private final SnapshotService snapshotService;
    private final InternalOpenAiCompatibleClient internalOpenAiCompatibleClient;
    private final RelatedMenuAiAutoFillService relatedMenuAiAutoFillService;

    public ApiExtractorService(ApiStorageService storageService, ApmCollectionService apmCollectionService,
                               com.baek.viewer.repository.RepoConfigRepository repoConfigRepository,
                               com.baek.viewer.repository.GlobalConfigRepository globalConfigRepository,
                               SnapshotService snapshotService,
                               InternalOpenAiCompatibleClient internalOpenAiCompatibleClient,
                               RelatedMenuAiAutoFillService relatedMenuAiAutoFillService) {
        this.storageService = storageService;
        this.apmCollectionService = apmCollectionService;
        this.repoConfigRepository = repoConfigRepository;
        this.globalConfigRepository = globalConfigRepository;
        this.snapshotService = snapshotService;
        this.internalOpenAiCompatibleClient = internalOpenAiCompatibleClient;
        this.relatedMenuAiAutoFillService = relatedMenuAiAutoFillService;
    }

    private volatile List<ApiInfo> cachedApis = new ArrayList<>();
    private volatile boolean extracting = false;
    private volatile boolean debugMode = false; // extract 시작 시 1회 세팅
    private volatile int totalFiles = 0;
    private volatile int processedFiles = 0;
    private volatile String currentFile = "";
    private volatile String lastError = null;
    private volatile int savedCount = -1; // -1 = 미저장, 0 이상 = 저장 건수
    private volatile int statusRevertedCount = 0; // 차단대상→사용(차단대상 제외) 전환 건수
    private volatile boolean relatedMenuAiProbeFailed = false;
    private volatile String relatedMenuAiProbeMessage = null;
    private final List<String> extractLogs = Collections.synchronizedList(new ArrayList<>());

    private void addLog(String level, String msg) {
        String ts = java.time.LocalTime.now().toString().substring(0, 8);
        extractLogs.add(ts + " [" + level + "] " + msg);
        // 콘솔/파일 로그에도 동시 기록
        switch (level) {
            case "ERROR" -> log.error("[추출] {}", msg);
            case "WARN"  -> log.warn("[추출] {}", msg);
            default      -> log.info("[추출] {}", msg);
        }
    }

    public boolean isExtracting() { return extracting; }
    public List<ApiInfo> getCached() { return cachedApis; }

    public Map<String, Object> getProgress() {
        Map<String, Object> p = new HashMap<>();
        p.put("extracting", extracting);
        p.put("total", totalFiles);
        p.put("processed", processedFiles);
        p.put("currentFile", currentFile);
        p.put("percent", totalFiles > 0 ? (processedFiles * 100 / totalFiles) : 0);
        p.put("error", lastError);
        p.put("savedCount", savedCount);
        p.put("statusRevertedCount", statusRevertedCount);
        p.put("relatedMenuAiProbeFailed", relatedMenuAiProbeFailed);
        p.put("relatedMenuAiProbeMessage", relatedMenuAiProbeMessage);
        p.put("logs", new ArrayList<>(extractLogs));
        return p;
    }

    /**
     * 관련메뉴 미흡 자동분석이 켜져 있으면, 본격 분석 전에 사내 AI chat 을 한 번 호출해 연결을 검증한다.
     * 실패 시 본 Extract 에서는 자동 보완을 수행하지 않는다(분석·저장은 계속).
     */
    private void runRelatedMenuAiConnectivityProbe(ExtractRequest req, GlobalConfig gc) {
        relatedMenuAiProbeFailed = false;
        relatedMenuAiProbeMessage = null;
        if (!req.isPersistToDatabase()) {
            return;
        }
        String repo = req.getRepositoryName();
        if (repo == null || repo.isBlank()) {
            return;
        }
        if (!gc.isAiRelatedMenuDeficientAutoEnabled()) {
            return;
        }
        if (!gc.isAiApiEnabled()) {
            relatedMenuAiProbeFailed = true;
            relatedMenuAiProbeMessage = "AI API 요청이 비활성화되어 있습니다.";
            addLog("WARN", "[관련메뉴 자동분석] " + relatedMenuAiProbeMessage + " URL 분석은 계속됩니다.");
            return;
        }
        if (gc.getAiOpenApiBaseUrl() == null || gc.getAiOpenApiBaseUrl().isBlank()) {
            relatedMenuAiProbeFailed = true;
            relatedMenuAiProbeMessage = "사내 AI 베이스 URL이 설정되지 않았습니다.";
            addLog("WARN", "[관련메뉴 자동분석] " + relatedMenuAiProbeMessage + " URL 분석은 계속됩니다.");
            return;
        }
        if (gc.getAiOpenApiToken() == null || gc.getAiOpenApiToken().isBlank()) {
            relatedMenuAiProbeFailed = true;
            relatedMenuAiProbeMessage = "사내 AI 토큰이 설정되지 않았습니다.";
            addLog("WARN", "[관련메뉴 자동분석] " + relatedMenuAiProbeMessage + " URL 분석은 계속됩니다.");
            return;
        }
        try {
            internalOpenAiCompatibleClient.chatCompletion(gc, "ping", 8);
            addLog("INFO", "[관련메뉴 자동분석] 사내 AI 연결 확인 완료");
        } catch (Exception e) {
            relatedMenuAiProbeFailed = true;
            relatedMenuAiProbeMessage = e.getMessage() != null ? e.getMessage() : "사내 AI 호출 실패";
            addLog("WARN", "[관련메뉴 자동분석] 연결 확인 실패 — 자동 보완을 건너뜁니다. URL 분석은 계속됩니다: "
                    + relatedMenuAiProbeMessage);
        }
    }

    public void startExtractAsync(ExtractRequest req) {
        savedCount = -1;
        statusRevertedCount = 0;
        relatedMenuAiProbeFailed = false;
        relatedMenuAiProbeMessage = null;
        extractLogs.clear();
        // 이전 추출의 잔여 진행률 초기화 — pull 단계 초반에 이전 값(예: 100%)이 잠깐 보이는 현상 방지.
        // 프론트는 `currentFile` 로 Git 동기화 단계임을 표시하고, 분석 단계에서 processedFiles/totalFiles 로 실제 진행률을 채움.
        totalFiles = 0;
        processedFiles = 0;
        currentFile = "Git 동기화 준비 중...";
        lastError = null;
        new Thread(() -> extract(req)).start();
    }

    // ======================================================
    // 메인 추출 진입점
    // ======================================================

    public List<ApiInfo> extract(ExtractRequest req) {
        if (extracting) throw new IllegalStateException("이미 추출 중입니다.");
        extracting = true;
        GlobalConfig gcBoot = globalConfigRepository.findById(1L).orElse(new GlobalConfig());
        debugMode = gcBoot.isApmDebug();
        runRelatedMenuAiConnectivityProbe(req, gcBoot);
        String rootPath = req.getRootPath();
        String domain = req.getDomain() != null ? req.getDomain() : "";
        String apiPathPrefix = req.getApiPathPrefix() != null ? req.getApiPathPrefix() : "";
        String gitBin = (req.getGitBinPath() != null && !req.getGitBinPath().isBlank())
                ? req.getGitBinPath() : defaultGitBinPath;
        Map<String, String> pathConstantsMap = parsePathConstants(req.getPathConstants());

        List<ApiInfo> apis = new CopyOnWriteArrayList<>();
        lastError = null;

        addLog("INFO", "추출 시작 — 경로: " + rootPath);
        if (req.getRepositoryName() != null && !req.getRepositoryName().isBlank()) {
            addLog("INFO", "레포지토리: " + req.getRepositoryName());
        }

        boolean tsRanForSummary = false;
        int javaControllerFileCount = 0;
        try {
            Path root = Paths.get(rootPath);
            if (!Files.exists(root)) throw new IllegalArgumentException("경로가 존재하지 않습니다: " + rootPath);

            // Git Checkout + Pull (설정에서 활성화된 경우만)
            String repoName = req.getRepositoryName();
            boolean doPull = true;
            String gitBranch = null;
            if (repoName != null && !repoName.isBlank()) {
                var rcOpt = repoConfigRepository.findByRepoName(repoName);
                if (rcOpt.isPresent()) {
                    if ("N".equals(rcOpt.get().getGitPullEnabled())) {
                        doPull = false;
                        addLog("INFO", "Git Pull 건너뜀 (설정에서 비활성화)");
                    }
                    gitBranch = rcOpt.get().getGitBranch();
                }
            }
            if (doPull) {
                try {
                    currentFile = "Git 동기화 실행 중...";
                    addLog("INFO", "Git 강제 동기화 (fetch + checkout -B + clean) 실행 중...");
                    String syncResult = hardSyncToOrigin(root.toFile(), gitBin, gitBranch);
                    addLog("OK", "Git 동기화 완료 — " + syncResult);
                } catch (Exception syncEx) {
                    String repoLabel = (repoName != null && !repoName.isBlank()) ? repoName : rootPath;
                    addLog("WARN", "Git 동기화 실패 [" + repoLabel + "] (기존 파일로 분석 계속) — "
                            + syncEx.getMessage()
                            + " / 해결: repo_config.git_branch 및 원격 브랜치 존재 여부 확인");
                }
            }

            currentFile = "Controller 파일 탐색 중...";
            List<Path> controllerFiles = Files.walk(root)
                    .filter(p -> {
                        String s = p.toString();
                        if (!s.endsWith(".java")) return false;
                        // 케이스/폴더명 차이로 컨트롤러가 누락되는 경우가 있어, controller 탐지 기준을 완화한다.
                        // - 기존: "Controller"/오타 "Conrtoller" (대문자 포함 경로만)
                        // - 개선: 대소문자 무시 "controller"/"conrtoller" 포함
                        String lower = s.toLowerCase();
                        return lower.contains("controller") || lower.contains("conrtoller");
                    })
                    .collect(Collectors.toList());

            java.util.Optional<com.baek.viewer.model.RepoConfig> rcTsOptForProgress = java.util.Optional.empty();
            if (repoName != null && !repoName.isBlank()) {
                rcTsOptForProgress = repoConfigRepository.findByRepoName(repoName);
            }
            boolean tsWillScan = rcTsOptForProgress.isPresent()
                    && "Y".equals(rcTsOptForProgress.get().getTsAnalysisEnabled());
            int javaFileCount = controllerFiles.size();
            javaControllerFileCount = javaFileCount;
            totalFiles = javaFileCount + (tsWillScan ? 1 : 0);
            processedFiles = 0;
            currentFile = "";  // 파일 개별 처리로 진입 — 개별 파일명이 채워짐
            addLog("INFO", "Controller 파일 " + javaFileCount + "개 발견"
                    + (tsWillScan ? " · TS(NestJS) 스캔 1단계 포함(진행률)" : ""));

            controllerFiles.parallelStream().forEach(file -> {
                String rel = root.relativize(file).toString();
                String fileName = file.getFileName().toString();
                currentFile = fileName;
                try {
                    List<String[]> git = getRecentGitHistories(rel, rootPath, gitBin, 5);
                    List<ApiInfo> fileApis = extractApisHybrid(file, rel, git, apiPathPrefix, pathConstantsMap);
                    apis.addAll(fileApis);
                    addLog("OK", rel + " — " + fileApis.size() + "개 API 추출");
                } catch (Exception e) {
                    addLog("ERROR", rel + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                processedFiles++;
            });

            if (tsWillScan) {
                var rcTs = rcTsOptForProgress.get();
                String tsGitBin = gitBin;
                String gbp = rcTs.getGitBinPath();
                if (gbp != null && !gbp.isBlank()) tsGitBin = gbp;
                addLog("INFO", "TS(NestJS) 라우트 스캔 시작 (ts_analysis_enabled=Y)");
                currentFile = "TS(NestJS) 스캔 중...";
                try {
                    List<ApiInfo> tsApis = extractWithTsRegexDebug(root, apiPathPrefix, null, null,
                            null, "", null);
                    applyGitHistoryPerRepoPath(tsApis, rootPath, tsGitBin);
                    apis.addAll(tsApis);
                    addLog("OK", "TS(NestJS) 스캔 완료 — " + tsApis.size() + "개 API (Java 결과에 병합)");
                } catch (Exception tsEx) {
                    addLog("WARN", "TS(NestJS) 스캔 실패 (Java 결과만 이어서 처리): " + tsEx.getMessage());
                    log.warn("[추출] TS 스캔 실패 repo={}", repoName, tsEx);
                }
                processedFiles++;
            }
            tsRanForSummary = tsWillScan;

        } catch (Exception e) {
            lastError = e.getMessage();
            addLog("ERROR", "추출 실패: " + e.getMessage());
            extracting = false;
            throw new RuntimeException("추출 실패: " + e.getMessage(), e);
        }

        // API 경로 정렬 후 도메인 + full URL 보정
        List<ApiInfo> sorted = apis.stream()
                .sorted(Comparator.comparing(ApiInfo::getApiPath))
                .collect(Collectors.toList());

        for (ApiInfo info : sorted) {
            info.setFullUrl(domain + info.getApiPath());
        }

        cachedApis = sorted;
        addLog("INFO", "추출 완료 — 총 " + sorted.size() + "개 API, Java 컨트롤러 파일 "
                + javaControllerFileCount + "개 처리"
                + (tsRanForSummary ? " + TS 스캔" : ""));

        // DB 저장 (레포지토리명이 있을 때만)
        String repoNameOut = req.getRepositoryName();
        if (repoNameOut != null && !repoNameOut.isBlank()) {
            if (!req.isPersistToDatabase()) {
                savedCount = 0;
                statusRevertedCount = 0;
                addLog("INFO", "DB 저장 건너뜀 — 요청에 따라 미리보기만 수행 (APM·스냅샷 생략)");
            } else {
            try {
                addLog("INFO", "DB 저장 중 — 레포: " + repoNameOut.trim());
                int[] saveResult = storageService.save(repoNameOut.trim(), cachedApis, req.getClientIp());
                savedCount = saveResult[0];
                statusRevertedCount = saveResult[1];
                String saveMsg = "DB 저장 완료 — " + savedCount + "개 저장/갱신";
                if (statusRevertedCount > 0) saveMsg += ", 자동①-③·공식 불일치 " + statusRevertedCount + "건 (현업검토=차단대상 제외)";
                addLog("OK", saveMsg);

                if (savedCount >= 0 && !relatedMenuAiProbeFailed) {
                    GlobalConfig gcFill = globalConfigRepository.findById(1L).orElse(new GlobalConfig());
                    if (gcFill.isAiRelatedMenuDeficientAutoEnabled()) {
                        try {
                            addLog("INFO", "관련메뉴 미흡 건 사내 AI 자동 보완 시작…");
                            int filled = relatedMenuAiAutoFillService.fillDeficientInRepository(repoNameOut.trim());
                            addLog("OK", "관련메뉴 미흡 AI 자동 보완 완료 — " + filled + "건 반영");
                        } catch (Exception aiEx) {
                            addLog("WARN", "관련메뉴 미흡 AI 자동 보완 중 오류: " + aiEx.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                savedCount = -1;
                addLog("ERROR", "DB 저장 실패: " + e.getMessage());
            }

            // APM 호출건수 자동 집계 (데이터가 있을 때만)
            try {
                var result = apmCollectionService.aggregateToRecords(repoNameOut.trim());
                int aggUpdated = ((Number) result.get("updated")).intValue();
                if (aggUpdated > 0) {
                    addLog("OK", "호출건수 자동 집계 — " + aggUpdated + "개 API 반영");
                } else {
                    addLog("INFO", "호출건수 집계 — APM 데이터 없음 (건너뜀)");
                }
            } catch (Exception e) {
                addLog("WARN", "호출건수 집계 실패 (분석 결과에 영향 없음): " + e.getMessage());
            }

            // 스냅샷 자동 생성 (수동 Extract 완료 시점)
            // [정책] 항상 '시점 기준 전체(풀)' 스냅샷이 생성됨. trigger 레포명은 label로만 추적성 보존.
            // [멀티레포 일괄 추출] req.skipSnapshot=true 면 이 단계는 건너뛰고, 호출자가 마지막에 1회만 생성한다.
            if (req.isSkipSnapshot()) {
                addLog("INFO", "스냅샷 생성 건너뜀 (일괄 추출 모드 — 마지막 단계에서 일괄 생성)");
            } else {
                try {
                    if (savedCount >= 0) {
                        String ts = java.time.LocalDateTime.now().toString().replace("T", " ");
                        if (ts.length() > 19) ts = ts.substring(0, 19);
                        String label = "Extract " + repoNameOut.trim() + " @ " + ts;
                        snapshotService.createSnapshot("EXTRACT_MANUAL", label, repoNameOut.trim(), req.getClientIp());
                        addLog("OK", "스냅샷 생성 완료 (전체 스냅샷)");
                    } else {
                        addLog("WARN", "스냅샷 생성 건너뜀 (DB 저장 실패)");
                    }
                } catch (Exception e) {
                    addLog("WARN", "스냅샷 생성 실패 (분석 결과에 영향 없음): " + e.getMessage());
                }
            }
            }
        }

        extracting = false;
        return cachedApis;
    }

    // ======================================================
    // 하이브리드 추출 (JavaParser 우선, Regex 폴백)
    // ======================================================

    private List<ApiInfo> extractApisHybrid(Path path, String rel,
                                             List<String[]> git,
                                             String apiPathPrefix,
                                             Map<String, String> pathConstantsMap) {
        try {
            return extractWithJavaParser(path, rel, git, apiPathPrefix, pathConstantsMap);
        } catch (Exception e) {
            addLog("WARN", rel + " — JavaParser 실패 (" + e.getClass().getSimpleName() + "), Regex 폴백 적용");
            // 로컬/운영에서 원인 파악이 쉽도록 콘솔/파일에 스택트레이스+problems 출력
            if (e instanceof ParseProblemException ppe) {
                log.warn("[추출] {} — JavaParser ParseProblemException", rel, ppe);
                try {
                    String problems = ppe.getProblems().stream()
                            .map(pr -> {
                                String loc = pr.getLocation().map(l -> " @ " + l.getBegin()).orElse("");
                                return pr.getMessage() + loc;
                            })
                            .limit(50)
                            .collect(Collectors.joining("\n- ", "- ", ""));
                    if (!problems.isBlank()) {
                        log.warn("[추출] {} — JavaParser problems:\n{}", rel, problems);
                    }
                } catch (Exception ignored) {
                    // problems 포맷 중 예외가 나도 본 예외 스택 출력은 유지
                }
            } else {
                log.warn("[추출] {} — JavaParser 실패 ({})", rel, e.getClass().getSimpleName(), e);
            }
            return extractWithRegex(path, rel, git, apiPathPrefix, pathConstantsMap);
        }
    }

    // ======================================================
    // JavaParser 기반 추출
    // ======================================================

    private List<ApiInfo> extractWithJavaParser(Path filePath, String relPath,
                                                 List<String[]> git,
                                                 String apiPathPrefix,
                                                 Map<String, String> pathConstantsMap) throws Exception {
        String source = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        return extractWithJavaParserFromSource(source, filePath, relPath, git, apiPathPrefix, pathConstantsMap);
    }

    /** 메모리에 있는 소스 문자열로 JavaParser 경로 추출 (디버그 업로드 등). */
    private List<ApiInfo> extractWithJavaParserFromSource(String source, Path filePath, String relPath,
                                                          List<String[]> git,
                                                          String apiPathPrefix,
                                                          Map<String, String> pathConstantsMap) throws Exception {
        ensureJavaParserConfigured();
        boolean debug = debugMode;
        List<ApiInfo> apis = new ArrayList<>();
        String src0 = source == null ? "" : source;
        NormalizationResult nr = normalizeForJavaParser(src0);
        if (nr.changed()) {
            // 화면 진행 로그(Extract Progress)에서 확실히 구분되도록 태그를 남김
            addLog("INFO", "[JP-NORM] in-memory normalization 적용: " + relPath + " — " + String.join(" | ", nr.notes()));
            // 콘솔도 "색"으로 강하게 표시
            log.info(purpleBanner("[JP-NORM] in-memory normalization 적용: " + relPath + " — " + String.join(" | ", nr.notes())));
        } else if (debug) {
            // 디버그 모드에서는 미적용도 확인 가능
            log.debug("[파싱-JP] in-memory normalization not applied: relPath={}", relPath);
        }
        forceJava17BeforeParse(relPath);
        CompilationUnit cu = StaticJavaParser.parse(nr.source());

        String classPath = "";
        String controllerComment = "-";
        String controllerRequestProperty = "-";

        Optional<ClassOrInterfaceDeclaration> mainClass = cu.findFirst(ClassOrInterfaceDeclaration.class);
        if (mainClass.isPresent()) {
            ClassOrInterfaceDeclaration cls = mainClass.get();
            controllerComment = cls.getComment()
                    .map(c -> c.getContent().replaceAll("[\\r\\n*]", " ").trim())
                    .orElse("-");
            controllerRequestProperty = extractRequestPropertyFromNode(cls);

            Optional<AnnotationExpr> classAnn = cls.getAnnotationByName("RequestMapping");
            if (classAnn.isPresent()) {
                List<String> paths = getPathsFromAnn(classAnn.get(), pathConstantsMap);
                if (!paths.isEmpty()) classPath = paths.get(0).trim();
            }
            if (debug) {
                log.debug("[파싱-JP] 클래스={}, @RequestMapping={}, comment={}, reqProp={}",
                        cls.getNameAsString(), classPath.isEmpty() ? "(없음)" : classPath,
                        controllerComment.length() > 40 ? controllerComment.substring(0, 40) + "..." : controllerComment,
                        controllerRequestProperty);
            }
        }

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            for (String annName : MAPPING_ANNS) {
                Optional<AnnotationExpr> methodAnn = method.getAnnotationByName(annName);
                if (methodAnn.isEmpty()) continue;

                String httpMethod = resolveHttpMethod(annName, methodAnn.get());
                List<String> subPaths = getPathsFromAnn(methodAnn.get(), pathConstantsMap);
                if (subPaths.isEmpty()) subPaths.add("");

                for (String sub : subPaths) {
                    String finalPath = normalizePath(apiPathPrefix + classPath + "/" + sub.trim());
                    ApiInfo info = buildApiInfo(filePath, relPath, method, git,
                            finalPath, httpMethod, controllerComment, controllerRequestProperty, source);
                    apis.add(info);

                    if (debug) {
                        log.debug("[파싱-JP]   {} {} | method={} | deprecated={} | urlBlock={} | programId={} | apiOp={}",
                                httpMethod, finalPath, method.getNameAsString(),
                                info.getIsDeprecated(), info.getHasUrlBlock(), info.getProgramId(),
                                info.getApiOperationValue());
                    }
                }
            }
        }
        if (debug) {
            log.debug("[파싱-JP] {} 완료 — {}개 API 추출", filePath.getFileName(), apis.size());
        }
        return apis;
    }

    // ======================================================
    // Regex 기반 폴백 추출
    // ======================================================

    private record RegexMappingSlice(int start, int endExclusive, String mappingType, String paramsRaw) {}

    private record RegexMethodNameMatch(int signatureMatchEnd, String methodName, String matchKind) {}

    /**
     * 주석 제거(길이 변화)로 raw 인덱스가 어긋나지 않게, 매핑 직후 청크만 잘라 동일 규칙으로 정리한다.
     */
    private static String rxStripCommentsChunk(String chunk) {
        if (chunk == null) return "";
        return chunk.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//.*", " ");
    }

    /**
     * {@code openIdx} 위치의 {@code '('} 에 대응하는 닫는 {@code ')'} 인덱스. 문자열·문자·텍스트블록·주석 내부 괄호는 무시.
     */
    private static int rxIndexOfClosingParenBalanced(String s, int openIdx) {
        if (s == null || openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '(') return -1;
        int depth = 1;
        int i = openIdx + 1;
        int n = s.length();
        while (i < n && depth > 0) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n) {
                char d = s.charAt(i + 1);
                if (d == '/') {
                    i += 2;
                    while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                    continue;
                }
                if (d == '*') {
                    i += 2;
                    while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                    i = Math.min(i + 2, n);
                    continue;
                }
            }
            if (c == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n) {
                    if (s.charAt(i) == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                        i += 3;
                        break;
                    }
                    if (s.charAt(i) == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n) {
                    char q = s.charAt(i);
                    if (q == '"') {
                        i++;
                        break;
                    }
                    if (q == '\\' && i + 1 < n) i += 2;
                    else i++;
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char q = s.charAt(i);
                    if (q == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (q == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    private static RegexMappingSlice rxNextMappingSlice(String raw, int fromIndex) {
        Matcher mh = RX_MAPPING_HEAD.matcher(raw);
        if (!mh.find(fromIndex)) return null;
        int start = mh.start();
        String mappingType = mh.group(1);
        int pos = mh.end();
        if (pos >= raw.length() || raw.charAt(pos) != '(') {
            return new RegexMappingSlice(start, pos, mappingType, "");
        }
        int close = rxIndexOfClosingParenBalanced(raw, pos);
        if (close < 0) return null;
        String inner = raw.substring(pos + 1, close);
        return new RegexMappingSlice(start, close + 1, mappingType, inner);
    }

    /**
     * 타입 선언 직전까지(최대 3k)만 본다 — 본문 안 메서드의 {@code @RequestMapping} 을 클래스 레벨로 오인하지 않기 위함.
     */
    private static int rxIndexOfClassDeclarationStart(String raw) {
        if (raw == null) return -1;
        Matcher m = Pattern.compile("(?m)^\\s*public\\s+class\\s+\\w+\\b").matcher(raw);
        if (m.find()) return m.start();
        m = Pattern.compile("(?m)^\\s*class\\s+\\w+\\b").matcher(raw);
        return m.find() ? m.start() : -1;
    }

    private String rxExtractClassLevelRequestMappingPath(String raw, Map<String, String> pathConstantsMap) {
        int classDecl = rxIndexOfClassDeclarationStart(raw);
        int headLen = classDecl >= 0 ? classDecl : Math.min(3000, raw.length());
        headLen = Math.min(headLen, raw.length());
        String head = raw.substring(0, headLen);
        Matcher rm = RX_CLASS_REQUEST_MAPPING_HEAD.matcher(head);
        if (!rm.find()) return "";
        int pos = rm.end();
        if (pos >= head.length() || head.charAt(pos) != '(') return "";
        int close = rxIndexOfClosingParenBalanced(head, pos);
        if (close < 0) return "";
        String cParams = substituteConstants(head.substring(pos + 1, close), pathConstantsMap)
                .replaceAll("\"\\s*\\+\\s*\"", "");
        Matcher cp = Pattern.compile("\"([^\"]+)\"").matcher(cParams);
        return cp.find() ? cp.group(1).trim() : "";
    }

    /** 클래스 선언 직후 블록에만 붙는 {@code @RequestMapping("/api")} 등 — 메서드 시그니처와 짝지으면 안 된다. */
    private static boolean rxMappingSliceFollowedByClassDecl(String raw, int mappingEndExclusive) {
        int to = Math.min(mappingEndExclusive + 256, raw.length());
        String peek = rxStripCommentsChunk(raw.substring(mappingEndExclusive, to));
        return Pattern.compile("^\\s*(public\\s+)?class\\s+\\w+\\s*\\{")
                .matcher(peek).find();
    }

    private static RegexMethodNameMatch rxFindMethodNameMatch(String afterMappingStripped) {
        Matcher m1 = RX_METHOD_WITH_ACCESS.matcher(afterMappingStripped);
        if (m1.find()) return new RegexMethodNameMatch(m1.end(), m1.group(1), "access-modifier");
        Matcher m2 = RX_METHOD_PKG_PRIVATE.matcher(afterMappingStripped);
        RegexMethodNameMatch best = null;
        int bestStart = Integer.MAX_VALUE;
        while (m2.find()) {
            String typeish = m2.group(1).trim();
            String name = m2.group(2);
            if (RX_METHOD_NAME_BLACKLIST.contains(name)) continue;
            if (RX_METHOD_TYPEISH_BLACKLIST.contains(typeish)) continue;
            int st = m2.start();
            if (st < bestStart) {
                bestStart = st;
                best = new RegexMethodNameMatch(m2.end(), name, "package-private");
            }
        }
        return best;
    }

    private static String rxNormalizeOperationText(String rawText) {
        if (rawText == null) return "";
        String t = rawText.replaceAll("\\s+", " ").trim();
        if (t.length() > RX_OPERATION_VALUE_MAX_LEN) {
            return t.substring(0, RX_OPERATION_VALUE_MAX_LEN) + "…";
        }
        return t;
    }

    private static String rxExtractQuotedOrTextBlockAfter(Matcher keyMatcher, String body) {
        int from = keyMatcher.end();
        if (from >= body.length()) return null;
        int i = from;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length()) return null;
        if (body.startsWith("\"\"\"", i)) {
            int j = i + 3;
            int n = body.length();
            while (j + 2 < n) {
                if (body.charAt(j) == '"' && body.charAt(j + 1) == '"' && body.charAt(j + 2) == '"') {
                    return rxNormalizeOperationText(body.substring(i + 3, j));
                }
                if (body.charAt(j) == '\\' && j + 1 < n) {
                    j += 2;
                    continue;
                }
                j++;
            }
            return null;
        }
        if (body.charAt(i) == '"') {
            int j = i + 1;
            StringBuilder sb = new StringBuilder();
            while (j < body.length()) {
                char c = body.charAt(j);
                if (c == '"') return rxNormalizeOperationText(sb.toString());
                if (c == '\\' && j + 1 < body.length()) {
                    sb.append(body.charAt(j + 1));
                    j += 2;
                    continue;
                }
                sb.append(c);
                j++;
            }
            return null;
        }
        return null;
    }

    /** {@code @Operation(...)} 본문에서 summary → description 순으로 텍스트블록·일반 문자열 추출. */
    private static String rxParseOperationAnnotationBody(String inner) {
        if (inner == null || inner.isBlank()) return null;
        Matcher sm = Pattern.compile("\\bsummary\\s*=").matcher(inner);
        if (sm.find()) {
            String v = rxExtractQuotedOrTextBlockAfter(sm, inner);
            if (v != null && !v.isBlank()) return v;
        }
        Matcher dm = Pattern.compile("\\bdescription\\s*=").matcher(inner);
        if (dm.find()) {
            String v = rxExtractQuotedOrTextBlockAfter(dm, inner);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String rxExtractApiOperationFromHeadArea(String headArea) {
        if (headArea == null || headArea.isEmpty()) return "-";
        int lastAo = -1;
        Matcher aoHead = Pattern.compile("@ApiOperation\\s*").matcher(headArea);
        while (aoHead.find()) lastAo = aoHead.start();
        if (lastAo >= 0) {
            int paren = headArea.indexOf('(', lastAo);
            if (paren >= 0) {
                int close = rxIndexOfClosingParenBalanced(headArea, paren);
                if (close > paren) {
                    String inner = headArea.substring(paren + 1, close);
                    Matcher vm = Pattern.compile("value\\s*=").matcher(inner);
                    if (vm.find()) {
                        String v = rxExtractQuotedOrTextBlockAfter(vm, inner);
                        if (v != null && !v.isBlank()) return v;
                    }
                }
            }
        }
        int lastOp = -1;
        Matcher opHead = Pattern.compile("@Operation\\s*").matcher(headArea);
        while (opHead.find()) lastOp = opHead.start();
        if (lastOp >= 0) {
            int paren = headArea.indexOf('(', lastOp);
            if (paren >= 0) {
                int close = rxIndexOfClosingParenBalanced(headArea, paren);
                if (close > paren) {
                    String inner = headArea.substring(paren + 1, close);
                    String v = rxParseOperationAnnotationBody(inner);
                    if (v != null && !v.isBlank()) return v;
                }
            }
        }
        return "-";
    }

    private List<ApiInfo> extractApisWithRegexCore(String raw, String relPath, String controllerFileName,
                                                   List<String[]> git, String apiPathPrefix,
                                                   Map<String, String> pathConstantsMap, Path logFileForDebug,
                                                   List<Map<String, Object>> debugSteps, String traceId) {
        boolean debug = debugMode;
        List<ApiInfo> apis = new ArrayList<>();
        // 가장 확실한 방어: 클래스 선언 이전에 등장한 모든 매핑(@RequestMapping 등)은 "클래스 레벨"로 간주하고
        // 메서드 시그니처와 절대 묶지 않는다. (중간에 @Slf4j, @RequiredArgsConstructor 등 애노테이션이 있어도 동일)
        final int classDeclStart = rxIndexOfClassDeclarationStart(raw);
        String classPath = rxExtractClassLevelRequestMappingPath(raw, pathConstantsMap);
        if (debugSteps != null) {
            addRegexTrace(debugSteps, traceId, "INFO",
                    "[RX] 시작: file=" + controllerFileName
                            + " relPath=" + (relPath == null ? "" : relPath)
                            + " classPath=" + (classPath == null || classPath.isBlank() ? "(없음)" : classPath)
                            + " apiPathPrefix=" + (apiPathPrefix == null ? "" : apiPathPrefix)
                            + " scanChars=" + RX_AFTER_MAPPING_SCAN_CHARS
                            + " classDeclStart=" + classDeclStart);
        }
        if (debug) {
            String fn = logFileForDebug != null && logFileForDebug.getFileName() != null
                    ? logFileForDebug.getFileName().toString()
                    : controllerFileName;
            log.debug("[파싱-RX] 파일={}, @RequestMapping={}", fn,
                    classPath.isEmpty() ? "(없음)" : classPath);
        }

        String controllerComment = "-";
        Matcher cM = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL).matcher(raw);
        if (cM.find()) controllerComment = cM.group(1).replaceAll("[\\r\\n*]", " ").trim();

        int searchFrom = 0;
        RegexMappingSlice slice;
        int mappingIndex = 0;
        while ((slice = rxNextMappingSlice(raw, searchFrom)) != null) {
            searchFrom = slice.endExclusive;
            if (debugSteps != null) {
                addRegexTrace(debugSteps, traceId, "DEBUG",
                        "[RX] mapping#" + mappingIndex + " detect"
                                + " type=" + slice.mappingType
                                + " range=[" + slice.start + "," + slice.endExclusive + ")"
                                + " paramsRaw=\"" + rxDbgPreview(slice.paramsRaw, 400) + "\"");
            }
            if (classDeclStart >= 0 && slice.start < classDeclStart) {
                if (debugSteps != null) {
                    addRegexTrace(debugSteps, traceId, "WARN",
                            "[RX] mapping#" + mappingIndex + " SKIP_BEFORE_CLASS_DECL"
                                    + " classDeclStart=" + classDeclStart);
                }
                mappingIndex++;
                continue;
            }
            if (rxMappingSliceFollowedByClassDecl(raw, slice.endExclusive)) {
                if (debugSteps != null) {
                    String to = raw.substring(slice.endExclusive, Math.min(slice.endExclusive + 120, raw.length()));
                    addRegexTrace(debugSteps, traceId, "WARN",
                            "[RX] mapping#" + mappingIndex + " SKIP_CLASS_LEVEL"
                                    + " peek=\"" + rxDbgPreview(rxStripCommentsChunk(to), 200) + "\"");
                }
                mappingIndex++;
                continue;
            }
            String mappingType = slice.mappingType;
            String paramsRaw = slice.paramsRaw == null ? "" : slice.paramsRaw;
            String params = substituteConstants(paramsRaw, pathConstantsMap).replaceAll("\"\\s*\\+\\s*\"", "");
            String httpMethod = resolveHttpMethodFromName(mappingType, params);

            int mapEnd = slice.endExclusive;
            String afterChunk = raw.substring(mapEnd, Math.min(mapEnd + RX_AFTER_MAPPING_SCAN_CHARS, raw.length()));
            String afterMapping = rxStripCommentsChunk(afterChunk);
            RegexMethodNameMatch sig = rxFindMethodNameMatch(afterMapping);
            if (sig == null) {
                if (debugSteps != null) {
                    addRegexTrace(debugSteps, traceId, "WARN",
                            "[RX] mapping#" + mappingIndex + " SKIP_NO_METHOD_SIG"
                                    + " afterPreview=\"" + rxDbgPreview(afterMapping, 260) + "\"");
                }
                mappingIndex++;
                continue;
            }

            String methodName = sig.methodName();
            int mapStart = slice.start;
            boolean isDeprecated = raw.substring(Math.max(0, mapStart - 300), mapStart).contains("@Deprecated");
            String headArea = raw.substring(Math.max(0, mapStart - 1000), mapStart);
            if (debugSteps != null) {
                addRegexTrace(debugSteps, traceId, "DEBUG",
                        "[RX] mapping#" + mappingIndex + " methodSig"
                                + " kind=" + sig.matchKind()
                                + " methodName=" + methodName
                                + " deprecated=" + (isDeprecated ? "Y" : "N")
                                + " headPreview=\"" + rxDbgPreview(headArea, 260) + "\"");
            }

            List<String> paths = extractMappingPathsFromParams(params);
            boolean found = !paths.isEmpty();
            if (debugSteps != null) {
                addRegexTrace(debugSteps, traceId, found ? "DEBUG" : "WARN",
                        "[RX] mapping#" + mappingIndex + " paths"
                                + " found=" + found
                                + " rawParams=\"" + rxDbgPreview(params, 400) + "\""
                                + " paths=" + paths);
            }
            for (String s : paths) {
                if (s == null) continue;
                s = s.trim();
                if (s.isEmpty()) continue;

                String finalPath = normalizePath(apiPathPrefix + classPath + "/" + s);

                ApiInfo info = new ApiInfo();
                info.setApiPath(finalPath);
                info.setHttpMethod(httpMethod);
                info.setMethodName(methodName);
                info.setControllerName(controllerFileName);
                info.setRepoPath(relPath.replace("\\", "/"));
                info.setIsDeprecated(isDeprecated ? "Y" : "N");
                String mBody = afterMapping.substring(sig.signatureMatchEnd(),
                        Math.min(sig.signatureMatchEnd() + 500, afterMapping.length()));
                info.setHasUrlBlock(detectUrlBlockRegex(mBody) ? "Y" : "N");
                info.setProgramId(autoExtractProgramId(finalPath));
                info.setControllerComment(controllerComment);
                info.setGit1(git.get(0)); info.setGit2(git.get(1)); info.setGit3(git.get(2));
                info.setGit4(git.get(3)); info.setGit5(git.get(4));

                Matcher docM = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL).matcher(headArea);
                if (docM.find()) {
                    String doc = docM.group(1);
                    info.setFullComment(doc.replaceAll("[\\r\\n*]", " ").trim());
                    Matcher dM = Pattern.compile("@?(description|deprecation)[\\s:]*([^@\\n\\r*]+)",
                            Pattern.CASE_INSENSITIVE).matcher(doc);
                    info.setDescriptionTag(dM.find() ? dM.group(2).trim() : "-");
                } else {
                    info.setFullComment("-"); info.setDescriptionTag("-");
                }
                if (isDeprecated && (info.getFullComment().equals("-") || !ApiStorageService.containsUrlBlockTag(info.getFullComment()))) {
                    String depLine = extractDeprecatedLine(headArea);
                    if (depLine != null && ApiStorageService.containsUrlBlockTag(depLine)) {
                        info.setFullComment(depLine);
                    }
                }

                String op = rxExtractApiOperationFromHeadArea(headArea);
                info.setApiOperationValue(op);
                info.setRequestPropertyValue("-");
                info.setControllerRequestPropertyValue("-");
                info.setBlockMarkingIncomplete(computeBlockMarkingIncomplete(
                        isFirstStmtUrlBlockRegex(mBody),
                        info.getIsDeprecated(),
                        info.getFullComment()));
                apis.add(info);
                if (debugSteps != null) {
                    addRegexTrace(debugSteps, traceId, "OK",
                            "[RX] mapping#" + mappingIndex + " ADD"
                                    + " " + httpMethod + " " + finalPath
                                    + " methodName=" + methodName
                                    + " op=\"" + rxDbgPreview(op, 200) + "\""
                                    + " urlBlock=" + info.getHasUrlBlock());
                }
                if (debug) {
                    log.debug("[파싱-RX]   {} {} | method={} | deprecated={} | urlBlock={} | markingIncomplete={}",
                            httpMethod, finalPath, methodName, info.getIsDeprecated(), info.getHasUrlBlock(),
                            info.isBlockMarkingIncomplete());
                }
            }

            if (!found) {
                String finalPath = normalizePath(apiPathPrefix + classPath);
                ApiInfo info = new ApiInfo();
                info.setApiPath(finalPath.isEmpty() ? "/" : finalPath);
                info.setHttpMethod(httpMethod);
                info.setMethodName(methodName);
                info.setControllerName(controllerFileName);
                info.setRepoPath(relPath.replace("\\", "/"));
                info.setIsDeprecated(isDeprecated ? "Y" : "N");
                String mBody2 = afterMapping.substring(sig.signatureMatchEnd(),
                        Math.min(sig.signatureMatchEnd() + 500, afterMapping.length()));
                info.setHasUrlBlock(detectUrlBlockRegex(mBody2) ? "Y" : "N");
                info.setProgramId(autoExtractProgramId(finalPath));
                info.setControllerComment(controllerComment);
                info.setGit1(git.get(0)); info.setGit2(git.get(1)); info.setGit3(git.get(2));
                info.setGit4(git.get(3)); info.setGit5(git.get(4));
                Matcher docM2 = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL).matcher(headArea);
                if (docM2.find()) {
                    String doc = docM2.group(1);
                    info.setFullComment(doc.replaceAll("[\\r\\n*]", " ").trim());
                    Matcher dM = Pattern.compile("@?(description|deprecation)[\\s:]*([^@\\n\\r*]+)",
                            Pattern.CASE_INSENSITIVE).matcher(doc);
                    info.setDescriptionTag(dM.find() ? dM.group(2).trim() : "-");
                } else {
                    info.setFullComment("-"); info.setDescriptionTag("-");
                }
                if (isDeprecated && (info.getFullComment().equals("-") || !ApiStorageService.containsUrlBlockTag(info.getFullComment()))) {
                    String depLine = extractDeprecatedLine(headArea);
                    if (depLine != null && ApiStorageService.containsUrlBlockTag(depLine)) {
                        info.setFullComment(depLine);
                    }
                }
                String op2 = rxExtractApiOperationFromHeadArea(headArea);
                info.setApiOperationValue(op2);
                info.setRequestPropertyValue("-");
                info.setControllerRequestPropertyValue("-");
                info.setBlockMarkingIncomplete(computeBlockMarkingIncomplete(
                        isFirstStmtUrlBlockRegex(mBody2),
                        info.getIsDeprecated(),
                        info.getFullComment()));
                apis.add(info);
                if (debugSteps != null) {
                    addRegexTrace(debugSteps, traceId, "OK",
                            "[RX] mapping#" + mappingIndex + " ADD_EMPTY_MAPPING"
                                    + " " + httpMethod + " " + info.getApiPath()
                                    + " methodName=" + methodName
                                    + " op=\"" + rxDbgPreview(op2, 200) + "\""
                                    + " urlBlock=" + info.getHasUrlBlock());
                }
                if (debug) {
                    log.debug("[파싱-RX]   {} {} (빈매핑) | method={} | deprecated={} | markingIncomplete={}",
                            httpMethod, info.getApiPath(), methodName, info.getIsDeprecated(),
                            info.isBlockMarkingIncomplete());
                }
            }
            mappingIndex++;
        }
        if (debug) {
            String fn = logFileForDebug != null && logFileForDebug.getFileName() != null
                    ? logFileForDebug.getFileName().toString()
                    : controllerFileName;
            log.debug("[파싱-RX] {} 완료 — {}개 API 추출", fn, apis.size());
        }
        if (debugSteps != null) {
            addRegexTrace(debugSteps, traceId, "INFO",
                    "[RX] 종료: mappings=" + mappingIndex + " apis=" + apis.size());
        }
        return apis;
    }

    private List<ApiInfo> extractWithRegex(Path filePath, String relPath,
                                            List<String[]> git,
                                            String apiPathPrefix,
                                            Map<String, String> pathConstantsMap) {
        try {
            String raw = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            return extractApisWithRegexCore(raw, relPath, filePath.getFileName().toString(), git,
                    apiPathPrefix, pathConstantsMap, filePath, null, null);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /**
     * Regex 폴백에서 Mapping 파라미터 문자열(params)로부터 "경로"만 추출한다.
     * - 허용: (1) 단일 멤버 형태: {@code @PostMapping("/path")} → ["/path"]
     * - 허용: (2) named 속성: {@code value="/path"} 또는 {@code path={"/a","/b"}}
     * - 제외: consumes/produces/headers/params/name 등 부가 속성의 문자열
     */
    private List<String> extractMappingPathsFromParams(String params) {
        if (params == null) return Collections.emptyList();
        String s = params.trim();
        if (s.isEmpty()) return Collections.emptyList();

        // 1) named 속성(value/path) 우선
        Matcher m = Pattern.compile("(?:(?:^|[,\\s])(?:value|path)\\s*=\\s*)(\\{[\\s\\S]*?\\}|\"[^\"]+\")",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) {
            String seg = m.group(1);
            List<String> out = new ArrayList<>();
            Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(seg);
            while (q.find()) out.add(q.group(1));
            return out;
        }

        // 2) 단일 멤버: "=" 이 전혀 없고 문자열 리터럴 1개 이상이면 첫 문자열을 경로로 간주
        if (!s.contains("=")) {
            Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(s);
            if (q.find()) return List.of(q.group(1));
        }
        return Collections.emptyList();
    }

    // ======================================================
    // ApiInfo 빌더 (JavaParser용)
    // ======================================================

    private ApiInfo buildApiInfo(Path filePath, String relPath, MethodDeclaration method,
                                  List<String[]> git, String finalPath, String httpMethod,
                                  String controllerComment, String controllerRequestProperty,
                                  String entireFileSource) {
        ApiInfo info = new ApiInfo();
        info.setApiPath(finalPath.isEmpty() ? "/" : finalPath);
        info.setHttpMethod(httpMethod);
        info.setMethodName(method.getNameAsString());
        info.setControllerName(filePath.getFileName().toString());
        info.setRepoPath(relPath.replace("\\", "/"));
        info.setIsDeprecated(method.isAnnotationPresent("Deprecated") ? "Y" : "N");
        info.setHasUrlBlock(detectUrlBlock(method) ? "Y" : "N");
        info.setProgramId(autoExtractProgramId(finalPath));
        info.setControllerComment(controllerComment);
        info.setControllerRequestPropertyValue(controllerRequestProperty);
        info.setGit1(git.get(0)); info.setGit2(git.get(1)); info.setGit3(git.get(2));
        info.setGit4(git.get(3)); info.setGit5(git.get(4));

        if (method.getComment().isPresent()) {
            String doc = method.getComment().get().getContent();
            info.setFullComment(doc.replaceAll("[\\r\\n*]", " ").trim());
            Matcher dM = Pattern.compile("@?(description|deprecation)[\\s:]*([^@\\n\\r*]+)",
                    Pattern.CASE_INSENSITIVE).matcher(doc);
            info.setDescriptionTag(dM.find() ? dM.group(2).trim() : "-");
        } else {
            info.setFullComment("-"); info.setDescriptionTag("-");
        }
        // @Deprecated 라인에서 [URL…차단…] 태그 정보 보충
        if ("Y".equals(info.getIsDeprecated()) && (info.getFullComment().equals("-") || !ApiStorageService.containsUrlBlockTag(info.getFullComment()))) {
            try {
                String src = entireFileSource != null ? entireFileSource : Files.readString(filePath, StandardCharsets.UTF_8);
                String depLine = extractDeprecatedLine(src);
                if (depLine != null && ApiStorageService.containsUrlBlockTag(depLine)) {
                    info.setFullComment(depLine);
                }
            } catch (Exception ignore) {}
        }

        info.setRequestPropertyValue(extractRequestPropertyFromNode(method));

        // @ApiOperation 우선, 없으면 @Operation(summary) 폴백
        String op = extractAnnotationValue(method, "ApiOperation", "value");
        if ("-".equals(op)) op = extractAnnotationValue(method, "Operation", "summary");
        info.setApiOperationValue(op);

        // 표기 미흡 판정 — 메서드 첫 실행 문장이 UnsupportedOperationException throw 인데
        // @Deprecated 또는 [URL차단작업] 주석 중 하나라도 누락된 경우 true
        info.setBlockMarkingIncomplete(computeBlockMarkingIncomplete(
                isFirstStmtUrlBlock(method),
                info.getIsDeprecated(),
                info.getFullComment()));

        // 소스 패치/매칭용 — 메소드 Range 라인 (없을 수 있음)
        try {
            method.getRange().ifPresent(r -> {
                info.setMethodBeginLine(r.begin.line);
                info.setMethodEndLine(r.end.line);
            });
        } catch (Exception ignore) {}

        return info;
    }

    /**
     * 서버 내부 기능(소스 패치/다운로드 등)에서 사용하기 위한 파서 결과 반환.
     * - debugParseJavaSource 와 유사하나 preview 가 아닌 전체 ApiInfo 를 반환한다.
     * - 파일 접근 없이, 문자열 소스만으로 파싱한다.
     */
    public List<ApiInfo> parseJavaSourceForInternalUse(String javaSource, String relPath, String apiPathPrefix, String pathConstantsRaw) {
        if (javaSource == null) throw new IllegalArgumentException("source is required");
        String rel = (relPath == null || relPath.isBlank()) ? "__internal__/Controller.java" : relPath.trim().replace('\\', '/');
        if (rel.contains("..")) throw new IllegalArgumentException("unsafe relPath");
        Map<String, String> constants = parsePathConstants(pathConstantsRaw);
        List<String[]> git = List.of(
                new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""},
                new String[]{"", "", ""}, new String[]{"", "", ""});
        ensureJavaParserConfigured();
        NormalizationResult nr = normalizeForJavaParser(javaSource);
        Path pseudo = Paths.get(rel);
        try {
            return extractWithJavaParserFromSource(nr.source(), pseudo, rel, git,
                    apiPathPrefix == null ? "" : apiPathPrefix, constants);
        } catch (Exception e) {
            throw new RuntimeException("JavaParser parsing failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
    }

    // ======================================================
    // JavaParser 헬퍼
    // ======================================================

    private List<String> getPathsFromAnn(AnnotationExpr ann, Map<String, String> constantsMap) {
        // LinkedHashSet: 삽입 순서 유지 + 어노테이션 배열 내 중복값 자동 제거
        // ex) @RequestMapping({"aaa.lc", "bbb.lc", "aaa.lc"}) → ["aaa.lc", "bbb.lc"]
        Set<String> seen = new LinkedHashSet<>();
        Expression value = null;
        if (ann instanceof SingleMemberAnnotationExpr se) {
            value = se.getMemberValue();
        } else if (ann instanceof NormalAnnotationExpr ne) {
            value = ne.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
                    .map(MemberValuePair::getValue).findFirst().orElse(null);
        }

        if (value instanceof ArrayInitializerExpr ae) {
            for (Expression expr : ae.getValues()) {
                String eval = evaluateExpression(expr, constantsMap);
                if (!eval.isEmpty()) seen.add(eval);
            }
        } else if (value != null) {
            String eval = evaluateExpression(value, constantsMap);
            if (!eval.isEmpty()) seen.add(eval);
        }
        return new ArrayList<>(seen);
    }

    private String evaluateExpression(Expression expr, Map<String, String> constantsMap) {
        if (expr instanceof StringLiteralExpr sl) return sl.getValue();
        if (expr instanceof BinaryExpr be && be.getOperator() == BinaryExpr.Operator.PLUS)
            return evaluateExpression(be.getLeft(), constantsMap) + evaluateExpression(be.getRight(), constantsMap);
        if (expr instanceof FieldAccessExpr || expr instanceof NameExpr)
            return constantsMap.getOrDefault(expr.toString(), "{" + expr + "}");
        return "";
    }

    private String extractAnnotationValue(MethodDeclaration method, String annName, String attrName) {
        Optional<AnnotationExpr> ann = method.getAnnotationByName(annName);
        if (ann.isEmpty()) return "-";
        if (ann.get() instanceof NormalAnnotationExpr ne) {
            return ne.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals(attrName))
                    .map(p -> evalAnnotationStringValue(p.getValue()))
                    .findFirst().orElse("-");
        }
        if (ann.get() instanceof SingleMemberAnnotationExpr se && "value".equals(attrName)) {
            return evalAnnotationStringValue(se.getMemberValue());
        }
        return "-";
    }

    private String evalAnnotationStringValue(Expression expr) {
        if (expr == null) return "-";
        try {
            if (expr.isStringLiteralExpr()) return expr.asStringLiteralExpr().getValue();
            // Java 15+ text block: """ ... """
            if (expr.isTextBlockLiteralExpr()) return expr.asTextBlockLiteralExpr().getValue();
            // 문자열 연결: "a" + "b" / "a" + SOME_CONST (const는 소스 문자열로 fallback)
            if (expr.isBinaryExpr() && expr.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
                String left = evalAnnotationStringValue(expr.asBinaryExpr().getLeft());
                String right = evalAnnotationStringValue(expr.asBinaryExpr().getRight());
                if ("-".equals(left)) left = expr.asBinaryExpr().getLeft().toString().replace("\"", "");
                if ("-".equals(right)) right = expr.asBinaryExpr().getRight().toString().replace("\"", "");
                return (left + right).trim().isEmpty() ? "-" : (left + right);
            }
            // 그 외: 최대한 소스 문자열을 유지 (따옴표만 제거)
            String s = expr.toString().replace("\"", "");
            return s.isBlank() ? "-" : s;
        } catch (Exception e) {
            String s = expr.toString().replace("\"", "");
            return s.isBlank() ? "-" : s;
        }
    }

    private String extractRequestPropertyFromNode(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        Optional<AnnotationExpr> ann = node.getAnnotationByName("RequestProperty");
        if (ann.isEmpty()) return "-";
        if (ann.get() instanceof NormalAnnotationExpr ne) {
            String title = ne.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("title"))
                    .map(p -> p.getValue().toString().replaceAll("\"", ""))
                    .findFirst().orElse(null);
            if (title != null) return title;
            return ne.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .map(p -> p.getValue().toString().replaceAll("\"", ""))
                    .findFirst().orElse("-");
        }
        if (ann.get() instanceof SingleMemberAnnotationExpr se)
            return se.getMemberValue().toString().replaceAll("\"", "");
        return "-";
    }

    // ======================================================
    // HTTP 메소드 판별
    // ======================================================

    private String resolveHttpMethod(String annName, AnnotationExpr ann) {
        if (!annName.equals("RequestMapping")) return annName.replace("Mapping", "").toUpperCase();
        // RequestMapping은 method 속성 확인
        if (ann instanceof NormalAnnotationExpr ne) {
            Optional<String> method = ne.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("method"))
                    .map(p -> p.getValue().toString())
                    .findFirst();
            if (method.isPresent()) {
                String m = method.get().toUpperCase();
                if (m.contains("GET")) return "GET";
                if (m.contains("POST")) return "POST";
                if (m.contains("PUT")) return "PUT";
                if (m.contains("DELETE")) return "DELETE";
                if (m.contains("PATCH")) return "PATCH";
            }
        }
        return "REQUEST";
    }

    private String resolveHttpMethodFromName(String mappingType, String params) {
        if (!mappingType.equals("RequestMapping")) return mappingType.replace("Mapping", "").toUpperCase();
        String upper = params.toUpperCase();
        if (upper.contains("GET")) return "GET";
        if (upper.contains("POST")) return "POST";
        if (upper.contains("PUT")) return "PUT";
        if (upper.contains("DELETE")) return "DELETE";
        if (upper.contains("PATCH")) return "PATCH";
        return "REQUEST";
    }

    // ======================================================
    // 프로그램 ID 자동 추출 (ApiExcelExporter 동일 로직)
    // ======================================================

    /**
     * JavaParser: 메서드 본문 **어디든** UnsupportedOperationException 을 throw 하면서
     * 첫 번째 인자(메시지 문자열 리터럴)에 "차단" 이 포함되면 true.
     *
     * (이전에는 메서드 첫 실행 문장만 검사했으나, 개발자 관행상 선행 로직 뒤
     *  차단 throw 를 두는 경우도 있어 위치 제약을 제거하고 메시지 "차단" 키워드로 의미를 한정함.)
     */
    private boolean detectUrlBlock(MethodDeclaration method) {
        if (method.getBody().isEmpty()) return false;
        return method.getBody().get()
                .findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                .anyMatch(oc -> {
                    if (!"UnsupportedOperationException".equals(oc.getType().getNameAsString())) return false;
                    // 첫 번째 인자가 문자열 리터럴이고 "차단" 포함
                    var args = oc.getArguments();
                    if (args.isEmpty()) return false;
                    var first = args.get(0);
                    if (first.isStringLiteralExpr()) {
                        return first.asStringLiteralExpr().getValue().contains("차단");
                    }
                    // 복잡한 표현식(상수·변수) 은 소스 문자열로 fallback 매칭
                    return first.toString().contains("차단");
                });
    }

    /**
     * Regex 폴백: 메서드 본문 어디든 `new UnsupportedOperationException("...차단...")` 패턴이면 true.
     * throw 키워드 없이 생성만 하는 변칙 케이스도 허용(의미 동일).
     */
    private boolean detectUrlBlockRegex(String methodBodySnippet) {
        if (methodBodySnippet == null) return false;
        return Pattern.compile(
                "new\\s+UnsupportedOperationException\\s*\\(\\s*\"[^\"]*차단[^\"]*\"",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        ).matcher(methodBodySnippet).find();
    }

    /** 차단 판정 — 위치 무관 "차단" 메시지 포함 여부로 동일 판정. (이전 이름 유지 — 호출부 호환) */
    private boolean isFirstStmtUrlBlock(MethodDeclaration method) { return detectUrlBlock(method); }

    /** Regex 경로 — 위치 무관 판정으로 통일. */
    private boolean isFirstStmtUrlBlockRegex(String methodBodySnippet) {
        return detectUrlBlockRegex(methodBodySnippet);
    }

    /**
     * 차단처리미흡 플래그 계산.
     * 실질 차단(UnsupportedOperationException throw + 메시지 "차단" 포함) 이면서
     * @Deprecated 또는 [URL차단작업] 주석 중 하나라도 누락되면 true.
     */
    private boolean computeBlockMarkingIncomplete(boolean urlBlockDetected, String isDeprecated, String fullComment) {
        if (!urlBlockDetected) return false;
        boolean deprecatedOk = "Y".equals(isDeprecated);
        boolean commentOk = ApiStorageService.containsUrlBlockTag(fullComment);
        return !(deprecatedOk && commentOk);
    }

    /**
     * @Deprecated 인근 텍스트에서 [URL차단작업] 라인 추출.
     *
     * 탐색 순서 (하나라도 찾으면 즉시 반환):
     *  ① @Deprecated 같은 줄 뒤쪽 (`@Deprecated [URL차단작업]...`)
     *  ② @Deprecated 다음 줄/아래 라인 (`@Deprecated\n[URL차단작업]...`)
     *  ③ @Deprecated **바로 위** 주석 블록들 — 원본 javadoc 과 별도로
     *      `/** @deprecated [URL차단작업]... *\/` 블록을 @Deprecated 바로 위에 덧붙이는 개발자 관행 커버.
     *      주석 블록이 여러 개 이어져 있어도(예: 원본 javadoc + 차단 태그 javadoc) 어느 블록이든 포함되면 감지.
     */
    /** 대괄호 안에 URL·차단 모두 포함된 토큰 — 변형 표기 허용 (예: [URL차단작업], [URL 차단작업], [차단URL완료]) */
    private static final Pattern URL_BLOCK_TAG_IN_TEXT =
            Pattern.compile("\\[(?=[^\\[\\]]*URL)(?=[^\\[\\]]*차단)[^\\[\\]]+\\]");

    private String extractDeprecatedLine(String source) {
        if (source == null) return null;
        // ① 같은 줄
        Matcher m = Pattern.compile("@Deprecated\\s+(.+)", Pattern.MULTILINE).matcher(source);
        if (m.find()) {
            String line = m.group(1).trim();
            if (ApiStorageService.containsUrlBlockTag(line)) return line;
        }
        // ② 뒤쪽 줄 — URL·차단 포함 대괄호 토큰 이후 라인 추출
        Matcher m2 = Pattern.compile(
                "@Deprecated[\\s\\S]*?(" + URL_BLOCK_TAG_IN_TEXT.pattern() + ".+)",
                Pattern.MULTILINE).matcher(source);
        if (m2.find()) return m2.group(1).trim();

        // ③ @Deprecated 바로 위 주석 블록
        int depIdx = source.indexOf("@Deprecated");
        if (depIdx >= 0) {
            int scanStart = Math.max(0, depIdx - 4000);   // 과도 범위 방지
            String pre = source.substring(scanStart, depIdx);
            // 직전 메서드/블록 경계 (}) 이후로 한정 — 다른 메서드의 URL차단 주석 오판 방지
            int lastBrace = pre.lastIndexOf("}");
            if (lastBrace >= 0) pre = pre.substring(lastBrace);
            if (ApiStorageService.containsUrlBlockTag(pre)) {
                Matcher m3 = Pattern.compile("(" + URL_BLOCK_TAG_IN_TEXT.pattern() + "[^\\n\\r]*)").matcher(pre);
                if (m3.find()) {
                    // 주석 블록 종료 표식(*/ 또는 ** 등) 제거 + 선행 * 공백 제거
                    return m3.group(1).replaceAll("\\s*\\*+/\\s*$", "").trim();
                }
            }
        }
        return null;
    }

    private String autoExtractProgramId(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return "-";

        // 7글자_4글자.확장자 패턴: LPMAIAA_V100.lc → MAI (앞2글자 스킵, 3~5번째가 프로그램ID)
        Matcher pgm = Pattern.compile("(\\w{2})(\\w{3})\\w{2}_\\w{4}\\.\\w+").matcher(path);
        if (pgm.find()) return pgm.group(2).toUpperCase();

        if (path.contains(".")) {
            String nameOnly = path.substring(path.lastIndexOf("/") + 1).split("\\.")[0];
            return nameOnly.contains("_") ? nameOnly.substring(0, nameOnly.lastIndexOf("_")) : nameOnly;
        }
        String[] segments = path.split("/");
        List<String> valid = new ArrayList<>();
        List<String> actions = Arrays.asList("new", "edit", "update", "delete", "create", "list", "save", "view");
        for (String s : segments)
            if (!s.isEmpty() && !s.startsWith("{") && !actions.contains(s.toLowerCase())) valid.add(s);
        return valid.isEmpty() ? "-" : valid.get(valid.size() - 1);
    }

    // ======================================================
    // Git 히스토리 조회
    // ======================================================

    private List<String[]> getRecentGitHistories(String rel, String root, String gitBin, int count) {
        List<String[]> h = new ArrayList<>();
        for (int i = 0; i < count; i++) h.add(new String[]{"-", "-", "No History"});
        try {
            Process p = new ProcessBuilder(gitBin, "log", "-" + count,
                    "--pretty=format:%as|%an|%s", "--", rel)
                    .directory(new File(root)).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
                for (int i = 0; i < Math.min(lines.size(), count); i++) {
                    String[] parts = lines.get(i).split("\\|", 3);
                    if (parts.length >= 2)
                        h.set(i, new String[]{parts[0], parts[1], parts.length > 2 ? parts[2] : ""});
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return h;
    }

    /**
     * TS 등 {@link ApiInfo#getRepoPath()}가 있는 항목에 대해, 파일(상대경로)당 1회 {@code git log}로 git1~5를 채운다.
     */
    private void applyGitHistoryPerRepoPath(List<ApiInfo> apis, String rootPath, String gitBin) {
        if (apis == null || apis.isEmpty()) return;
        String root = rootPath == null ? "" : rootPath.trim();
        if (root.isEmpty()) return;
        String bin = (gitBin != null && !gitBin.isBlank()) ? gitBin : defaultGitBinPath;
        Map<String, List<String[]>> gitByRel = new HashMap<>();
        for (ApiInfo a : apis) {
            if (a == null) continue;
            String rel = a.getRepoPath();
            if (rel == null || rel.isBlank()) continue;
            gitByRel.computeIfAbsent(rel, k -> getRecentGitHistories(k, root, bin, 5));
            List<String[]> g = gitByRel.get(rel);
            if (g != null && g.size() >= 5) {
                a.setGit1(g.get(0));
                a.setGit2(g.get(1));
                a.setGit3(g.get(2));
                a.setGit4(g.get(3));
                a.setGit5(g.get(4));
            }
        }
    }

    /**
     * 대상 레포 working tree 를 origin/{branch} 기준으로 강제 정렬한다.
     * 로컬 변경·divergent history·detached HEAD·단일 브랜치 클론 어느 상태에서도 최신 원격 커밋으로 맞춘다.
     *
     * 순서:
     *   1) target branch resolve (인자 비면 원격 기본 브랜치 자동 감지)
     *   2) 명시 refspec 으로 git fetch origin (--single-branch 클론도 해당 브랜치 수신)
     *   3) origin/{branch} 존재 검증 — 없으면 명확한 예외
     *   4) before HEAD 기록 → git checkout -B {branch} origin/{branch} → git clean -fd
     *   5) after HEAD 비교 — 변경 없음이면 리턴 문자열에 표기
     *
     * 서버의 레포 디렉토리는 읽기 전용 분석 미러이므로 로컬 변경은 폐기가 안전하다.
     * 분석 전용이 아닌 곳에서는 호출하지 말 것.
     */
    public String hardSyncToOrigin(java.io.File repoDir, String gitBin, String branch) throws Exception {
        StringBuilder out = new StringBuilder();
        log.debug("[hardSync] 시작 — repo={}, branch={}", repoDir.getAbsolutePath(), branch);

        // Step1. 대상 브랜치 resolve
        String targetBranch;
        if (branch != null && !branch.isBlank()) {
            targetBranch = branch.trim();
            log.debug("[hardSync] Step1. 대상 브랜치 = 설정값 '{}'", targetBranch);
        } else {
            targetBranch = resolveRemoteDefaultBranch(repoDir, gitBin);
            log.debug("[hardSync] Step1. 대상 브랜치 = 원격 기본 브랜치 '{}' (설정 미지정)", targetBranch);
        }
        out.append("branch=").append(targetBranch).append(" / ");

        // Step2. 명시 refspec 으로 fetch — --single-branch 클론도 커버
        String refspec = "+refs/heads/" + targetBranch + ":refs/remotes/origin/" + targetBranch;
        log.debug("[hardSync] Step2. git fetch origin --prune {}", refspec);
        String fetchOut;
        try {
            fetchOut = runGitCommand(repoDir, gitBin, "fetch", "origin", "--prune", refspec);
        } catch (Exception fetchEx) {
            throw new RuntimeException("원격 브랜치 fetch 실패: origin/" + targetBranch
                    + ". repo_config.git_branch 확인 필요. 원인: " + fetchEx.getMessage());
        }
        log.debug("[hardSync] fetch 결과: {}", fetchOut);

        // Step3. origin/{branch} 존재 검증
        try {
            runGitCommand(repoDir, gitBin, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/" + targetBranch);
        } catch (Exception verifyEx) {
            throw new RuntimeException("원격에 브랜치 없음: origin/" + targetBranch
                    + ". repo_config.git_branch=" + (branch == null ? "(empty)" : branch) + " 확인 필요");
        }

        // Step4. before HEAD → checkout -B → clean
        String beforeSha;
        try {
            beforeSha = runGitCommand(repoDir, gitBin, "rev-parse", "--short", "HEAD").trim();
        } catch (Exception e) {
            beforeSha = "(detached/empty)";
        }

        log.debug("[hardSync] Step4. git checkout -B {} origin/{}", targetBranch, targetBranch);
        String checkoutOut = runGitCommand(repoDir, gitBin, "checkout", "-B", targetBranch, "origin/" + targetBranch);
        log.debug("[hardSync] checkout 결과: {}", checkoutOut);

        log.debug("[hardSync] Step4. git clean -fd");
        String cleanOut = runGitCommand(repoDir, gitBin, "clean", "-fd");
        log.debug("[hardSync] clean 결과: {}", cleanOut);

        // Step5. after HEAD 비교
        String afterSha;
        try {
            afterSha = runGitCommand(repoDir, gitBin, "rev-parse", "--short", "HEAD").trim();
        } catch (Exception e) {
            afterSha = "(unknown)";
        }
        boolean unchanged = beforeSha.equals(afterSha);
        out.append("HEAD: ").append(beforeSha).append(" → ").append(afterSha);
        if (unchanged) out.append(" (변경 없음)");
        out.append(" / clean: ").append(cleanOut.isEmpty() ? "(no untracked)" : cleanOut);
        out.append(" / fetch: ").append(fetchOut.isEmpty() ? "(up-to-date)" : fetchOut);

        log.info("[hardSync] 완료 — repo={} branch={} HEAD: {} → {}{}",
                repoDir.getName(), targetBranch, beforeSha, afterSha, unchanged ? " (변경 없음)" : "");

        return out.toString();
    }

    /**
     * 원격(origin)의 기본 브랜치 이름을 감지한다.
     * 1차: git ls-remote --symref origin HEAD 출력의 refs/heads/{name} 파싱
     * 2차: git symbolic-ref --short refs/remotes/origin/HEAD 의 마지막 segment
     * 둘 다 실패하면 예외 throw.
     */
    private String resolveRemoteDefaultBranch(java.io.File repoDir, String gitBin) throws Exception {
        try {
            String out = runGitCommand(repoDir, gitBin, "ls-remote", "--symref", "origin", "HEAD");
            // "ref: refs/heads/main\tHEAD ..." 형태에서 main 추출
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("ref:\\s*refs/heads/(\\S+)\\s+HEAD").matcher(out);
            if (m.find()) {
                String name = m.group(1);
                log.debug("[hardSync] 원격 기본 브랜치 감지(ls-remote): {}", name);
                return name;
            }
        } catch (Exception e) {
            log.debug("[hardSync] ls-remote --symref 실패, symbolic-ref 폴백: {}", e.getMessage());
        }
        try {
            String symRef = runGitCommand(repoDir, gitBin, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").trim();
            // "origin/main" → "main"
            int slash = symRef.indexOf('/');
            String name = (slash >= 0) ? symRef.substring(slash + 1) : symRef;
            if (!name.isEmpty()) {
                log.debug("[hardSync] 원격 기본 브랜치 감지(symbolic-ref): {}", name);
                return name;
            }
        } catch (Exception e) {
            log.debug("[hardSync] symbolic-ref 폴백 실패: {}", e.getMessage());
        }
        throw new RuntimeException("원격 기본 브랜치를 확인할 수 없음. repo_config.git_branch 지정 필요");
    }

    /** Git 명령 실행 헬퍼 — 출력 문자열 반환, 실패 시 예외 */
    private String runGitCommand(java.io.File dir, String gitBin, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(gitBin);
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder output = new StringBuilder();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
            String line; while ((line = br.readLine()) != null) output.append(line).append(" ");
        }
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("exit=" + exitCode + " " + output.toString().trim());
        }
        return output.toString().trim();
    }

    // ======================================================
    // 유틸리티
    // ======================================================

    private String normalizePath(String path) {
        if (path == null) return "/";
        String result = path.replaceAll("/+", "/");
        if (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) return "/";
        return result;
    }

    private String substituteConstants(String text, Map<String, String> map) {
        for (Map.Entry<String, String> e : map.entrySet())
            text = text.replace(e.getKey(), "\"" + e.getValue() + "\"");
        return text;
    }

    private Map<String, String> parsePathConstants(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) return map;
        for (String pair : raw.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private static List<Map<String, Object>> previewApis(List<ApiInfo> apis) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(apis.size(), 50); i++) {
            ApiInfo a = apis.get(i);
            rows.add(Map.of(
                    "apiPath", a.getApiPath(),
                    "httpMethod", a.getHttpMethod(),
                    "methodName", a.getMethodName(),
                    "apiOperationValue", a.getApiOperationValue(),
                    "descriptionTag", a.getDescriptionTag(),
                    "isDeprecated", a.getIsDeprecated(),
                    "hasUrlBlock", a.getHasUrlBlock()
            ));
        }
        return rows;
    }

    private static void addDebugStep(List<Map<String, Object>> steps, String level, String message) {
        if (steps == null) return;
        steps.add(new LinkedHashMap<>(Map.of(
                "level", level == null ? "INFO" : level,
                "message", message == null ? "" : message
        )));
    }

    private static String rxDbgPreview(String s, int maxChars) {
        if (s == null) return "";
        String v = s.replaceAll("\\s+", " ").trim();
        if (maxChars <= 0) return v;
        if (v.length() <= maxChars) return v;
        return v.substring(0, maxChars) + "…";
    }

    private static String rxNewTraceId() {
        return "RX-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static void addRegexTrace(List<Map<String, Object>> steps, String traceId, String level, String message) {
        String msg = (traceId == null || traceId.isBlank())
                ? message
                : ("[" + traceId + "] " + message);
        addDebugStep(steps, level, msg);

        if (traceId == null || traceId.isBlank()) return;
        String tag = "[파싱-RX-DBG] traceId=" + traceId + " ";
        String lv = level == null ? "INFO" : level;
        if ("ERROR".equalsIgnoreCase(lv)) log.error("{}{}", tag, message);
        else if ("WARN".equalsIgnoreCase(lv)) log.warn("{}{}", tag, message);
        else if ("OK".equalsIgnoreCase(lv)) log.info("{}{}", tag, message);
        else log.debug("{}{}", tag, message);
    }

    // ======================================================
    // TypeScript(NestJS) 정규식 디버그 파서
    // ======================================================

    private static final int TS_DEBUG_PREVIEW_MAX = 260;

    private static final List<String> TS_DEFAULT_EXCLUDES = List.of(
            "**/node_modules/**",
            "**/dist/**",
            "**/build/**",
            "**/*.spec.ts",
            "**/*.test.ts",
            "**/*.d.ts"
    );

    private static List<PathMatcher> compileGlobMatchers(String rawCsv, List<String> defaults) {
        List<String> pats = new ArrayList<>();
        if (rawCsv != null && !rawCsv.isBlank()) {
            for (String p : rawCsv.split(",")) {
                String t = p == null ? "" : p.trim();
                if (!t.isEmpty()) pats.add(t);
            }
        } else if (defaults != null) {
            pats.addAll(defaults);
        }
        List<PathMatcher> out = new ArrayList<>();
        for (String p : pats) {
            try {
                out.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            } catch (Exception ignored) {
                // invalid glob: ignore
            }
        }
        return out;
    }

    private static boolean anyMatch(List<PathMatcher> ms, Path rel) {
        if (ms == null || ms.isEmpty()) return false;
        for (PathMatcher m : ms) {
            try {
                if (m.matches(rel)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String tsFirstQuotedLiteral(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("['\"`]([^'\"`]+)['\"`]").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> tsExtractPathsFromDecoratorArgs(String inner) {
        if (inner == null) return Collections.emptyList();
        String t = inner.trim();
        if (t.isEmpty()) return List.of("");

        // object arg: { path: '...' }
        Matcher pm = Pattern.compile("\\bpath\\s*:\\s*(['\"`])([^'\"`]+)\\1").matcher(t);
        if (pm.find()) return List.of(pm.group(2));

        // array: ['a','b']
        if (t.startsWith("[")) {
            List<String> out = new ArrayList<>();
            Matcher qm = Pattern.compile("['\"`]([^'\"`]+)['\"`]").matcher(t);
            while (qm.find()) out.add(qm.group(1));
            return out;
        }

        // pure literal only (concat/template/identifier는 UNRESOLVED 처리)
        // e.g. OK: '/x'  | NOT OK: BASE + '/x' , `${BASE}/x`
        Matcher lm = Pattern.compile("^\\s*(['\"`])([^'\"`]+)\\1\\s*$").matcher(t);
        if (lm.find()) return List.of(lm.group(2));

        return Collections.emptyList();
    }

    private static int tsIndexOfClassDecl(String src) {
        if (src == null) return -1;
        Matcher m = Pattern.compile("(?m)^\\s*(?:export\\s+)?class\\s+\\w+\\b").matcher(src);
        if (m.find()) return m.start();
        m = Pattern.compile("\\bclass\\s+\\w+\\b").matcher(src);
        return m.find() ? m.start() : -1;
    }

    private static String tsFindControllerPrefix(String src, int classDeclStart) {
        if (src == null) return "";
        int headEnd = classDeclStart >= 0 ? classDeclStart : Math.min(src.length(), 6000);
        String head = src.substring(0, Math.min(headEnd, src.length()));

        int idx = head.lastIndexOf("@Controller");
        if (idx < 0) return "";
        int paren = head.indexOf('(', idx);
        if (paren < 0) return "";
        int close = rxIndexOfClosingParenBalanced(head, paren);
        if (close < 0) return "";
        String inner = head.substring(paren + 1, close);
        String lit = tsFirstQuotedLiteral(inner);
        if (lit != null) return lit.trim();
        Matcher pm = Pattern.compile("\\bpath\\s*:\\s*(['\"`])([^'\"`]+)\\1").matcher(inner);
        return pm.find() ? pm.group(2).trim() : "";
    }

    private static String tsFindMethodNameAfter(String srcAfterDecorator) {
        if (srcAfterDecorator == null) return null;
        String chunk = rxStripCommentsChunk(srcAfterDecorator);
        // common NestJS handler: async name(...)
        Matcher m = Pattern.compile("(?m)^\\s*(?:public|private|protected)?\\s*(?:async\\s+)?(\\w+)\\s*\\(").matcher(chunk);
        while (m.find()) {
            String name = m.group(1);
            if (name == null) continue;
            if (RX_METHOD_NAME_BLACKLIST.contains(name)) continue;
            if (RX_METHOD_TYPEISH_BLACKLIST.contains(name)) continue;
            return name;
        }
        return null;
    }

    private List<ApiInfo> extractWithTsRegexDebug(Path root, String apiPathPrefix, String includeCsv, String excludeCsv,
                                                  List<Map<String, Object>> steps, String traceId,
                                                  Map<String, Object> countsOut) throws IOException {
        if (root == null) throw new IllegalArgumentException("rootPath is required");
        if (!Files.exists(root)) throw new IllegalArgumentException("rootPath not found: " + root);
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("rootPath is not a directory: " + root);

        List<PathMatcher> includes = compileGlobMatchers(
                (includeCsv == null || includeCsv.isBlank()) ? "**/*.ts" : includeCsv,
                List.of("**/*.ts"));
        List<PathMatcher> excludes = compileGlobMatchers(excludeCsv, TS_DEFAULT_EXCLUDES);

        int fileCount = 0;
        int mappingCount = 0;
        List<ApiInfo> apis = new ArrayList<>();

        addRegexTrace(steps, traceId, "INFO", "[TS] scan 시작 root=" + root.toString());

        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path rel = root.relativize(p);
                if (!anyMatch(includes, rel)) continue;
                if (anyMatch(excludes, rel)) {
                    addRegexTrace(steps, traceId, "DEBUG", "[TS] FILE_SKIP " + rel.toString().replace('\\', '/'));
                    continue;
                }
                fileCount++;
                String relStr = rel.toString().replace('\\', '/');
                addRegexTrace(steps, traceId, "DEBUG", "[TS] FILE_SCAN " + relStr);

                String src;
                try {
                    src = Files.readString(p, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    addRegexTrace(steps, traceId, "WARN", "[TS] FILE_PARSE_FAIL " + relStr + " msg=" + e.getMessage());
                    continue;
                }

                int classDecl = tsIndexOfClassDecl(src);
                String ctrlPrefix = tsFindControllerPrefix(src, classDecl);
                addRegexTrace(steps, traceId, "DEBUG",
                        "[TS] CONTROLLER_PREFIX_DETECT file=" + p.getFileName()
                                + " prefix=\"" + rxDbgPreview(ctrlPrefix, TS_DEBUG_PREVIEW_MAX) + "\"");

                Matcher dm = Pattern.compile("@(Get|Post|Put|Delete|Patch|Options|Head|All)\\s*").matcher(src);
                while (dm.find()) {
                    String deco = dm.group(1);
                    int at = dm.start();
                    int decoEnd = dm.end();
                    int paren = (decoEnd < src.length() && src.charAt(decoEnd) == '(') ? decoEnd : src.indexOf('(', decoEnd);
                    String inner = "";
                    int afterDeco = decoEnd;
                    boolean hasParen = false;
                    if (paren == decoEnd) {
                        hasParen = true;
                        int close = rxIndexOfClosingParenBalanced(src, paren);
                        if (close > paren) {
                            inner = src.substring(paren + 1, close);
                            afterDeco = close + 1;
                        }
                    } else if (paren > 0 && paren - decoEnd <= 1 && paren < src.length() && src.charAt(paren) == '(') {
                        hasParen = true;
                        int close = rxIndexOfClosingParenBalanced(src, paren);
                        if (close > paren) {
                            inner = src.substring(paren + 1, close);
                            afterDeco = close + 1;
                        }
                    }

                    List<String> paths = hasParen ? tsExtractPathsFromDecoratorArgs(inner) : List.of("");
                    if (paths.isEmpty()) {
                        mappingCount++;
                        addRegexTrace(steps, traceId, "WARN",
                                "[TS] ROUTE_DETECT SKIP_UNRESOLVED_PATH deco=" + deco
                                        + " args=\"" + rxDbgPreview(inner, TS_DEBUG_PREVIEW_MAX) + "\"");
                        continue;
                    }

                    String after = src.substring(afterDeco, Math.min(afterDeco + 2000, src.length()));
                    String methodName = tsFindMethodNameAfter(after);
                    if (methodName == null) {
                        mappingCount++;
                        addRegexTrace(steps, traceId, "WARN",
                                "[TS] ROUTE_DETECT SKIP_NO_METHOD_NAME deco=" + deco
                                        + " args=\"" + rxDbgPreview(inner, TS_DEBUG_PREVIEW_MAX) + "\""
                                        + " afterPreview=\"" + rxDbgPreview(after, TS_DEBUG_PREVIEW_MAX) + "\"");
                        continue;
                    }

                    boolean isDeprecated = src.substring(Math.max(0, at - 400), at).contains("@Deprecated");
                    String headArea = src.substring(Math.max(0, at - 800), at);
                    String op = "-";
                    Matcher opM = Pattern.compile("@ApiOperation\\s*\\((.*?)\\)", Pattern.DOTALL).matcher(headArea);
                    if (opM.find()) {
                        String lit = tsFirstQuotedLiteral(opM.group(1));
                        if (lit != null) op = lit;
                    }

                    for (String mp : paths) {
                        mappingCount++;
                        String finalPath = normalizePath((apiPathPrefix == null ? "" : apiPathPrefix) + "/" + ctrlPrefix + "/" + (mp == null ? "" : mp));
                        ApiInfo info = new ApiInfo();
                        info.setApiPath(finalPath);
                        info.setHttpMethod(deco.toUpperCase());
                        info.setMethodName(methodName);
                        info.setControllerName(p.getFileName().toString());
                        info.setRepoPath(relStr);
                        info.setIsDeprecated(isDeprecated ? "Y" : "N");
                        info.setHasUrlBlock("N");
                        info.setProgramId(autoExtractProgramId(finalPath));
                        info.setControllerComment("-");
                        info.setFullComment("-");
                        info.setDescriptionTag("-");
                        info.setApiOperationValue(op);
                        info.setRequestPropertyValue("-");
                        info.setControllerRequestPropertyValue("-");
                        info.setBlockMarkingIncomplete(false);
                        // git history는 디버그에서 생략
                        info.setGit1(new String[]{"", "", ""});
                        info.setGit2(new String[]{"", "", ""});
                        info.setGit3(new String[]{"", "", ""});
                        info.setGit4(new String[]{"", "", ""});
                        info.setGit5(new String[]{"", "", ""});
                        apis.add(info);
                        addRegexTrace(steps, traceId, "OK",
                                "[TS] ADD " + info.getHttpMethod() + " " + info.getApiPath()
                                        + " methodName=" + methodName
                                        + " file=" + relStr);
                    }
                }
            }
        }

        if (countsOut != null) {
            countsOut.put("fileCount", fileCount);
            countsOut.put("mappingCount", mappingCount);
        }
        addRegexTrace(steps, traceId, "INFO", "[TS] scan 종료 files=" + fileCount + " mappings=" + mappingCount + " apis=" + apis.size());
        return apis;
    }

    public Map<String, Object> debugParseTsRoutes(String rootPath, String apiPathPrefix, String include, String exclude) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("parseMethod", "ts-regex");
        out.put("mode", "ts");
        out.put("rootPath", rootPath);
        out.put("apiPathPrefix", apiPathPrefix == null ? "" : apiPathPrefix);
        out.put("include", include);
        out.put("exclude", exclude);
        List<Map<String, Object>> steps = new ArrayList<>();
        out.put("steps", steps);
        try {
            String traceId = rxNewTraceId();
            out.put("tsTraceId", traceId);
            addRegexTrace(steps, traceId, "INFO", "TS regex trace 시작");
            Path root = (rootPath == null || rootPath.isBlank()) ? null : Paths.get(rootPath.trim());
            Map<String, Object> counts = new LinkedHashMap<>();
            List<ApiInfo> apis = extractWithTsRegexDebug(root, apiPathPrefix == null ? "" : apiPathPrefix, include, exclude, steps, traceId, counts);
            out.putAll(counts);
            out.put("ok", true);
            out.put("apiCount", apis.size());
            out.put("apis", previewApis(apis));
            addRegexTrace(steps, traceId, "OK", "TS regex 완료: apis=" + apis.size());
            return out;
        } catch (Exception e) {
            out.put("errorType", e.getClass().getName());
            out.put("message", e.getMessage());
            out.put("stackTrace", stackTraceString(e));
            addDebugStep(steps, "ERROR", "중단: " + e.getMessage());
            return out;
        }
    }

    public Map<String, Object> savePartialFromTsRegexRepo(String repositoryName, String rootPath,
                                                          String apiPathPrefix, String include, String exclude,
                                                          String domain, String clientIp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("parseMethod", "ts-regex");
        try {
            if (repositoryName == null || repositoryName.isBlank()) throw new IllegalArgumentException("repositoryName required");
            if (rootPath == null || rootPath.isBlank()) throw new IllegalArgumentException("rootPath is required");
            Path root = Paths.get(rootPath.trim());
            String traceId = rxNewTraceId();
            List<Map<String, Object>> steps = new ArrayList<>();
            out.put("steps", steps);
            out.put("tsTraceId", traceId);
            Map<String, Object> counts = new LinkedHashMap<>();
            List<ApiInfo> apis = extractWithTsRegexDebug(root, apiPathPrefix == null ? "" : apiPathPrefix, include, exclude, steps, traceId, counts);
            String dom = domain != null ? domain : "";
            for (ApiInfo a : apis) {
                String p = a.getApiPath() != null ? a.getApiPath() : "";
                a.setFullUrl(dom + p);
            }

            String tsSaveGitBin = repoConfigRepository.findByRepoName(repositoryName.trim())
                    .map(com.baek.viewer.model.RepoConfig::getGitBinPath)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(defaultGitBinPath);
            applyGitHistoryPerRepoPath(apis, rootPath, tsSaveGitBin);

            int[] r = storageService.savePartial(repositoryName.trim(), apis, clientIp);
            out.putAll(counts);
            out.put("ok", true);
            out.put("savedTouches", r[0]);
            out.put("statusRevertedCount", r[1]);
            out.put("apiCount", apis.size());
            log.info("[TS 부분저장] repo={} rootPath={} apis={}", repositoryName, rootPath, apis.size());
            return out;
        } catch (Exception e) {
            log.warn("[TS 부분저장 실패] {}", e.getMessage());
            out.put("error", e.getMessage());
            out.put("errorType", e.getClass().getName());
            return out;
        }
    }

    private static String sanitizeDebugRelPath(String raw) {
        if (raw == null) return "";
        String v = raw;
        int idx = v.lastIndexOf("상대경로(relPath):");
        if (idx >= 0) v = v.substring(idx + "상대경로(relPath):".length());
        v = v.replace('\r', '\n');
        String[] lines = v.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i] == null ? "" : lines[i].trim();
            if (!t.isEmpty()) {
                v = t;
                break;
            }
        }
        v = v.trim();
        v = v.replaceAll("^[`'\"]+", "").replaceAll("[`'\"]+$", "").trim();
        return v;
    }

    private static boolean isWindowsAbsPath(String s) {
        if (s == null) return false;
        return s.matches("^[A-Za-z]:[\\\\/].+");
    }

    private static boolean isUnixAbsPath(String s) {
        if (s == null) return false;
        return s.startsWith("/");
    }

    /**
     * Regex 폴백 추출 (업로드 소스 문자열 기반).
     * - {@link #extractWithRegex(Path, String, List, String, Map)} 과 동일 코어({@link #extractApisWithRegexCore})를 사용한다.
     */
    private List<ApiInfo> extractWithRegexFromSource(String rawSource, Path pseudoPath, String relPath,
                                                     List<String[]> git,
                                                     String apiPathPrefix,
                                                     Map<String, String> pathConstantsMap) {
        String raw = rawSource == null ? "" : rawSource;
        String controllerFileName = pseudoPath != null && pseudoPath.getFileName() != null
                ? pseudoPath.getFileName().toString()
                : "Uploaded.java";
        return extractApisWithRegexCore(raw, relPath, controllerFileName, git, apiPathPrefix, pathConstantsMap, null, null, null);
    }

    /**
     * 디버그 용도: 특정 단일 파일을 파싱해 결과/예외를 반환한다.
     * - JavaParser 우선
     * - 실패 시 Regex 폴백
     * - DB 저장/깃 실행 없음
     */
    public Map<String, Object> debugParseSingleFile(String rootPath, String relPath, String apiPathPrefix, String pathConstantsRaw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("javaParserOk", false);
        out.put("regexFallbackUsed", false);
        out.put("rootPath", rootPath);
        out.put("relPath", relPath);
        out.put("apiPathPrefix", apiPathPrefix == null ? "" : apiPathPrefix);
        List<Map<String, Object>> steps = new ArrayList<>();
        out.put("steps", steps);
        try {
            addDebugStep(steps, "INFO", "입력값 확인");
            String relRaw = relPath;
            String relSan = sanitizeDebugRelPath(relRaw);
            out.put("relPathSanitized", relSan);
            if (relRaw != null && !relRaw.equals(relSan)) {
                addDebugStep(steps, "INFO", "relPath 정규화: '" + relRaw + "' → '" + relSan + "'");
            }

            if (relSan == null || relSan.isBlank()) throw new IllegalArgumentException("relPath is required");

            final Path file;
            final String effectiveRelPath;
            if (isWindowsAbsPath(relSan) || isUnixAbsPath(relSan)) {
                file = Paths.get(relSan);
                effectiveRelPath = file.getFileName() != null ? file.getFileName().toString() : relSan;
                addDebugStep(steps, "INFO", "절대경로 입력으로 처리: " + file.toAbsolutePath().normalize());
            } else {
                if (rootPath == null || rootPath.isBlank()) throw new IllegalArgumentException("rootPath is required");
                Path root = Paths.get(rootPath);
                file = root.resolve(relSan);
                effectiveRelPath = relSan;
                addDebugStep(steps, "INFO", "상대경로 입력으로 처리: " + file.toAbsolutePath().normalize());
            }
            if (!Files.exists(file)) throw new IllegalArgumentException("file not found: " + file);
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("not a file: " + file);

            Map<String, String> constants = parsePathConstants(pathConstantsRaw);
            // git history는 debug에서 생략
            List<String[]> git = List.of(new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""});

            addDebugStep(steps, "INFO", "JavaParser 파싱 시작");
            try {
                ensureJavaParserConfigured();
                String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                NormalizationResult nr = normalizeForJavaParser(raw);
                if (nr.changed()) {
                    addDebugStep(steps, "INFO", "in-memory normalization 적용: " + String.join(" | ", nr.notes()));
                    log.info(purpleBanner("[JP-NORM] (debug-parse-one) " + effectiveRelPath + " — " + String.join(" | ", nr.notes())));
                }
                List<ApiInfo> apis = extractWithJavaParserFromSource(nr.source(), file, effectiveRelPath, git,
                        apiPathPrefix == null ? "" : apiPathPrefix, constants);
                out.put("ok", true);
                out.put("javaParserOk", true);
                out.put("parseMethod", "javaparser");
                out.put("mode", "path");
                out.put("apiCount", apis.size());
                out.put("apis", previewApis(apis));
                out.put("absolutePath", file.toAbsolutePath().normalize().toString());
                addDebugStep(steps, "OK", "JavaParser 추출 완료: " + apis.size() + "건");
                return out;
            } catch (ParseProblemException ppe) {
                out.put("errorType", ParseProblemException.class.getName());
                out.put("message", ppe.getMessage());
                out.put("stackTrace", stackTraceString(ppe));
                out.put("problems", ppe.getProblems().stream().map(pr -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("message", pr.getMessage());
                    pr.getLocation().ifPresent(loc -> {
                        try {
                            m.put("begin", String.valueOf(loc.getBegin()));
                            m.put("end", String.valueOf(loc.getEnd()));
                        } catch (Exception ignored) {
                        }
                    });
                    return m;
                }).toList());
                addDebugStep(steps, "WARN", "JavaParser 실패 (ParseProblemException) → Regex 폴백 시도");
                // 콘솔/파일에 스택트레이스도 남겨 로컬에서 원인 파악을 쉽게 한다.
                log.warn("[DEBUG 단건파싱] {} — JavaParser ParseProblemException", effectiveRelPath, ppe);
            } catch (Exception jpEx) {
                out.put("errorType", jpEx.getClass().getName());
                out.put("message", jpEx.getMessage());
                out.put("stackTrace", stackTraceString(jpEx));
                addDebugStep(steps, "WARN", "JavaParser 실패 (" + jpEx.getClass().getSimpleName() + ") → Regex 폴백 시도");
                log.warn("[DEBUG 단건파싱] {} — JavaParser 실패 ({})", effectiveRelPath, jpEx.getClass().getSimpleName(), jpEx);
            }

            out.put("regexFallbackUsed", true);
            addDebugStep(steps, "INFO", "Regex 폴백 추출 시작");
            String traceId = rxNewTraceId();
            out.put("regexTraceId", traceId);
            addRegexTrace(steps, traceId, "INFO", "Regex trace 시작");
            String raw2 = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<ApiInfo> rxApis = extractApisWithRegexCore(raw2, effectiveRelPath, file.getFileName().toString(), git,
                    apiPathPrefix == null ? "" : apiPathPrefix, constants, file, steps, traceId);
            out.put("ok", true);
            out.put("parseMethod", "regex");
            out.put("mode", "path");
            out.put("apiCount", rxApis.size());
            out.put("apis", previewApis(rxApis));
            out.put("absolutePath", file.toAbsolutePath().normalize().toString());
            addDebugStep(steps, "OK", "Regex 폴백 추출 완료: " + rxApis.size() + "건");
            return out;
        } catch (Exception e) {
            out.put("errorType", e.getClass().getName());
            out.put("message", e.getMessage());
            out.put("stackTrace", stackTraceString(e));
            addDebugStep(steps, "ERROR", "중단: " + e.getMessage());
            return out;
        }
    }

    private static final int DEBUG_PARSE_SOURCE_MAX_CHARS = 3_000_000;

    /**
     * 브라우저에서 업로드한 .java 소스 문자열을 메모리에서만 파싱한다 (서버 디스크의 레포 경로 불필요).
     */
    public Map<String, Object> debugParseJavaSource(String javaSource, String fileLabel, String apiPathPrefix, String pathConstantsRaw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("javaParserOk", false);
        out.put("regexFallbackUsed", false);
        out.put("mode", "upload");
        out.put("rootPath", "(업로드)");
        String rel = (fileLabel == null || fileLabel.isBlank())
                ? "__upload__/Snippet.java"
                : fileLabel.trim().replace('\\', '/');
        out.put("relPath", rel);
        out.put("apiPathPrefix", apiPathPrefix == null ? "" : apiPathPrefix);
        List<Map<String, Object>> steps = new ArrayList<>();
        out.put("steps", steps);
        try {
            addDebugStep(steps, "INFO", "입력값 확인");
            if (javaSource == null) throw new IllegalArgumentException("source is required");
            if (javaSource.length() > DEBUG_PARSE_SOURCE_MAX_CHARS) {
                throw new IllegalArgumentException("소스가 너무 큽니다 (문자 수 최대 " + DEBUG_PARSE_SOURCE_MAX_CHARS + ").");
            }
            if (rel.contains("..")) throw new IllegalArgumentException("unsafe file name");

            Map<String, String> constants = parsePathConstants(pathConstantsRaw);
            List<String[]> git = List.of(new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""});
            Path pseudo = Paths.get(rel);

            addDebugStep(steps, "INFO", "JavaParser 파싱 시작");
            try {
                ensureJavaParserConfigured();
                NormalizationResult nr = normalizeForJavaParser(javaSource);
                if (nr.changed()) {
                    addDebugStep(steps, "INFO", "in-memory normalization 적용: " + String.join(" | ", nr.notes()));
                    log.info(purpleBanner("[JP-NORM] (debug-parse-upload) " + rel + " — " + String.join(" | ", nr.notes())));
                }
                List<ApiInfo> apis = extractWithJavaParserFromSource(nr.source(), pseudo, rel, git,
                        apiPathPrefix == null ? "" : apiPathPrefix, constants);
                out.put("ok", true);
                out.put("javaParserOk", true);
                out.put("parseMethod", "javaparser");
                out.put("apiCount", apis.size());
                out.put("apis", previewApis(apis));
                out.put("absolutePath", "(업로드 — 서버 로컬 파일 아님)");
                addDebugStep(steps, "OK", "JavaParser 추출 완료: " + apis.size() + "건");
                return out;
            } catch (ParseProblemException ppe) {
                out.put("errorType", ParseProblemException.class.getName());
                out.put("message", ppe.getMessage());
                out.put("stackTrace", stackTraceString(ppe));
                out.put("problems", ppe.getProblems().stream().map(pr -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("message", pr.getMessage());
                    pr.getLocation().ifPresent(loc -> {
                        try {
                            m.put("begin", String.valueOf(loc.getBegin()));
                            m.put("end", String.valueOf(loc.getEnd()));
                        } catch (Exception ignored) {
                        }
                    });
                    return m;
                }).toList());
                addDebugStep(steps, "WARN", "JavaParser 실패 (ParseProblemException) → Regex 폴백 시도");
                log.warn("[DEBUG 업로드파싱] {} — JavaParser ParseProblemException", rel, ppe);
            } catch (Exception jpEx) {
                out.put("errorType", jpEx.getClass().getName());
                out.put("message", jpEx.getMessage());
                out.put("stackTrace", stackTraceString(jpEx));
                addDebugStep(steps, "WARN", "JavaParser 실패 (" + jpEx.getClass().getSimpleName() + ") → Regex 폴백 시도");
                log.warn("[DEBUG 업로드파싱] {} — JavaParser 실패 ({})", rel, jpEx.getClass().getSimpleName(), jpEx);
            }

            out.put("regexFallbackUsed", true);
            addDebugStep(steps, "INFO", "Regex 폴백 추출 시작");
            String traceId = rxNewTraceId();
            out.put("regexTraceId", traceId);
            addRegexTrace(steps, traceId, "INFO", "Regex trace 시작");
            List<ApiInfo> rxApis = extractApisWithRegexCore(javaSource, rel,
                    pseudo != null && pseudo.getFileName() != null ? pseudo.getFileName().toString() : rel,
                    git, apiPathPrefix == null ? "" : apiPathPrefix, constants, null, steps, traceId);
            out.put("ok", true);
            out.put("parseMethod", "regex");
            out.put("apiCount", rxApis.size());
            out.put("apis", previewApis(rxApis));
            out.put("absolutePath", "(업로드 — 서버 로컬 파일 아님)");
            addDebugStep(steps, "OK", "Regex 폴백 추출 완료: " + rxApis.size() + "건");
            return out;
        } catch (Exception e) {
            out.put("errorType", e.getClass().getName());
            out.put("message", e.getMessage());
            out.put("stackTrace", stackTraceString(e));
            addDebugStep(steps, "ERROR", "중단: " + e.getMessage());
            return out;
        }
    }

    private static NormalizationResult normalizeForJavaParser(String source) {
        if (source == null || source.isEmpty()) return new NormalizationResult("", false, List.of());
        // 일부 레거시 소스는 파일 인코딩/복붙 문제로 C1 control(0x80~0x9F) 같은 비표준 문자가 섞여
        // JavaParser가 Lexical error(<92> 등)로 실패할 수 있다. 원본 파일은 건드리지 않고 입력만 최소 정리한다.
        String cleaned = sanitizeParserInputControls(source);
        // allowlist 어노테이션 인자 영역에서만 "'...'" → "\"...\"" 로 보정한다.
        // - Java 문법상 단일따옴표는 char 리터럴이므로 "/v1/common/" 같은 다문자 리터럴은 파싱 자체가 깨진다.
        // - 원본 파일은 수정하지 않고, 파서 입력 문자열만 보정한다.
        StringBuilder out = new StringBuilder(cleaned.length());
        List<String> notes = new ArrayList<>();
        Set<String> touchedAnns = new LinkedHashSet<>();

        boolean changed = !cleaned.equals(source);
        if (changed) {
            notes.add("sanitize control chars (C0/C1) for JavaParser");
        }

        int n = cleaned.length();
        int i = 0;

        while (i < n) {
            char c = cleaned.charAt(i);
            if (c != '@') {
                out.append(c);
                i++;
                continue;
            }

            int nameStart = i + 1;
            int j = nameStart;
            while (j < n) {
                char cj = cleaned.charAt(j);
                if (Character.isJavaIdentifierPart(cj)) j++;
                else break;
            }
            if (j == nameStart) {
                out.append(c);
                i++;
                continue;
            }

            String ann = cleaned.substring(nameStart, j);
            if (!NORMALIZE_ANN_NAMES.contains(ann)) {
                out.append(cleaned, i, j);
                i = j;
                continue;
            }

            // 복사: @AnnotationName
            out.append(cleaned, i, j);
            i = j;

            // 공백 스킵
            while (i < n && Character.isWhitespace(cleaned.charAt(i))) {
                out.append(cleaned.charAt(i));
                i++;
            }
            if (i >= n || cleaned.charAt(i) != '(') continue;

            // 애노테이션 인자 영역: 괄호 균형으로 끝까지 스캔하며 단일따옴표 리터럴을 제한적으로 변환
            int parenDepth = 0;
            boolean inDq = false;
            boolean inTextBlock = false;
            boolean inSq = false;
            boolean escaping = false;
            int sqStart = -1;
            StringBuilder sqBuf = null;
            boolean annChanged = false;

            while (i < n) {
                char ch = cleaned.charAt(i);

                if (inSq) {
                    if (escaping) {
                        // \' 같은 이스케이프는 그대로 버퍼에 포함
                        sqBuf.append(ch);
                        escaping = false;
                        i++;
                        continue;
                    }
                    if (ch == '\\') {
                        sqBuf.append(ch);
                        escaping = true;
                        i++;
                        continue;
                    }
                    if (ch == '\'') {
                        // 단일따옴표 닫힘
                        String inner = sqBuf.toString();
                        // 안전 규칙: 1글자 char은 유지, 그 외/문자열스러운 기호 포함이면 문자열로 보정
                        boolean looksLikeStringLiteral = inner.length() != 1
                                || inner.contains("/")
                                || inner.contains(".")
                                || inner.contains("-")
                                || inner.contains("_")
                                || inner.contains(" ");
                        if (looksLikeStringLiteral) {
                            out.append('\"').append(inner.replace("\"", "\\\"")).append('\"');
                            changed = true;
                            annChanged = true;
                        } else {
                            // 1글자 char 리터럴은 유지
                            out.append('\'').append(inner).append('\'');
                        }
                        inSq = false;
                        sqStart = -1;
                        sqBuf = null;
                        i++; // 닫는 ' 소비
                        continue;
                    }
                    sqBuf.append(ch);
                    i++;
                    continue;
                }

                if (inDq) {
                    out.append(ch);
                    if (escaping) {
                        escaping = false;
                    } else if (ch == '\\') {
                        escaping = true;
                    } else if (ch == '"') {
                        inDq = false;
                    }
                    i++;
                    continue;
                }

                if (inTextBlock) {
                    out.append(ch);
                    // text block end: """ (not escaped in Java text blocks)
                    if (ch == '"' && i + 2 < n && cleaned.charAt(i + 1) == '"' && cleaned.charAt(i + 2) == '"') {
                        out.append('"').append('"');
                        i += 3;
                        inTextBlock = false;
                        continue;
                    }
                    i++;
                    continue;
                }

                // 문자열/문자 리터럴 밖
                if (ch == '"') {
                    // Java 15+ text block: """ ... """
                    if (i + 2 < n && cleaned.charAt(i + 1) == '"' && cleaned.charAt(i + 2) == '"') {
                        out.append('"').append('"').append('"');
                        i += 3;
                        inTextBlock = true;
                        continue;
                    } else {
                        inDq = true;
                        out.append(ch);
                        i++;
                        continue;
                    }
                }
                if (ch == '\'') {
                    inSq = true;
                    sqStart = i;
                    sqBuf = new StringBuilder();
                    i++; // 시작 '는 출력하지 않고 버퍼로 축적
                    continue;
                }

                out.append(ch);

                if (ch == '(') parenDepth++;
                else if (ch == ')') {
                    parenDepth--;
                    if (parenDepth <= 0) {
                        i++;
                        break;
                    }
                }
                i++;
            }

            if (annChanged) {
                touchedAnns.add(ann);
            }
        }

        if (changed) {
            if (!touchedAnns.isEmpty()) {
                notes.add("single-quote literal → double-quote in @" + String.join(", @", touchedAnns));
            } else {
                notes.add("single-quote literal → double-quote");
            }
        }
        return new NormalizationResult(out.toString(), changed, List.copyOf(notes));
    }

    private static String sanitizeParserInputControls(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            char repl = 0;

            // allow common whitespace
            if (ch == '\n' || ch == '\r' || ch == '\t') continue;

            // C0 control
            if (ch < 0x20) repl = ' ';
            // C1 control (often appears as 0x92 etc from cp1252/encoding issues)
            else if (ch >= 0x80 && ch <= 0x9F) {
                repl = switch (ch) {
                    case 0x91, 0x92 -> '\''; // ‘ ’
                    case 0x93, 0x94 -> '"';  // “ ”
                    case 0x96, 0x97 -> '-';  // – —
                    default -> ' ';
                };
            }

            if (repl != 0) {
                if (b == null) {
                    b = new StringBuilder(s.length());
                    b.append(s, 0, i);
                }
                b.append(repl);
            } else if (b != null) {
                b.append(ch);
            }
        }
        return b == null ? s : b.toString();
    }

    /**
     * 단건 파싱 결과를 레포 DB에 부분 반영(다른 URL 삭제 표시 없음). 서버에서 파일을 다시 파싱한다.
     */
    public Map<String, Object> savePartialFromRepoPath(String repositoryName, String rootPath, String relPath,
                                                       String apiPathPrefix, String pathConstantsRaw,
                                                       String domain, String clientIp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        try {
            if (repositoryName == null || repositoryName.isBlank()) {
                throw new IllegalArgumentException("repositoryName required");
            }
            ensureJavaParserConfigured();
            if (rootPath == null || rootPath.isBlank()) throw new IllegalArgumentException("rootPath is required");
            if (relPath == null || relPath.isBlank()) throw new IllegalArgumentException("relPath is required");
            Path root = Paths.get(rootPath);
            Path file = root.resolve(relPath);
            if (!Files.exists(file)) throw new IllegalArgumentException("file not found: " + file);
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("not a file: " + file);
            Map<String, String> constants = parsePathConstants(pathConstantsRaw);
            List<String[]> git = List.of(new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""});
            List<ApiInfo> apis = extractWithJavaParser(file, relPath, git, apiPathPrefix == null ? "" : apiPathPrefix, constants);
            String dom = domain != null ? domain : "";
            for (ApiInfo a : apis) {
                String p = a.getApiPath() != null ? a.getApiPath() : "";
                a.setFullUrl(dom + p);
            }
            int[] r = storageService.savePartial(repositoryName, apis, clientIp);
            out.put("ok", true);
            out.put("savedTouches", r[0]);
            out.put("statusRevertedCount", r[1]);
            out.put("apiCount", apis.size());
            log.info("[단건 부분저장] repo={} relPath={} apis={}", repositoryName, relPath, apis.size());
            return out;
        } catch (Exception e) {
            log.warn("[단건 부분저장 실패] {}", e.getMessage());
            out.put("error", e.getMessage());
            out.put("errorType", e.getClass().getName());
            return out;
        }
    }

    /** 업로드 소스를 다시 파싱한 뒤 부분 저장. */
    public Map<String, Object> savePartialFromUploadSource(String repositoryName, String javaSource, String fileLabel,
                                                           String apiPathPrefix, String pathConstantsRaw,
                                                           String domain, String clientIp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        try {
            if (repositoryName == null || repositoryName.isBlank()) {
                throw new IllegalArgumentException("repositoryName required");
            }
            ensureJavaParserConfigured();
            if (javaSource == null || javaSource.isBlank()) throw new IllegalArgumentException("source is required");
            if (javaSource.length() > DEBUG_PARSE_SOURCE_MAX_CHARS) {
                throw new IllegalArgumentException("소스가 너무 큽니다 (문자 수 최대 " + DEBUG_PARSE_SOURCE_MAX_CHARS + ").");
            }
            String rel = (fileLabel == null || fileLabel.isBlank())
                    ? "__upload__/Snippet.java"
                    : fileLabel.trim().replace('\\', '/');
            if (rel.contains("..")) throw new IllegalArgumentException("unsafe file name");
            Map<String, String> constants = parsePathConstants(pathConstantsRaw);
            List<String[]> git = List.of(new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""}, new String[]{"", "", ""});
            Path pseudo = Paths.get(rel);
            List<ApiInfo> apis = extractWithJavaParserFromSource(javaSource, pseudo, rel, git,
                    apiPathPrefix == null ? "" : apiPathPrefix, constants);
            String dom = domain != null ? domain : "";
            for (ApiInfo a : apis) {
                String p = a.getApiPath() != null ? a.getApiPath() : "";
                a.setFullUrl(dom + p);
            }
            int[] r = storageService.savePartial(repositoryName, apis, clientIp);
            out.put("ok", true);
            out.put("savedTouches", r[0]);
            out.put("statusRevertedCount", r[1]);
            out.put("apiCount", apis.size());
            log.info("[단건 부분저장 업로드] repo={} label={} apis={}", repositoryName, rel, apis.size());
            return out;
        } catch (Exception e) {
            log.warn("[단건 부분저장 업로드 실패] {}", e.getMessage());
            out.put("error", e.getMessage());
            out.put("errorType", e.getClass().getName());
            return out;
        }
    }

    private static String stackTraceString(Throwable e) {
        if (e == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}