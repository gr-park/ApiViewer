package com.baek.viewer.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "global_config")
public class GlobalConfig {

    @Id
    private Long id = 1L; // 단일 레코드

    @Column(name = "start_date", length = 20)
    private String startDate;

    @Column(name = "end_date", length = 20)
    private String endDate;

    @Column(name = "review_threshold")
    private Integer reviewThreshold = 3;

    @Column(name = "password", length = 100)
    private String password;

    /** 페이지당 표시 건수 (기본 200) */
    @Column(name = "page_size")
    private Integer pageSize = 200;

    /** 페이지 네비게이션 표시 개수 (기본 10) */
    @Column(name = "page_nav_size")
    private Integer pageNavSize = 10;

    /** 팀 목록 JSON: ["IT카드개발팀","IT커머스개발팀"] — 자동완성용 */
    @Column(name = "teams", columnDefinition = "TEXT")
    private String teams;

    /** 와탭 공통 프로필 JSON: [{"name":"운영","url":"...","cookie":"..."}] */
    @Column(name = "whatap_profiles", columnDefinition = "TEXT")
    private String whatapProfiles;

    /** 제니퍼 공통 프로필 JSON: [{"name":"운영","url":"...","bearerToken":"..."}] */
    @Column(name = "jennifer_profiles", columnDefinition = "TEXT")
    private String jenniferProfiles;

    /** Whatap Mock 사용 여부 (Y/N) */
    @Column(name = "whatap_mock_enabled", length = 5)
    private String whatapMockEnabled = "N";

    /** Jennifer Mock 사용 여부 (Y/N) */
    @Column(name = "jennifer_mock_enabled", length = 5)
    private String jenniferMockEnabled = "N";

    /** 시스템 로그 레벨 (INFO / DEBUG) — DEBUG 시 파싱 과정·APM 요청/응답 전문 기록 */
    @Column(name = "apm_log_level", length = 10)
    private String apmLogLevel = "INFO";

    /** 로그 조회 시 tail 줄 수 (기본 1000, 0이면 전체) */
    @Column(name = "log_tail_lines")
    private Integer logTailLines = 1000;

    // ── 메일 발송 설정 ──
    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort = 25;

    @Column(name = "smtp_username")
    private String smtpUsername;

    @Column(name = "smtp_password")
    private String smtpPassword;

    @Column(name = "mail_from")
    private String mailFrom;

    @Column(name = "mail_to", columnDefinition = "TEXT")
    private String mailTo;  // 쉼표 구분 수신자 목록

    /** 와탭 조회 ptotal (전체 건수 상한, 기본 100) */
    @Column(name = "whatap_ptotal")
    private Integer whatapPtotal = 100;

    /** 와탭 조회 psize (한 페이지 건수, 기본 10000) */
    @Column(name = "whatap_psize")
    private Integer whatapPsize = 10000;

    /**
     * 현업검토 화면에 표시할 상태 목록 (콤마 구분)
     * 기본값: 3가지 차단대상 모두 표시
     */
    @Column(name = "review_target_statuses", columnDefinition = "TEXT")
    private String reviewTargetStatuses;

    /**
     * 데이터 백업 메타 정보 JSON
     * {"analysis":{"at":"...","count":0},"callHistory":{"at":"...","count":0}}
     */
    @Column(name = "last_backup_meta", columnDefinition = "TEXT")
    private String lastBackupMeta;

    // ── Bitbucket 클론 설정 ──
    /** Bitbucket 서버 URL (예: https://bitbucket.company.com) */
    @Column(name = "bitbucket_url", length = 500)
    private String bitbucketUrl;

    /** Bitbucket Bearer 토큰 */
    @Column(name = "bitbucket_token", columnDefinition = "TEXT")
    private String bitbucketToken;

    /** 레포 목록 조회 시 페이지당 건수 (기본 100) */
    @Column(name = "list_repo_limit")
    private Integer listRepoLimit = 100;

    /** 클론받을 로컬 디렉토리 경로 */
    @Column(name = "clone_local_path", length = 1000)
    private String cloneLocalPath;

    /** git-bash.exe 경로 (Windows 전용, 예: C:\Program Files\Git\git-bash.exe) */
    @Column(name = "git_bash_path", length = 1000)
    private String gitBashPath;

