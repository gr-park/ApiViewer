package com.baek.viewer.service;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Service
public class CloneService {

    private static final Logger log = LoggerFactory.getLogger(CloneService.class);
    private static final int MAX_CLONE_REPOS = 5;
    private static final ObjectMapper om = new ObjectMapper();
    private static final Pattern PCT_PATTERN = Pattern.compile("(\\d+)%");

    // jobId -> 레포별 진행 상태 목록
    private final ConcurrentHashMap<String, List<RepoCloneStatus>> jobMap = new ConcurrentHashMap<>();

    private final GlobalConfigRepository globalRepo;
    private final RepoConfigRepository repoConfigRepo;

    @Value("${api.viewer.repos-config-path:./repos-config.yml}")
    private String reposConfigPath;

    public CloneService(GlobalConfigRepository globalRepo, RepoConfigRepository repoConfigRepo) {
        this.globalRepo = globalRepo;
        this.repoConfigRepo = repoConfigRepo;
    }

    // ── Bitbucket 레포 목록 조회 (페이지네이션) ──────────────────────────
    public Map<String, Object> listRepos(int start) throws Exception {
        GlobalConfig gc = globalRepo.findById(1L)
                .orElseThrow(() -> new IllegalStateException("설정 정보가 없습니다."));

        String baseUrl = gc.getBitbucketUrl();
        String token   = gc.getBitbucketToken();
        int limit      = gc.getListRepoLimit();

        if (baseUrl == null || baseUrl.isBlank())
            throw new IllegalArgumentException("Bitbucket URL이 설정되지 않았습니다.");
        if (token == null || token.isBlank())
            throw new IllegalArgumentException("Bitbucket 토큰이 설정되지 않았습니다.");

        String url = baseUrl.replaceAll("/+$", "")
                + "/rest/api/1.0/repos?limit=" + limit + "&start=" + start;

        log.info("[Bitbucket 레포 조회] start={} url={}", start, url);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(trustAllSslContext())
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("[Bitbucket API 오류] status={} body={}", response.statusCode(), response.body());
            throw new RuntimeException("Bitbucket API 오류 (HTTP " + response.statusCode() + ")");
        }

