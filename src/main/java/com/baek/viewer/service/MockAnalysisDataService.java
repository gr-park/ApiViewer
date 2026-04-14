package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Mock 분석데이터 생성 서비스.
 *
 * 실제 소스코드 파싱 없이 ApiRecord 를 더미로 생성한다.
 * Railway 등 소스 반입이 불가능한 환경에서 데모/시연용 데이터를 채울 때 사용.
 *
 * - 네임스페이스: 사용자 지정 repoName 앞에 "mock-" prefix 자동 부착.
 * - 중복방지: (repositoryName, apiPath, httpMethod) 이미 존재하면 스킵.
 * - git_history: 랜덤 커밋 1~3개 JSON 생성 (상태 판정 로직 동작 확인용).
 */
@Service
public class MockAnalysisDataService {

    private static final Logger log = LoggerFactory.getLogger(MockAnalysisDataService.class);

    public static final String MOCK_PREFIX = "mock-";

    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};

    /** 한글 도메인/리소스/액션 샘플 (카드사 업무 기준) */
    private static final String[] DOMAINS = {
            "card", "member", "payment", "issue", "benefit", "point",
            "auth", "account", "campaign", "statement", "approval", "limit"
    };
    private static final String[] RESOURCES = {
            "list", "detail", "summary", "history", "register", "update",
            "delete", "approve", "reject", "search", "export", "stat"
    };
    private static final String[] DOMAIN_KO = {
            "카드", "회원", "결제", "발급", "혜택", "포인트",
            "인증", "계정", "캠페인", "명세서", "승인", "한도"
    };
    private static final String[] ACTION_KO = {
            "목록 조회", "상세 조회", "요약", "이력 조회", "등록", "수정",
            "삭제", "승인", "반려", "검색", "내보내기", "통계"
    };

    private static final String[] COMMIT_AUTHORS = {
            "kim.dev", "lee.dev", "park.dev", "choi.dev", "jung.dev", "yoon.dev"
    };
    private static final String[] COMMIT_MESSAGES = {
            "feat: 신규 API 추가",
            "fix: 응답 오류 수정",
            "refactor: 로직 리팩토링",
            "chore: 의존성 업데이트",
            "feat: 필드 추가",
            "fix: null 체크 보강",
            "test: 테스트 코드 추가"
    };

    private final ApiRecordRepository repo;
    private final JdbcTemplate jdbc;

    public MockAnalysisDataService(ApiRecordRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    /**
     * Mock 분석데이터 생성.
     *
     * @param repoNameRaw 사용자 지정 레포명 (자동으로 mock- prefix 부착)
     * @param count       생성할 ApiRecord 개수 (1~5000)
     * @return 생성 통계
     */
    @Transactional
    public Map<String, Object> generate(String repoNameRaw, int count) {
        if (repoNameRaw == null || repoNameRaw.isBlank()) {
            throw new IllegalArgumentException("레포명을 입력하세요.");
        }
        if (count < 1 || count > 5000) {
            throw new IllegalArgumentException("건수 범위: 1 ~ 5000");
        }

        String repoName = repoNameRaw.startsWith(MOCK_PREFIX)
                ? repoNameRaw
                : MOCK_PREFIX + repoNameRaw.trim();

        log.info("[Mock 분석데이터] 생성 시작: repo={}, count={}", repoName, count);

        // 기존 레코드 키 조회 (중복방지)
        Set<String> existingKeys = new HashSet<>();
        for (ApiRecord r : repo.findByRepositoryName(repoName)) {
            existingKeys.add(r.getApiPath() + "|" + r.getHttpMethod());
        }
        log.debug("[Mock 분석데이터] 기존 레코드={}건", existingKeys.size());

        Random rnd = new Random();
        LocalDateTime now = LocalDateTime.now();
        List<ApiRecord> toSave = new ArrayList<>();
        int skipped = 0;

        for (int i = 0; i < count; i++) {
            int domIdx = rnd.nextInt(DOMAINS.length);
            int resIdx = rnd.nextInt(RESOURCES.length);
            String domain = DOMAINS[domIdx];
            String resource = RESOURCES[resIdx];
            String method = HTTP_METHODS[rnd.nextInt(HTTP_METHODS.length)];
            String apiPath = String.format("/api/%s/%s/%d", domain, resource, rnd.nextInt(900) + 100);
            String key = apiPath + "|" + method;

            // 중복방지: 기존 DB + 이번 배치 내 중복
            if (existingKeys.contains(key)) { skipped++; continue; }
            existingKeys.add(key);

            ApiRecord r = buildRecord(repoName, apiPath, method, domIdx, resIdx, i, rnd, now);
            toSave.add(r);
        }

        if (!toSave.isEmpty()) {
            repo.saveAll(toSave);
        }

        // call_count 컬럼은 0으로 초기화 (실제 호출이력은 Mock APM에서 별도 생성)
        log.info("[Mock 분석데이터] 생성 완료: repo={}, inserted={}, skipped(중복)={}",
                repoName, toSave.size(), skipped);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repositoryName", repoName);
        result.put("inserted", toSave.size());
        result.put("skippedDuplicate", skipped);
        result.put("requested", count);
        return result;
    }

    /** mock- 레포 전체 목록 (선택 UI 용). */
    public List<String> listMockRepos() {
        return jdbc.queryForList(
                "SELECT DISTINCT repository_name FROM api_record " +
                        "WHERE repository_name LIKE ? ORDER BY repository_name",
                String.class, MOCK_PREFIX + "%");
    }

    /** 단일 mock- 레포 또는 전체 mock-* 삭제. */
    @Transactional
    public Map<String, Object> delete(String repoNameRaw) {
        int deletedApm;
        int deletedApi;
        String target;
        if (repoNameRaw == null || repoNameRaw.isBlank() || "ALL".equalsIgnoreCase(repoNameRaw)) {
            target = MOCK_PREFIX + "*";
            deletedApm = jdbc.update("DELETE FROM apm_call_data WHERE repository_name LIKE ?", MOCK_PREFIX + "%");
            deletedApi = jdbc.update("DELETE FROM api_record WHERE repository_name LIKE ?", MOCK_PREFIX + "%");
        } else {
            target = repoNameRaw.startsWith(MOCK_PREFIX) ? repoNameRaw : MOCK_PREFIX + repoNameRaw;
            deletedApm = jdbc.update("DELETE FROM apm_call_data WHERE repository_name = ?", target);
            deletedApi = jdbc.update("DELETE FROM api_record WHERE repository_name = ?", target);
        }
        log.warn("[Mock 분석데이터 삭제] target={}, api_record -{}, apm_call_data -{}",
                target, deletedApi, deletedApm);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        result.put("apiRecordDeleted", deletedApi);
        result.put("apmCallDataDeleted", deletedApm);
        return result;
    }

    // ─────────────────────────────────────────────────────────────

    private ApiRecord buildRecord(String repoName, String apiPath, String method,
                                  int domIdx, int resIdx, int seq, Random rnd, LocalDateTime now) {
        ApiRecord r = new ApiRecord();
        r.setRepositoryName(repoName);
        r.setApiPath(apiPath);
        r.setHttpMethod(method);
        r.setLastAnalyzedAt(now);
        r.setCreatedIp("mock");
        r.setModifiedAt(now);
        r.setModifiedIp("mock");
        r.setDataSource("ANALYSIS");
        r.setCallCount(0L);
        r.setCallCountMonth(0L);
        r.setCallCountWeek(0L);

        String controllerName = capitalize(DOMAINS[domIdx]) + "Controller";
        String methodName = RESOURCES[resIdx] + capitalize(DOMAINS[domIdx]);
        r.setControllerName(controllerName);
        r.setMethodName(methodName);
        r.setProgramId(String.format("MOCK%04d", seq % 10000));
        r.setApiOperationValue(DOMAIN_KO[domIdx] + " " + ACTION_KO[resIdx]);
        r.setDescriptionTag(DOMAIN_KO[domIdx] + " API");
        r.setControllerComment("/** Mock " + DOMAIN_KO[domIdx] + " 컨트롤러 */");
        r.setFullUrl("http://mock.local" + apiPath);
        r.setRepoPath("src/main/java/com/mock/" + DOMAINS[domIdx] + "/" + controllerName + ".java");
        r.setControllerFilePath("/" + repoName + "/" + r.getRepoPath());

        // 상태 분포: 사용 60 / 검토필요 15 / 최우선 10 / 후순위 8 / 차단완료 7
        int pick = rnd.nextInt(100);
        String status;
        String hasBlock = "N";
        String isDeprecated = "N";
        String blockTarget = null;
        String blockCriteria = null;
        String fullComment = null;
        LocalDate blockedDate = null;
        String blockedReason = null;
        boolean overridden = false;
        if (pick < 60) {
            status = "사용";
        } else if (pick < 75) {
            status = "검토필요 차단대상";
        } else if (pick < 85) {
            status = "최우선 차단대상";
        } else if (pick < 93) {
            status = "후순위 차단대상";
            blockTarget = "후순위 차단대상";
            blockCriteria = "(Mock) 수동 지정";
            overridden = true;
        } else {
            status = "차단완료";
            hasBlock = "Y";
            isDeprecated = "Y";
            blockedDate = LocalDate.now().minusDays(rnd.nextInt(200) + 10);
            blockedReason = "Mock 차단근거 (CSR-" + (90000 + rnd.nextInt(9999)) + ")";
            fullComment = "[URL차단작업][" + blockedDate + "][CSR-99999] Mock 차단";
        }
        r.setStatus(status);
        r.setStatusOverridden(overridden);
        r.setHasUrlBlock(hasBlock);
        r.setIsDeprecated(isDeprecated);
        r.setBlockTarget(blockTarget);
        r.setBlockCriteria(blockCriteria);
        r.setBlockedDate(blockedDate);
        r.setBlockedReason(blockedReason);
        r.setFullComment(fullComment);

        // git_history: 1~3개 커밋 (최근 2년 내 랜덤, 최우선 차단대상 후보는 1년 경과 커밋 보장)
        r.setGitHistory(buildGitHistoryJson(status, rnd));

        return r;
    }

    /** 랜덤 git_history JSON. '최우선 차단대상'은 1년 초과 커밋을 1건 이상 보장. */
    private String buildGitHistoryJson(String status, Random rnd) {
        int commits = 1 + rnd.nextInt(3); // 1~3개
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder("[");
        for (int c = 0; c < commits; c++) {
            if (c > 0) sb.append(",");
            int daysAgo;
            if ("최우선 차단대상".equals(status) && c == 0) {
                daysAgo = 366 + rnd.nextInt(365); // 1~2년 경과
            } else {
                daysAgo = rnd.nextInt(600);
            }
            LocalDate date = today.minusDays(daysAgo);
            String author = COMMIT_AUTHORS[rnd.nextInt(COMMIT_AUTHORS.length)];
            String msg = COMMIT_MESSAGES[rnd.nextInt(COMMIT_MESSAGES.length)];
            sb.append("{\"date\":\"").append(date).append("\",")
              .append("\"author\":\"").append(author).append("\",")
              .append("\"message\":\"").append(msg).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