    /**
     * URL 차단 모니터링 — 와탭 Referer 경로 템플릿.
     * 호스트(scheme+host+port)는 RepoConfig.whatapUrl 에서 자동 추출,
     * 이 값은 {base}에 덧붙일 Referer 경로만 담는다. {pcode} 플레이스홀더는 RepoConfig.whatapPcode 로 치환.
     * 예: "/v2/project/apm/{pcode}/new/tx_profile" (host는 RepoConfig.whatapUrl 에서 자동 추출)
     */
    @Column(name = "block_monitor_whatap_referer", columnDefinition = "TEXT")
    private String blockMonitorWhatapReferer;

    /** URL 차단 모니터링에서 제외할 봇 키워드 (콤마 구분). userAgent / clientType 부분일치 */
    @Column(name = "bot_keywords", columnDefinition = "TEXT")
    private String botKeywords;

    /**
     * URL 차단 모니터링 — 차단 검증 작업 시간대 시작 (HH:mm, 24h, 로컬 시각).
     * 이 시간대(start ≤ t < end)에 속하는 트랜잭션은 조회 결과에서 자동 제외 — 담당자 차단 검증 호출이 잡음으로 섞이지 않도록.
     * 기본값 18:00.
     */
    @Column(name = "block_monitor_exclude_start", length = 5)
    private String blockMonitorExcludeStart;

    /** URL 차단 모니터링 — 차단 검증 작업 시간대 종료 (HH:mm, 24h). 기본값 20:00. */
    @Column(name = "block_monitor_exclude_end", length = 5)
    private String blockMonitorExcludeEnd;

    /**
     * 테스트/샘플 의심 URL 판정 키워드 (콤마 구분, 대소문자 무시 부분일치).
     * URL 경로 / 메소드명 / 컨트롤러명 / 파일경로 / 주석 / @ApiOperation / @Description 매칭.
     * fullUrl 은 의도적으로 제외 (도메인 자체에 test/stg 가 포함된 환경 false positive 방지).
     */
    @Column(name = "test_suspect_keywords", columnDefinition = "TEXT")
    private String testSuspectKeywords;

    /** URL 스냅샷 보관일수 (null이면 YML 기본값 사용) */
    @Column(name = "snapshot_retention_days")
    private Integer snapshotRetentionDays;

    // ── 사내 AI OpenAPI (OpenAI 호환 chat) ──
    /** 베이스 URL (예: https://ai.company.com) — 경로 제외 */
    @Column(name = "ai_open_api_base_url", length = 500)
    private String aiOpenApiBaseUrl;

    @Column(name = "ai_open_api_token", columnDefinition = "TEXT")
    private String aiOpenApiToken;

    /**
     * Chat completions 경로 (베이스 뒤에 붙음). 기본 /v1/chat/completions
     */
    @Column(name = "ai_open_api_chat_path", length = 200)
    private String aiOpenApiChatPath;

    /** 요청 모델명 (사내 API 스펙에 맞게) */
    @Column(name = "ai_open_api_model", length = 200)
    private String aiOpenApiModel;

    /**
     * ops_digest AI 요약을 트리거할 배치 jobType 목록 (콤마 구분).
     * null/빈값이면 코드 기본 집합 사용.
     */
    @Column(name = "ai_ops_digest_job_types", columnDefinition = "TEXT")
    private String aiOpsDigestJobTypes;

    /** 마지막 ops_digest AI 응답 (설정 화면 확인용) */
    @Column(name = "ai_last_ops_digest", columnDefinition = "TEXT")
    private String aiLastOpsDigest;

    @Column(name = "ai_last_ops_digest_at")
    private LocalDateTime aiLastOpsDigestAt;

    /**
     * APM ↔ URL분석현황 매칭 진단 리포트 (JSON).
     * - 대시보드/설정의 "자동 리포트" 노출용
     * - 큰 데이터는 담지 않고 요약 + 레포별 top 미매칭 샘플만 저장한다.
     */
    @Column(name = "apm_match_report", columnDefinition = "TEXT")
    private String apmMatchReport;

    /** 마지막 APM 매칭 리포트 생성 시각 */
    @Column(name = "apm_match_report_at")
    private LocalDateTime apmMatchReportAt;

    /**
     * URL 분석(Extract) 문제 파일 요약 리포트(JSON).
     * - extract.html에서 추출 종료 시점(오류/0개추출 발생 시) 저장
     * - 대시보드/상단 배너 및 설정 화면에서 재확인/다운로드 용도
     */
    @Column(name = "extract_issue_report", columnDefinition = "TEXT")
    private String extractIssueReport;

    /** 마지막 URL 분석(Extract) 문제 파일 요약 리포트 생성 시각 */
    @Column(name = "extract_issue_report_at")
    private LocalDateTime extractIssueReportAt;