        Map<String, Object> result = om.readValue(response.body(), new TypeReference<>() {});
        log.info("[Bitbucket 레포 조회 완료] start={} isLastPage={}", start, result.get("isLastPage"));
        return result;
    }

    // ── Clone 실행 (비동기, 최대 5개) ────────────────────────────────────
    public String startClone(List<Map<String, String>> repos) {
        if (repos == null || repos.isEmpty())
            throw new IllegalArgumentException("클론할 레포지토리를 선택하세요.");
        if (repos.size() > MAX_CLONE_REPOS)
            throw new IllegalArgumentException("한 번에 최대 " + MAX_CLONE_REPOS + "개까지 클론 가능합니다.");

        GlobalConfig gc = globalRepo.findById(1L)
                .orElseThrow(() -> new IllegalStateException("설정 정보가 없습니다."));
        String localPath  = gc.getCloneLocalPath();
        String gitBashPath = gc.getGitBashPath();

        if (localPath == null || localPath.isBlank())
            throw new IllegalArgumentException("클론 로컬 경로가 설정되지 않았습니다.");

        String gitExe = resolveGitExe(gitBashPath);
        log.info("[Clone 준비] gitBashPath={} -> gitExe={}", gitBashPath, gitExe);

        String jobId = UUID.randomUUID().toString();
        List<RepoCloneStatus> statuses = new CopyOnWriteArrayList<>();

        for (Map<String, String> repo : repos) {
            RepoCloneStatus s = new RepoCloneStatus(repo.get("slug"), repo.get("cloneUrl"), repo.getOrDefault("project", ""));
            statuses.add(s);
        }
        jobMap.put(jobId, statuses);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (RepoCloneStatus status : statuses) {
            futures.add(CompletableFuture.runAsync(() -> runGitClone(status, localPath, gitExe)));
        }
        // 모든 클론 완료 후 이메일 발송
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> sendCompletionMail(jobId, statuses));

        log.info("[Clone 시작] jobId={} repos={}", jobId, repos.stream().map(r -> r.get("slug")).toList());
        return jobId;
    }

    /**
     * git-bash.exe 경로에서 실제 git.exe 경로를 유추한다.
     * 예) C:\Program Files\Git\git-bash.exe -> C:\Program Files\Git\bin\git.exe
     * 유추 실패 시 시스템 PATH의 "git" 사용.
     */
    private String resolveGitExe(String gitBashPath) {
        if (gitBashPath == null || gitBashPath.isBlank()) return "git";
        File gitBashFile = new File(gitBashPath);
        File gitDir = gitBashFile.getParentFile();
        if (gitDir == null) return "git";

        // Git for Windows 일반 설치 구조: {gitDir}/bin/git.exe
        File candidate = new File(gitDir, "bin" + File.separator + "git.exe");
        if (candidate.exists()) return candidate.getAbsolutePath();

        // {gitDir}/cmd/git.exe
        candidate = new File(gitDir, "cmd" + File.separator + "git.exe");
        if (candidate.exists()) return candidate.getAbsolutePath();

        // mingw64/bin/git.exe (일부 설치)
        candidate = new File(gitDir, "mingw64" + File.separator + "bin" + File.separator + "git.exe");
        if (candidate.exists()) return candidate.getAbsolutePath();

        log.warn("[git 경로 유추 실패] gitBashPath={} — 시스템 git 사용", gitBashPath);
        return "git";
    }

    private void runGitClone(RepoCloneStatus status, String localPath, String gitExe) {
        try {
            status.setStatus("CLONING");
            status.setPercent(0);

            String cloneUrl  = status.getCloneUrl();
            String project   = status.getProject();
            File   parentDir = (project != null && !project.isBlank())
                    ? new File(localPath, project)
                    : new File(localPath);
            File targetDir   = new File(parentDir, status.getSlug());

            if (targetDir.exists()) {
                status.addLog("[이미 존재] " + targetDir.getAbsolutePath() + " — 이미 클론된 레포지토리입니다. 건너뜁니다.");
                status.setStatus("ALREADY_EXISTS");
                status.setPercent(100);
                return;
            }

            parentDir.mkdirs();

            status.addLog("[시작] git clone -> " + targetDir.getAbsolutePath());
            status.addLog("[git] " + gitExe);

            ProcessBuilder pb = new ProcessBuilder(gitExe, "clone", "--progress", cloneUrl,
                    targetDir.getAbsolutePath());
            pb.redirectErrorStream(true); // stderr(진행률)를 stdout으로 합침
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    status.addLog(line);
                    int pct = calcPercent(line);
                    if (pct >= 0) status.setPercent(pct);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                status.setStatus("DONE");
                status.setPercent(100);
                status.addLog("[완료] 클론 성공 (100%)");
                updateRootPathAfterClone(status.getSlug(), targetDir.getAbsolutePath(), status);
            } else {
                status.setStatus("ERROR");
                status.addLog("[실패] 종료 코드: " + exitCode);
            }
        } catch (Exception e) {
            status.setStatus("ERROR");
            status.addLog("[오류] " + e.getMessage());
            log.error("[Clone 오류][{}] {}", status.getSlug(), e.getMessage());
        }
    }

    /**
     * git clone --progress 출력 라인에서 진행률(0~100)을 계산한다.
     * Counting objects:          -> 3%
     * Compressing objects: XX%   -> 5 ~ 15%
     * Receiving objects:   XX%   -> 15 ~ 85%
     * Resolving deltas:    XX%   -> 85 ~ 100%
     * 해당 없으면 -1 반환.
     */
    private int calcPercent(String line) {
        if (line.contains("Counting objects")) return 3;
        if (line.contains("Compressing objects")) {
            int p = extractPct(line);
            return p < 0 ? 5 : 5 + p / 10;       // 5 ~ 15
        }
        if (line.contains("Receiving objects")) {
            int p = extractPct(line);
            return p < 0 ? 15 : 15 + p * 70 / 100; // 15 ~ 85
        }
        if (line.contains("Resolving deltas")) {
            int p = extractPct(line);
            return p < 0 ? 85 : 85 + p * 15 / 100; // 85 ~ 100
        }
        return -1;
    }

    private int extractPct(String line) {
        Matcher m = PCT_PATTERN.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * 클론 성공 후 repoName이 slug와 일치하는 레포의 rootPath를
     * DB(RepoConfig)와 repos-config.yml 파일에 동시에 업데이트한다.
     */
    private void updateRootPathAfterClone(String slug, String clonedPath, RepoCloneStatus status) {
        // 1. DB 업데이트
        try {
            repoConfigRepo.findByRepoName(slug).ifPresent(rc -> {
                rc.setRootPath(clonedPath);
                repoConfigRepo.save(rc);
                log.info("[rootPath 업데이트][DB] repoName={} -> {}", slug, clonedPath);
                status.addLog("[rootPath 업데이트] DB 설정 완료: " + clonedPath);
            });
        } catch (Exception e) {
            log.warn("[rootPath 업데이트][DB 실패] repoName={} error={}", slug, e.getMessage());
            status.addLog("[rootPath 업데이트] DB 업데이트 실패: " + e.getMessage());
        }

        // 2. YAML 파일 업데이트
        try {
            updateRootPathInYaml(slug, clonedPath);
            log.info("[rootPath 업데이트][YAML] repoName={} -> {}", slug, clonedPath);
            status.addLog("[rootPath 업데이트] YAML 파일 반영 완료");
        } catch (Exception e) {
            log.warn("[rootPath 업데이트][YAML 실패] repoName={} error={}", slug, e.getMessage());
            status.addLog("[rootPath 업데이트] YAML 파일 업데이트 실패: " + e.getMessage());
        }
    }

    /**
     * repos-config.yml 파일에서 해당 repoName 블록의 rootPath 값을 newPath로 교체한다.
     * 주석·포맷·순서를 보존하기 위해 텍스트 파싱 방식으로 처리한다.
     */
    private synchronized void updateRootPathInYaml(String repoName, String newPath) throws Exception {
        Path filePath = Path.of(reposConfigPath);
        if (!Files.exists(filePath)) {
            log.warn("[YAML rootPath 업데이트] 파일 없음: {}", reposConfigPath);
            return;
        }

        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        List<String> result = new ArrayList<>(lines.size());

        boolean inTargetBlock = false;
        boolean replaced = false;
        // repos 블록 진입 여부 (repositories: 아래에서만 repoName을 탐색)
        boolean inRepositories = false;

        for (String line : lines) {
            String trimmed = line.stripLeading();

            // repositories: 섹션 시작 감지
            if (trimmed.startsWith("repositories:")) {
                inRepositories = true;
                result.add(line);
                continue;
            }

            // 다른 최상위 섹션 시작 시 repositories 섹션 종료
            if (!trimmed.startsWith("-") && !trimmed.startsWith("#") && trimmed.contains(":") && !line.startsWith(" ") && !line.startsWith("\t")) {
                inRepositories = false;
                inTargetBlock = false;
            }

            if (inRepositories) {
                // 새 레포 엔트리 시작 (  - repoName: 또는  - repoName:)
                if (trimmed.startsWith("- repoName:")) {
                    String entryName = trimmed.substring("- repoName:".length()).trim();
                    inTargetBlock = repoName.equals(entryName);
                    result.add(line);
                    continue;
                }

                // 대상 블록 안에서 rootPath 줄 교체
                if (inTargetBlock && trimmed.startsWith("rootPath:")) {
                    // 원래 인덴트 유지
                    String indent = line.substring(0, line.length() - line.stripLeading().length());
                    result.add(indent + "rootPath: " + newPath);
                    replaced = true;
                    continue;
                }
            }

            result.add(line);
        }

        if (!replaced) {
            log.warn("[YAML rootPath 업데이트] repoName={}에 해당하는 rootPath 항목을 찾지 못했습니다.", repoName);
            return;
        }

        // 원자적 교체: 임시 파일에 쓴 후 이동
        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        Files.write(tmp, result, StandardCharsets.UTF_8);
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public List<RepoCloneStatus> getJobStatus(String jobId) {
        return jobMap.get(jobId);
    }

    // ── Clone 완료 이메일 알림 ────────────────────────────────────────────
    private void sendCompletionMail(String jobId, List<RepoCloneStatus> statuses) {
        try {
            GlobalConfig gc = globalRepo.findById(1L).orElse(null);
            if (gc == null || gc.getSmtpHost() == null || gc.getSmtpHost().isBlank()) {
                log.info("[Clone 완료 알림] SMTP 미설정 — 메일 발송 생략");
                return;
            }
            String mailTo = gc.getMailTo();
            if (mailTo == null || mailTo.isBlank()) {
                log.info("[Clone 완료 알림] 수신자 미설정 — 메일 발송 생략");
                return;
            }

            long doneCount   = statuses.stream().filter(s -> "DONE".equals(s.getStatus())).count();
            long skipCount   = statuses.stream().filter(s -> "ALREADY_EXISTS".equals(s.getStatus())).count();
            long errorCount  = statuses.stream().filter(s -> "ERROR".equals(s.getStatus())).count();

            StringBuilder sb = new StringBuilder();
            sb.append("Bitbucket Clone 작업이 완료되었습니다.\n\n");
            sb.append("JobId    : ").append(jobId).append("\n");
            sb.append("완료     : ").append(doneCount).append("개\n");
            sb.append("이미존재 : ").append(skipCount).append("개\n");
            sb.append("실패     : ").append(errorCount).append("개\n");
            sb.append("\n─────────────────────────────────\n");
            for (RepoCloneStatus s : statuses) {
                sb.append(String.format("[%s] %s\n", s.getStatus(), s.getSlug()));
                // 마지막 로그 1줄 (요약)
                List<String> logs = s.getLogs();
                if (!logs.isEmpty()) sb.append("    ").append(logs.get(logs.size() - 1)).append("\n");
            }
            sb.append("─────────────────────────────────\n");
            sb.append("완료 시각: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            String subject = String.format("[ApiViewer] Clone 완료 — 성공 %d / 이미존재 %d / 실패 %d", doneCount, skipCount, errorCount);

            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(gc.getSmtpHost());
            sender.setPort(gc.getSmtpPort());
            if (gc.getSmtpUsername() != null && !gc.getSmtpUsername().isBlank()) {
                sender.setUsername(gc.getSmtpUsername());
                sender.setPassword(gc.getSmtpPassword());
            }
            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.connectiontimeout", "5000");
            props.put("mail.smtp.timeout", "5000");
            if (gc.getSmtpPort() == 587) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.auth", "true");
            }

            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(gc.getMailFrom() != null && !gc.getMailFrom().isBlank() ? gc.getMailFrom() : "apiviewer@localhost");
            msg.setTo(mailTo.split("[,;\\s]+"));
            msg.setSubject(subject);
            msg.setText(sb.toString());

            sender.send(msg);
            log.info("[Clone 완료 알림] 메일 발송 완료 — 수신자={}, 제목={}", mailTo, subject);
        } catch (Exception e) {
            log.warn("[Clone 완료 알림] 메일 발송 실패: {}", e.getMessage());
        }
    }

    // ── sh 스크립트 생성 ─────────────────────────────────────────────────
    public String generateScript(List<Map<String, String>> repos) {
        GlobalConfig gc = globalRepo.findById(1L)
                .orElseThrow(() -> new IllegalStateException("설정 정보가 없습니다."));
        String localPath = gc.getCloneLocalPath() != null ? gc.getCloneLocalPath() : "";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("# ─────────────────────────────────────────────────────────\n");
        sb.append("# Bitbucket Clone Script (SSH)\n");
        sb.append("# 생성일시: ").append(timestamp).append("\n");
        sb.append("# 대상 레포: ").append(repos.size()).append("개\n");
        sb.append("# ─────────────────────────────────────────────────────────\n\n");
        sb.append("BASE_DIR=\"").append(localPath).append("\"\n\n");
        sb.append("mkdir -p \"$BASE_DIR\"\n\n");

        for (int i = 0; i < repos.size(); i++) {
            Map<String, String> repo = repos.get(i);
            String slug     = repo.get("slug");
            String project  = repo.getOrDefault("project", "");
            String cloneUrl = repo.get("cloneUrl") != null ? repo.get("cloneUrl") : "";
            String targetPath = (project != null && !project.isBlank())
                    ? "\"$BASE_DIR/" + project + "/" + slug + "\""
                    : "\"$BASE_DIR/" + slug + "\"";

            sb.append("# ").append(i + 1).append(". ").append(slug).append("\n");
            if (project != null && !project.isBlank()) {
                sb.append("mkdir -p \"$BASE_DIR/").append(project).append("\"\n");
            }
            sb.append("echo '=== [").append(i + 1).append("/").append(repos.size())
              .append("] ").append(slug).append(" 클론 시작 ==='\n");
            sb.append("git clone --progress \\\n");
            sb.append("  \"").append(cloneUrl).append("\" \\\n");
            sb.append("  ").append(targetPath).append(" 2>&1 \\\n");
            sb.append("  | grep -E '(Cloning|remote:|Receiving|Resolving|done\\.|error|fatal)'\n");
            sb.append("echo ''\n\n");
        }

        sb.append("echo '=== 전체 완료 ==='\n");
        return sb.toString();
    }

    // ── SSL 인증 Skip ────────────────────────────────────────────────────
    private static SSLContext trustAllSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
            }, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("SSL 컨텍스트 초기화 실패", e);
        }
    }

    // ── 내부 상태 클래스 ─────────────────────────────────────────────────
    public static class RepoCloneStatus {
        private final String slug;
        private final String cloneUrl;
        private final String project;
        private volatile String status = "PENDING";
        private volatile int percent = 0;
        private final List<String> logs = new CopyOnWriteArrayList<>();

        public RepoCloneStatus(String slug, String cloneUrl, String project) {
            this.slug     = slug;
            this.cloneUrl = cloneUrl;
            this.project  = project != null ? project : "";
        }

        public void addLog(String line) { logs.add(line); }

        public String getSlug()     { return slug; }
        public String getCloneUrl() { return cloneUrl; }
        public String getProject()  { return project; }
        public String getStatus()   { return status; }
        public void setStatus(String s) { this.status = s; }
        public int getPercent()     { return percent; }
        public void setPercent(int p) { this.percent = Math.min(100, Math.max(0, p)); }
        public List<String> getLogs() { return new ArrayList<>(logs); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug",    slug);
            m.put("status",  status);
            m.put("percent", percent);
            m.put("logs",    getLogs());
            return m;
        }
    }
}
