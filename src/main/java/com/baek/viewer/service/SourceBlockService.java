package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SourceBlockService {

    public static final int MAX_IDS = 300;
    public static final String BLOCK_THROW_LINE = "if (true) throw new UnsupportedOperationException(\"차단된 URL 입니다.\");";

    /**
     * LexicalPreservingPrinter 가 삽입한 if-throw 를 두 줄로 풀어 쓰는 경우가 있어,
     * 차단 가드 한 줄 형태로만 정규화한다 (동일 메시지·UOE 한정).
     */
    private static final Pattern SPLIT_BLOCK_GUARD = Pattern.compile(
            "([\\t ]*)if\\s*\\(\\s*true\\s*\\)\\s*\\R([\\t ]+)throw\\s+new\\s+UnsupportedOperationException\\(\\s*\"차단된 URL 입니다\\.\"\\s*\\)\\s*;",
            Pattern.MULTILINE);

    private static String compactBlockGuardLines(String javaSource) {
        return SPLIT_BLOCK_GUARD.matcher(javaSource).replaceAll(mr -> mr.group(1) + BLOCK_THROW_LINE);
    }

    private final ApiRecordRepository recordRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final ApiExtractorService extractorService;

    public SourceBlockService(ApiRecordRepository recordRepository,
                              RepoConfigRepository repoConfigRepository,
                              ApiExtractorService extractorService) {
        this.recordRepository = recordRepository;
        this.repoConfigRepository = repoConfigRepository;
        this.extractorService = extractorService;
    }

    public void validateSourceBlockDownload(String repoName,
                                            List<Long> ids,
                                            LocalDate modalBlockedDate,
                                            String modalTicketNo,
                                            String modalTicketTitle) {
        if (repoName == null || repoName.isBlank()) throw new IllegalArgumentException("repoName_required");
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("ids_required");
        if (ids.size() > MAX_IDS) throw new IllegalArgumentException("too_many_ids");

        List<ApiRecord> records = recordRepository.findAllById(ids);
        Map<Long, ApiRecord> byId = new HashMap<>();
        for (ApiRecord r : records) byId.put(r.getId(), r);

        boolean anyRowMissingDeploy = false;
        int validForRepo = 0;
        for (Long id : ids) {
            ApiRecord r = byId.get(id);
            if (r == null) continue;
            if (!repoName.equals(r.getRepositoryName())) continue;
            String rel = (r.getRepoPath() == null) ? null : r.getRepoPath().trim().replace('\\', '/');
            if (rel == null || rel.isBlank() || rel.contains("..") || rel.startsWith("/")) continue;
            validForRepo++;
            if (r.getDeployScheduledDate() == null || isBlank(r.getDeployCsr())) {
                anyRowMissingDeploy = true;
            }
        }
        if (validForRepo == 0) throw new IllegalArgumentException("no_valid_records_for_repo");

        if (anyRowMissingDeploy) {
            if (modalBlockedDate == null) throw new IllegalArgumentException("blockedDate_required");
            if (isBlank(modalTicketNo)) throw new IllegalArgumentException("blockedTicketNo_required");
            if (isBlank(modalTicketTitle)) throw new IllegalArgumentException("blockedTicketTitle_required");
        }

        for (Long id : ids) {
            ApiRecord r = byId.get(id);
            if (r == null) continue;
            if (!repoName.equals(r.getRepositoryName())) continue;
            String rel = (r.getRepoPath() == null) ? null : r.getRepoPath().trim().replace('\\', '/');
            if (rel == null || rel.isBlank() || rel.contains("..") || rel.startsWith("/")) continue;
            LocalDate effDate = effectiveBlockedDate(r, modalBlockedDate);
            String effNo = effectiveTicketNo(r, modalTicketNo);
            if (effDate == null || isBlank(effNo)) {
                throw new IllegalArgumentException("insufficient_block_metadata");
            }
        }
    }

    public void writeBlockedSourcesZip(String repoName,
                                       List<Long> ids,
                                       LocalDate modalBlockedDate,
                                       String modalTicketNo,
                                       String modalTicketTitle,
                                       ZipOutputStream zos) throws Exception {
        if (repoName == null || repoName.isBlank()) throw new IllegalArgumentException("repoName is required");
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("ids is required");
        if (ids.size() > MAX_IDS) throw new IllegalArgumentException("한 번에 처리 가능한 ID는 최대 " + MAX_IDS + "건입니다.");

        RepoConfig rc = repoConfigRepository.findByRepoName(repoName)
                .orElseThrow(() -> new IllegalArgumentException("레포 설정을 찾을 수 없습니다: " + repoName));
        String rootPath = rc.getRootPath();
        if (rootPath == null || rootPath.isBlank()) throw new IllegalArgumentException("레포 rootPath 가 비어 있습니다: " + repoName);
        Path root = Paths.get(rootPath.trim());
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("레포 rootPath 가 올바르지 않습니다: " + rootPath);
        }

        List<ApiRecord> records = recordRepository.findAllById(ids);
        Map<Long, ApiRecord> byId = new HashMap<>();
        for (ApiRecord r : records) byId.put(r.getId(), r);

        // 입력 id 중 누락된 케이스도 manifest 로 남긴다.
        List<Map<String, Object>> manifestItems = new ArrayList<>();

        // filePath(rel repoPath) -> records
        Map<String, List<ApiRecord>> byRepoPath = new LinkedHashMap<>();
        for (Long id : ids) {
            ApiRecord r = byId.get(id);
            if (r == null) {
                manifestItems.add(item("missing", id, null, "not_found", "DB에서 레코드를 찾을 수 없습니다."));
                continue;
            }
            if (!repoName.equals(r.getRepositoryName())) {
                manifestItems.add(item("skipped", id, safeKey(r), "repo_mismatch", "선택한 레포와 레코드 레포가 다릅니다."));
                continue;
            }
            String rel = (r.getRepoPath() == null) ? null : r.getRepoPath().trim().replace('\\', '/');
            if (rel == null || rel.isBlank() || rel.contains("..") || rel.startsWith("/")) {
                manifestItems.add(item("failed", id, safeKey(r), "invalid_repo_path", "repoPath가 올바르지 않습니다."));
                continue;
            }
            byRepoPath.computeIfAbsent(rel, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<ApiRecord>> e : byRepoPath.entrySet()) {
            String relPath = e.getKey();
            List<ApiRecord> fileRecords = e.getValue();
            Path file = root.resolve(relPath).normalize();
            if (!file.startsWith(root)) {
                for (ApiRecord r : fileRecords) {
                    manifestItems.add(item("failed", r.getId(), safeKey(r), "unsafe_path", "파일 경로가 안전하지 않습니다."));
                }
                continue;
            }
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                for (ApiRecord r : fileRecords) {
                    manifestItems.add(item("failed", r.getId(), safeKey(r), "file_not_found", "소스 파일을 찾을 수 없습니다: " + relPath));
                }
                continue;
            }

            String src = Files.readString(file, StandardCharsets.UTF_8);
            String patched;
            try {
                patched = patchSingleJavaFile(src, relPath, fileRecords, modalBlockedDate, modalTicketNo, modalTicketTitle, manifestItems);
            } catch (Exception ex) {
                for (ApiRecord r : fileRecords) {
                    manifestItems.add(item("failed", r.getId(), safeKey(r), "patch_failed", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
                }
                continue;
            }

            // zip entry: {repoName}/{repoPath}
            String entryName = (repoName + "/" + relPath).replace("\\", "/");
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(patched.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private String patchSingleJavaFile(String source,
                                      String relPath,
                                      List<ApiRecord> targets,
                                      LocalDate modalBlockedDate,
                                      String modalTicketNo,
                                      String modalTicketTitle,
                                      List<Map<String, Object>> manifestItems) {
        // 1) 파일 내 API 매핑을 extractor 로 계산 (정확한 apiPath/httpMethod 매칭)
        List<ApiInfo> apis = extractorService.parseJavaSourceForInternalUse(source, relPath, "", null);
        Map<String, ApiInfo> apiKeyToInfo = new HashMap<>();
        for (ApiInfo a : apis) {
            apiKeyToInfo.put(key(a.getApiPath(), a.getHttpMethod()), a);
        }

        // 2) AST 파싱 + lexical preservation
        CompilationUnit cu = StaticJavaParser.parse(source);
        LexicalPreservingPrinter.setup(cu);

        Map<Integer, MethodDeclaration> beginLineToMethod = new HashMap<>();
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            md.getRange().ifPresent(r -> beginLineToMethod.put(r.begin.line, md));
        }

        Statement blockStmt = StaticJavaParser.parseStatement(BLOCK_THROW_LINE);

        for (ApiRecord r : targets) {
            LocalDate effDate = effectiveBlockedDate(r, modalBlockedDate);
            String effNo = effectiveTicketNo(r, modalTicketNo);
            String effTitle = effectiveTicketTitle(modalTicketTitle);
            String deprecateDocLine = buildDeprecateDocLine(effDate, effNo, effTitle);
            String k = key(r.getApiPath(), r.getHttpMethod());
            ApiInfo info = apiKeyToInfo.get(k);
            if (info == null || info.getMethodBeginLine() == null) {
                manifestItems.add(item("failed", r.getId(), safeKey(r), "handler_not_found", "소스에서 URL 매핑(메소드)을 찾지 못했습니다."));
                continue;
            }
            MethodDeclaration md = beginLineToMethod.get(info.getMethodBeginLine());
            if (md == null) {
                manifestItems.add(item("failed", r.getId(), safeKey(r), "method_range_not_found", "메소드 Range를 찾지 못했습니다."));
                continue;
            }
            if (md.getBody().isEmpty()) {
                manifestItems.add(item("skipped", r.getId(), safeKey(r), "no_body", "메소드 바디가 없어 차단코드를 삽입할 수 없습니다."));
                continue;
            }

            boolean changed = false;

            // @Deprecated
            if (!md.isAnnotationPresent("Deprecated")) {
                md.addAnnotation("Deprecated");
                changed = true;
            }

            // Javadoc: /** * @deprecated [URL차단작업][날짜][티켓] 제목 ... */
            boolean hasDeprecateBlock = hasDeprecatedUrlBlockJavadoc(md);
            if (!hasDeprecateBlock) {
                String newDoc;
                if (md.getJavadocComment().isPresent()) {
                    String raw = md.getJavadocComment().get().getContent();
                    String trimmed = raw == null ? "" : raw.trim();
                    if (!trimmed.endsWith("\n")) trimmed = trimmed + "\n";
                    newDoc = trimmed + " * " + deprecateDocLine + "\n ";
                } else {
                    newDoc = "\n * " + deprecateDocLine + "\n ";
                }
                md.setComment(new JavadocComment(newDoc));
                changed = true;
            }

            // if(true) throw ...
            if (!startsWithBlockGuard(md)) {
                md.getBody().get().getStatements().addFirst(blockStmt.clone());
                changed = true;
            }

            manifestItems.add(item(changed ? "patched" : "skipped", r.getId(), safeKey(r),
                    changed ? "ok" : "already_applied",
                    changed ? "패치 적용" : "이미 차단 표기가 존재합니다."));
        }

        return compactBlockGuardLines(LexicalPreservingPrinter.print(cu));
    }

    private static boolean startsWithBlockGuard(MethodDeclaration md) {
        try {
            if (md.getBody().isEmpty()) return false;
            var stmts = md.getBody().get().getStatements();
            if (stmts.isEmpty()) return false;
            Statement s0 = stmts.get(0);
            if (!(s0 instanceof IfStmt ifs)) return false;
            if (!(ifs.getCondition() instanceof BooleanLiteralExpr bl) || !bl.getValue()) return false;
            Statement thenStmt = ifs.getThenStmt();
            ThrowStmt ts = null;
            if (thenStmt instanceof ThrowStmt t) {
                ts = t;
            } else if (thenStmt instanceof com.github.javaparser.ast.stmt.BlockStmt blk
                    && blk.getStatements().size() == 1
                    && blk.getStatement(0) instanceof ThrowStmt t) {
                ts = t;
            }
            if (ts == null) return false;
            if (!(ts.getExpression() instanceof ObjectCreationExpr oce)) return false;
            if (!"UnsupportedOperationException".equals(oce.getType().getNameAsString())) return false;
            if (oce.getArguments().size() != 1) return false;
            if (!(oce.getArgument(0) instanceof StringLiteralExpr sl)) return false;
            return "차단된 URL 입니다.".equals(sl.getValue());
        } catch (Exception e) {
            return false;
        }
    }

    /** 메소드 주석에 이미 `@deprecated [URL차단작업]...` 또는 구형 `deprecated:` 동일 패턴이 있는지 */
    private static boolean hasDeprecatedUrlBlockJavadoc(MethodDeclaration md) {
        try {
            String existing = md.getComment().map(c -> c.getContent()).orElse("");
            if (existing == null || existing.isBlank()) return false;
            String compact = existing.replace('\n', ' ').replace('\r', ' ');
            boolean hasDeprecateToken = compact.contains("@deprecated")
                    || compact.contains("deprecated:");
            return hasDeprecateToken && ApiStorageService.containsUrlBlockTag(compact);
        } catch (Exception e) {
            return false;
        }
    }

    private static String buildDeprecateDocLine(LocalDate date, String ticketNo, String title) {
        return "@deprecated [URL차단작업][" + date + "][" + ticketNo + "] " + title;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LocalDate effectiveBlockedDate(ApiRecord r, LocalDate modal) {
        return r.getDeployScheduledDate() != null ? r.getDeployScheduledDate() : modal;
    }

    private static String effectiveTicketNo(ApiRecord r, String modal) {
        if (!isBlank(r.getDeployCsr())) return r.getDeployCsr().trim();
        return modal == null ? "" : modal.trim();
    }

    private static String effectiveTicketTitle(String modal) {
        return isBlank(modal) ? "-" : modal.trim();
    }

    private static String key(String apiPath, String httpMethod) {
        return String.valueOf(apiPath) + "|" + String.valueOf(httpMethod);
    }

    private static String safeKey(ApiRecord r) {
        if (r == null) return "-";
        return (r.getRepositoryName() != null ? r.getRepositoryName() : "-") + "|" +
                (r.getApiPath() != null ? r.getApiPath() : "-") + "|" +
                (r.getHttpMethod() != null ? r.getHttpMethod() : "-");
    }

    private static Map<String, Object> item(String status, Long id, String key, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("id", id);
        m.put("key", key);
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}