    /**
     * 전역 배너 숨김이 적용된 extract issue 리포트 시각.
     * 현재 리포트 시각과 같으면 "이 리포트는 전역 숨김 처리됨"으로 본다.
     */
    @Column(name = "extract_issue_dismiss_report_at")
    private LocalDateTime extractIssueDismissReportAt;

    /** extract issue 배너를 마지막으로 전역 숨김 처리한 시각 */
    @Column(name = "extract_issue_dismissed_at")
    private LocalDateTime extractIssueDismissedAt;

    /** 포털 전체 쪽지(관리자 브로드캐스트) — 로그인 사용자에게만 표시 */
    @Column(name = "portal_notice_text", columnDefinition = "TEXT")
    private String portalNoticeText;

    @Column(name = "portal_notice_at")
    private LocalDateTime portalNoticeAt;

    /** LOCAi 추천 메시지 캐시 — AI_SUMMARY 배치가 주기적으로 갱신 */
    @Column(name = "ai_dashboard_summary", columnDefinition = "TEXT")
    private String aiDashboardSummary;

    @Column(name = "ai_dashboard_summary_at")
    private LocalDateTime aiDashboardSummaryAt;

    /** 대시보드 LOCAi 추천 메시지 영역 노출 여부 (Y/N, 기본 Y) */
    @Column(name = "ai_dashboard_enabled", length = 5)
    private String aiDashboardEnabled = "Y";

    /** /api/ai/* API 요청 허용 여부 (Y/N, 기본 Y) */
    @Column(name = "ai_api_enabled", length = 5)
    private String aiApiEnabled = "Y";

    /**
     * URL 분석(Extract) 시 관련메뉴 미흡 건에 대해 사내 AI로 관련메뉴(override) 자동 보완 (Y/N, 기본 N).
     */
    @Column(name = "ai_related_menu_deficient_auto", length = 5)
    private String aiRelatedMenuDeficientAuto = "N";

    /** 관련메뉴 미흡 — effective 글자수가 이 값 미만이면 미흡 (기본 3) */
    @Column(name = "related_menu_deficient_min_len")
    private Integer relatedMenuDeficientMinLen;

    /** 관련메뉴 미흡 — effective 글자수가 이 값 초과이면 미흡 (기본 29) */
    @Column(name = "related_menu_deficient_max_len")
    private Integer relatedMenuDeficientMaxLen;

