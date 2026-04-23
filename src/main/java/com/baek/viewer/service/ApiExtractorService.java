package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ExtractRequest;
import com.github.javaparser.StaticJavaParser;
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

    private static final List<String> MAPPING_ANNS = Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    @Value("${api.viewer.git-bin-path:git}")
    private String defaultGitBinPath;

    private final ApiStorageService storageService;
    private final ApmCollectionService apmCollectionService;
    private final com.baek.viewer.repository.RepoConfigRepository repoConfigRepository;
    private final com.baek.viewer.repository.GlobalConfigRepository globalConfigRepository;

    public ApiExtractorService(ApiStorageService storageService, ApmCollectionService apmCollectionService,
                               com.baek.viewer.repository.RepoConfigRepository repoConfigRepository,
                               com.baek.viewer.repository.GlobalConfigRepository globalConfigRepository) {
        this.storageService = storageService;
        this.apmCollectionService = apmCollectionService;
        this.repoConfigRepository = repoConfigRepository;
        this.globalConfigRepository = globalConfigRepository;
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
        p.put("logs", new ArrayList<>(extractLogs));
        return p;
    }

    public void startExtractAsync(ExtractRequest req) {
        savedCount = -1;
        statusRevertedCount = 0;
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
        debugMode = globalConfigRepository.findById(1L)
                .map(com.baek.viewer.model.GlobalConfig::isApmDebug).orElse(false);
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
                    .filter(p -> p.toString().endsWith(".java") &&
                            (p.toString().contains("Controller") || p.toString().contains("Conrtoller")))
                    .collect(Collectors.toList());

            totalFiles = controllerFiles.size();
            processedFiles = 0;
            currentFile = "";  // 파일 개별 처리로 진입 — 개별 파일명이 채워짐
            addLog("INFO", "Controller 파일 " + totalFiles + "개 발견");

            controllerFiles.parallelStream().forEach(file -> {
                String rel = root.relativize(file).toString();
                String fileName = file.getFileName().toString();
                currentFile = fileName;
                try {
                    List<String[]> git = getRecentGitHistories(rel, rootPath, gitBin, 5);
                    List<ApiInfo> fileApis = extractApisHybrid(file, rel, git, apiPathPrefix, pathConstantsMap);
                    apis.addAll(fileApis);
                    addLog("OK", fileName + " — " + fileApis.size() + "개 API 추출");
                } catch (Exception e) {
                    addLog("ERROR", fileName + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                processedFiles++;
            });

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
        addLog("INFO", "추출 완료 — 총 " + sorted.size() + "개 API, 파일 " + totalFiles + "개 처리");

        // DB 저장 (레포지토리명이 있을 때만)
        String repoName = req.getRepositoryName();
        if (repoName != null && !repoName.isBlank()) {
            try {
                addLog("INFO", "DB 저장 중 — 레포: " + repoName.trim());
                int[] saveResult = storageService.save(repoName.trim(), cachedApis, req.getClientIp());
                savedCount = saveResult[0];
                statusRevertedCount = saveResult[1];
                String saveMsg = "DB 저장 완료 — " + savedCount + "개 저장/갱신";
                if (statusRevertedCount > 0) saveMsg += ", 차단대상→사용 전환 " + statusRevertedCount + "건 (현업검토결과=차단대상 제외)";
                addLog("OK", saveMsg);
            } catch (Exception e) {
                savedCount = -1;
                addLog("ERROR", "DB 저장 실패: " + e.getMessage());
            }

            // APM 호출건수 자동 집계 (데이터가 있을 때만)
            try {
                var result = apmCollectionService.aggregateToRecords(repoName.trim());
                int aggUpdated = ((Number) result.get("updated")).intValue();
                if (aggUpdated > 0) {
                    addLog("OK", "호출건수 자동 집계 — " + aggUpdated + "개 API 반영");
                } else {
                    addLog("INFO", "호출건수 집계 — APM 데이터 없음 (건너뜀)");
                }
            } catch (Exception e) {
                addLog("WARN", "호출건수 집계 실패 (분석 결과에 영향 없음): " + e.getMessage());
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
            addLog("WARN", path.getFileName() + " — JavaParser 실패 (" + e.getClass().getSimpleName() + "), Regex 폴백 적용");
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
        boolean debug = debugMode;
        List<ApiInfo> apis = new ArrayList<>();
        String source = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        CompilationUnit cu = StaticJavaParser.parse(source);

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
                            finalPath, httpMethod, controllerComment, controllerRequestProperty);
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

    private List<ApiInfo> extractWithRegex(Path filePath, String relPath,
                                            List<String[]> git,
                                            String apiPathPrefix,
                                            Map<String, String> pathConstantsMap) {
        boolean debug = debugMode;
        List<ApiInfo> apis = new ArrayList<>();
        try {
            String raw = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            String clean = raw.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//.*", " ");

            String controllerComment = "-";
            Matcher cM = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL).matcher(raw);
            if (cM.find()) controllerComment = cM.group(1).replaceAll("[\\r\\n*]", " ").trim();

            String classPath = "";
            String classHead = clean.substring(0, Math.min(clean.length(), 3000));
            Matcher cm = Pattern.compile("@RequestMapping\\s*\\((.*?)\\)", Pattern.DOTALL).matcher(classHead);
            if (cm.find()) {
                String cParams = substituteConstants(cm.group(1), pathConstantsMap)
                        .replaceAll("\"\\s*\\+\\s*\"", "");
                Matcher cp = Pattern.compile("\"([^\"]+)\"").matcher(cParams);
                if (cp.find()) classPath = cp.group(1).trim();
            }
            if (debug) {
                log.debug("[파싱-RX] 파일={}, @RequestMapping={}", filePath.getFileName(),
                        classPath.isEmpty() ? "(없음)" : classPath);
            }

            Matcher mMatcher = Pattern.compile(
                    "@(GetMapping|PostMapping|RequestMapping|PutMapping|DeleteMapping|PatchMapping)\\s*\\((.*?)\\)",
                    Pattern.DOTALL).matcher(raw);

            while (mMatcher.find()) {
                String mappingType = mMatcher.group(1);
                String params = substituteConstants(mMatcher.group(2), pathConstantsMap)
                        .replaceAll("\"\\s*\\+\\s*\"", "");
                String httpMethod = resolveHttpMethodFromName(mappingType, params);

                String afterMapping = clean.substring(mMatcher.end(), Math.min(mMatcher.end() + 1000, clean.length()));
                Matcher mName = Pattern.compile("(?:public|private|protected)\\s+[\\w<>,\\s]+\\s+(\\w+)\\s*\\(")
                        .matcher(afterMapping);
                if (!mName.find()) continue;

                String methodName = mName.group(1);
                boolean isDeprecated = clean.substring(Math.max(0, mMatcher.start() - 300), mMatcher.start())
                        .contains("@Deprecated");

                Matcher p = Pattern.compile("\"([^\"]+)\"").matcher(params);
                boolean found = false;
                while (p.find()) {
                    String s = p.group(1).trim();
                    if (s.contains("RequestMethod")) continue;
                    found = true;

                    String finalPath = normalizePath(apiPathPrefix + classPath + "/" + s);
                    String headArea = raw.substring(Math.max(0, mMatcher.start() - 1000), mMatcher.start());

                    ApiInfo info = new ApiInfo();
                    info.setApiPath(finalPath);
                    info.setHttpMethod(httpMethod);
                    info.setMethodName(methodName);
                    info.setControllerName(filePath.getFileName().toString());
                    info.setRepoPath(relPath.replace("\\", "/"));
                    info.setIsDeprecated(isDeprecated ? "Y" : "N");
                    String mBody = afterMapping.substring(mName.end(), Math.min(mName.end() + 500, afterMapping.length()));
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
                    // @Deprecated 라인에서 [URL…차단…] 태그 정보 보충
                    if (isDeprecated && (info.getFullComment().equals("-") || !ApiStorageService.containsUrlBlockTag(info.getFullComment()))) {
                        String depLine = extractDeprecatedLine(headArea);
                        if (depLine != null && ApiStorageService.containsUrlBlockTag(depLine)) {
                            info.setFullComment(depLine);
                        }
                    }

                    // @ApiOperation 우선, 없으면 @Operation(summary) 폴백
                    Matcher aM = Pattern.compile("@ApiOperation\\s*\\(.*?value\\s*=\\s*\"([^\"]+)\".*?\\)",
                            Pattern.DOTALL).matcher(headArea);
                    if (aM.find()) {
                        info.setApiOperationValue(aM.group(1));
                    } else {
                        Matcher opM = Pattern.compile("@Operation\\s*\\(.*?summary\\s*=\\s*\"([^\"]+)\".*?\\)",
                                Pattern.DOTALL).matcher(headArea);
                        info.setApiOperationValue(opM.find() ? opM.group(1) : "-");
                    }
                    info.setRequestPropertyValue("-");
                    info.setControllerRequestPropertyValue("-");
                    info.setBlockMarkingIncomplete(computeBlockMarkingIncomplete(
                            isFirstStmtUrlBlockRegex(mBody),
                            info.getIsDeprecated(),
                            info.getFullComment()));
                    apis.add(info);
                    if (debug) {
                        log.debug("[파싱-RX]   {} {} | method={} | deprecated={} | urlBlock={} | markingIncomplete={}",
                                httpMethod, finalPath, methodName, info.getIsDeprecated(), info.getHasUrlBlock(),
                                info.isBlockMarkingIncomplete());
                    }
                }

                if (!found) {
                    // 매핑 어노테이션은 있으나 경로 문자열이 없는 경우 (빈 매핑)
                    String finalPath = normalizePath(apiPathPrefix + classPath);
                    ApiInfo info = new ApiInfo();
                    info.setApiPath(finalPath.isEmpty() ? "/" : finalPath);
                    info.setHttpMethod(httpMethod);
                    info.setMethodName(methodName);
                    info.setControllerName(filePath.getFileName().toString());
                    info.setRepoPath(relPath.replace("\\", "/"));
                    info.setIsDeprecated(isDeprecated ? "Y" : "N");
                    String mBody2 = afterMapping.substring(mName.end(), Math.min(mName.end() + 500, afterMapping.length()));
                    info.setHasUrlBlock(detectUrlBlockRegex(mBody2) ? "Y" : "N");
                    info.setProgramId(autoExtractProgramId(finalPath));
                    info.setControllerComment(controllerComment);
                    info.setGit1(git.get(0)); info.setGit2(git.get(1)); info.setGit3(git.get(2));
                    info.setGit4(git.get(3)); info.setGit5(git.get(4));
                    info.setFullComment("-"); info.setDescriptionTag("-");
                    info.setApiOperationValue("-");
                    info.setRequestPropertyValue("-");
                    info.setControllerRequestPropertyValue("-");
                    info.setBlockMarkingIncomplete(computeBlockMarkingIncomplete(
                            isFirstStmtUrlBlockRegex(mBody2),
                            info.getIsDeprecated(),
                            info.getFullComment()));
                    apis.add(info);
                    if (debug) {
                        log.debug("[파싱-RX]   {} {} (빈매핑) | method={} | deprecated={} | markingIncomplete={}",
                                httpMethod, info.getApiPath(), methodName, info.getIsDeprecated(),
                                info.isBlockMarkingIncomplete());
                    }
                }
            }
        } catch (Exception ignored) {}
        if (debug) {
            log.debug("[파싱-RX] {} 완료 — {}개 API 추출", filePath.getFileName(), apis.size());
        }
        return apis;
    }

    // ======================================================
    // ApiInfo 빌더 (JavaParser용)
    // ======================================================

    private ApiInfo buildApiInfo(Path filePath, String relPath, MethodDeclaration method,
                                  List<String[]> git, String finalPath, String httpMethod,
                                  String controllerComment, String controllerRequestProperty) {
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
                String src = Files.readString(filePath, StandardCharsets.UTF_8);
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

        return info;
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
                    .map(p -> p.getValue().toString().replaceAll("\"", ""))
                    .findFirst().orElse("-");
        }
        if (ann.get() instanceof SingleMemberAnnotationExpr se && "value".equals(attrName)) {
            return se.getMemberValue().toString().replaceAll("\"", "");
        }
        return "-";
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
}