    public Long getId() { return id; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public Integer getReviewThreshold() { return reviewThreshold != null ? reviewThreshold : 3; }
    public void setReviewThreshold(Integer reviewThreshold) { this.reviewThreshold = reviewThreshold; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Integer getPageSize() { return pageSize != null ? pageSize : 200; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public Integer getPageNavSize() { return pageNavSize != null ? pageNavSize : 10; }
    public void setPageNavSize(Integer pageNavSize) { this.pageNavSize = pageNavSize; }
    public String getTeams() { return teams; }
    public void setTeams(String teams) { this.teams = teams; }
    public String getWhatapProfiles() { return whatapProfiles; }
    public void setWhatapProfiles(String whatapProfiles) { this.whatapProfiles = whatapProfiles; }
    public String getJenniferProfiles() { return jenniferProfiles; }
    public void setJenniferProfiles(String jenniferProfiles) { this.jenniferProfiles = jenniferProfiles; }
    public String getWhatapMockEnabled() { return whatapMockEnabled != null ? whatapMockEnabled : "N"; }
    public void setWhatapMockEnabled(String v) { this.whatapMockEnabled = v; }
    public boolean isWhatapMockEnabled() { return "Y".equalsIgnoreCase(getWhatapMockEnabled()); }
    public String getJenniferMockEnabled() { return jenniferMockEnabled != null ? jenniferMockEnabled : "N"; }
    public void setJenniferMockEnabled(String v) { this.jenniferMockEnabled = v; }
    public boolean isJenniferMockEnabled() { return "Y".equalsIgnoreCase(getJenniferMockEnabled()); }
    public String getApmLogLevel() { return apmLogLevel != null ? apmLogLevel : "INFO"; }
    public void setApmLogLevel(String v) { this.apmLogLevel = v; }
    public boolean isApmDebug() { return "DEBUG".equalsIgnoreCase(getApmLogLevel()); }
    public Integer getLogTailLines() { return logTailLines != null ? logTailLines : 1000; }
    public void setLogTailLines(Integer v) { this.logTailLines = v; }
    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String v) { this.smtpHost = v; }
    public Integer getSmtpPort() { return smtpPort != null ? smtpPort : 25; }
    public void setSmtpPort(Integer v) { this.smtpPort = v; }
    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String v) { this.smtpUsername = v; }
    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String v) { this.smtpPassword = v; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String v) { this.mailFrom = v; }
    public String getMailTo() { return mailTo; }
    public void setMailTo(String v) { this.mailTo = v; }
    public Integer getWhatapPtotal() { return whatapPtotal != null ? whatapPtotal : 100; }
    public void setWhatapPtotal(Integer v) { this.whatapPtotal = v; }
    public Integer getWhatapPsize() { return whatapPsize != null ? whatapPsize : 10000; }
    public void setWhatapPsize(Integer v) { this.whatapPsize = v; }
    public String getReviewTargetStatuses() {
        return reviewTargetStatuses != null ? reviewTargetStatuses
               : "최우선 차단대상,후순위 차단대상,검토필요대상";
    }
    public void setReviewTargetStatuses(String v) { this.reviewTargetStatuses = v; }
    public String getLastBackupMeta() { return lastBackupMeta; }
    public void setLastBackupMeta(String v) { this.lastBackupMeta = v; }
    public String getBitbucketUrl() { return bitbucketUrl; }
    public void setBitbucketUrl(String v) { this.bitbucketUrl = v; }
    public String getBitbucketToken() { return bitbucketToken; }
    public void setBitbucketToken(String v) { this.bitbucketToken = v; }
    public Integer getListRepoLimit() { return listRepoLimit != null ? listRepoLimit : 100; }
    public void setListRepoLimit(Integer v) { this.listRepoLimit = v; }
    public String getCloneLocalPath() { return cloneLocalPath; }
    public void setCloneLocalPath(String v) { this.cloneLocalPath = v; }
    public String getGitBashPath() { return gitBashPath; }
    public void setGitBashPath(String v) { this.gitBashPath = v; }
    public String getBlockMonitorWhatapReferer() {
        return blockMonitorWhatapReferer != null && !blockMonitorWhatapReferer.isBlank()
                ? blockMonitorWhatapReferer
                : "/v2/project/apm/{pcode}/new/tx_profile";
    }
    public void setBlockMonitorWhatapReferer(String v) { this.blockMonitorWhatapReferer = v; }
    public String getBotKeywords() {
        return botKeywords != null && !botKeywords.isBlank() ? botKeywords
               : "Googlebot,AdsBot,bingbot,YandexBot,DuckDuckBot,facebookexternalhit,Slackbot,Twitterbot,LinkedInBot,crawler,spider";
    }
    public void setBotKeywords(String v) { this.botKeywords = v; }

    public String getBlockMonitorExcludeStart() {
        return blockMonitorExcludeStart != null && !blockMonitorExcludeStart.isBlank()
                ? blockMonitorExcludeStart : "18:00";
    }
    public void setBlockMonitorExcludeStart(String v) { this.blockMonitorExcludeStart = v; }

    public String getBlockMonitorExcludeEnd() {
        return blockMonitorExcludeEnd != null && !blockMonitorExcludeEnd.isBlank()
                ? blockMonitorExcludeEnd : "20:00";
    }
    public void setBlockMonitorExcludeEnd(String v) { this.blockMonitorExcludeEnd = v; }

    public String getTestSuspectKeywords() {
        return (testSuspectKeywords != null && !testSuspectKeywords.isBlank())
                ? testSuspectKeywords
                : "test,sample,mock,테스트,샘플,demo,dummy,fixture,스텁";
    }
    public void setTestSuspectKeywords(String v) { this.testSuspectKeywords = v; }

    public Integer getSnapshotRetentionDays() { return snapshotRetentionDays; }
    public void setSnapshotRetentionDays(Integer v) { this.snapshotRetentionDays = v; }

    public String getAiOpenApiBaseUrl() { return aiOpenApiBaseUrl; }
    public void setAiOpenApiBaseUrl(String v) { this.aiOpenApiBaseUrl = v; }
    public String getAiOpenApiToken() { return aiOpenApiToken; }
    public void setAiOpenApiToken(String v) { this.aiOpenApiToken = v; }
    public String getAiOpenApiChatPath() { return aiOpenApiChatPath; }
    public void setAiOpenApiChatPath(String v) { this.aiOpenApiChatPath = v; }
    public String getAiOpenApiModel() { return aiOpenApiModel; }
    public void setAiOpenApiModel(String v) { this.aiOpenApiModel = v; }
    public String getAiOpsDigestJobTypes() { return aiOpsDigestJobTypes; }
    public void setAiOpsDigestJobTypes(String v) { this.aiOpsDigestJobTypes = v; }
    public String getAiLastOpsDigest() { return aiLastOpsDigest; }
    public void setAiLastOpsDigest(String v) { this.aiLastOpsDigest = v; }
    public LocalDateTime getAiLastOpsDigestAt() { return aiLastOpsDigestAt; }
    public void setAiLastOpsDigestAt(LocalDateTime v) { this.aiLastOpsDigestAt = v; }

    public String getApmMatchReport() { return apmMatchReport; }
    public void setApmMatchReport(String v) { this.apmMatchReport = v; }
    public LocalDateTime getApmMatchReportAt() { return apmMatchReportAt; }
    public void setApmMatchReportAt(LocalDateTime v) { this.apmMatchReportAt = v; }

    public String getExtractIssueReport() { return extractIssueReport; }
    public void setExtractIssueReport(String v) { this.extractIssueReport = v; }
    public LocalDateTime getExtractIssueReportAt() { return extractIssueReportAt; }
    public void setExtractIssueReportAt(LocalDateTime v) { this.extractIssueReportAt = v; }
    public LocalDateTime getExtractIssueDismissReportAt() { return extractIssueDismissReportAt; }
    public void setExtractIssueDismissReportAt(LocalDateTime v) { this.extractIssueDismissReportAt = v; }
    public LocalDateTime getExtractIssueDismissedAt() { return extractIssueDismissedAt; }
    public void setExtractIssueDismissedAt(LocalDateTime v) { this.extractIssueDismissedAt = v; }

    public String getPortalNoticeText() { return portalNoticeText; }
    public void setPortalNoticeText(String portalNoticeText) { this.portalNoticeText = portalNoticeText; }
    public LocalDateTime getPortalNoticeAt() { return portalNoticeAt; }
    public void setPortalNoticeAt(LocalDateTime portalNoticeAt) { this.portalNoticeAt = portalNoticeAt; }

    public String getAiDashboardSummary() { return aiDashboardSummary; }
    public void setAiDashboardSummary(String v) { this.aiDashboardSummary = v; }
    public LocalDateTime getAiDashboardSummaryAt() { return aiDashboardSummaryAt; }
    public void setAiDashboardSummaryAt(LocalDateTime v) { this.aiDashboardSummaryAt = v; }

    public String getAiDashboardEnabled() { return aiDashboardEnabled != null ? aiDashboardEnabled : "Y"; }
    public void setAiDashboardEnabled(String v) { this.aiDashboardEnabled = v; }
    public boolean isAiDashboardEnabled() { return !"N".equalsIgnoreCase(getAiDashboardEnabled()); }

    public String getAiApiEnabled() { return aiApiEnabled != null ? aiApiEnabled : "Y"; }
    public void setAiApiEnabled(String v) { this.aiApiEnabled = v; }
    public boolean isAiApiEnabled() { return !"N".equalsIgnoreCase(getAiApiEnabled()); }

    public String getAiRelatedMenuDeficientAuto() {
        return aiRelatedMenuDeficientAuto != null ? aiRelatedMenuDeficientAuto : "N";
    }
    public void setAiRelatedMenuDeficientAuto(String v) { this.aiRelatedMenuDeficientAuto = v; }
    public boolean isAiRelatedMenuDeficientAutoEnabled() {
        return "Y".equalsIgnoreCase(getAiRelatedMenuDeficientAuto());
    }

    public Integer getRelatedMenuDeficientMinLen() {
        return relatedMenuDeficientMinLen != null ? relatedMenuDeficientMinLen : 3;
    }
    public void setRelatedMenuDeficientMinLen(Integer v) { this.relatedMenuDeficientMinLen = v; }

    public Integer getRelatedMenuDeficientMaxLen() {
        return relatedMenuDeficientMaxLen != null ? relatedMenuDeficientMaxLen : 29;
    }
    public void setRelatedMenuDeficientMaxLen(Integer v) { this.relatedMenuDeficientMaxLen = v; }

    /** 채팅 API 전체 URL */
    public String resolveAiChatEndpoint() {
        String base = aiOpenApiBaseUrl != null ? aiOpenApiBaseUrl.trim() : "";
        if (base.isEmpty()) return "";
        String path = aiOpenApiChatPath != null && !aiOpenApiChatPath.isBlank()
                ? aiOpenApiChatPath.trim() : "/v1/chat/completions";
        if (!path.startsWith("/")) path = "/" + path;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }
}
