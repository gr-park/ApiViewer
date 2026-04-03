package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ApiStorageService {

    private final ApiRecordRepository repository;
    private final GlobalConfigRepository globalConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiStorageService(ApiRecordRepository repository,
                             GlobalConfigRepository globalConfigRepository) {
        this.repository = repository;
        this.globalConfigRepository = globalConfigRepository;
    }

    private static final Pattern BLOCKED_DATE_PATTERN =
            Pattern.compile("\\[URL차단작업\\]\\[(\\d{4}-\\d{2}-\\d{2})\\]");
    private static final Pattern BLOCKED_REASON_PATTERN =
            Pattern.compile("\\[URL차단작업\\]\\[\\d{4}-\\d{2}-\\d{2}\\]\\s*(.+)");

    /**
     * 추출된 API 목록을 DB에 저장합니다.
     * ① 신규: 전체 필드 세팅
     * ② 기존 + 차단완료: SKIP (건드리지 않음)
     * ③ 기존 + 차단완료 아님: 추출 필드만 업데이트, 수동 설정 필드 보호
     */
    @Transactional
    public int save(String repositoryName, List<ApiInfo> apis, String clientIp) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int reviewThreshold = getReviewThreshold();
        int saved = 0;

        for (ApiInfo a : apis) {
            Optional<ApiRecord> existing = repository.findByRepositoryNameAndApiPathAndHttpMethod(
                    repositoryName, a.getApiPath(), a.getHttpMethod());

            if (existing.isPresent()) {
                ApiRecord r = existing.get();

                // ② 차단완료 → SKIP
                if ("차단완료".equals(r.getStatus())) continue;

                // ③ 기존 + 차단완료 아님 → 추출 필드만 업데이트
                String oldStatus = r.getStatus();
                updateExtractedFields(r, a, now);

                // status 재계산 + 변경 감지
                if (!r.isStatusOverridden()) {
                    String newStatus = calculateStatus(r, reviewThreshold);
                    if (!Objects.equals(oldStatus, newStatus)) {
                        appendChangeLog(r, oldStatus + "→" + newStatus + ": 재추출 시 상태 변경 감지");
                    }
                    r.setStatus(newStatus);
                }
                // 차단완료로 변경되었으면 blockedDate 파싱
                if ("차단완료".equals(r.getStatus())) {
                    r.setBlockedDate(parseBlockedDate(r.getFullComment()));
                    r.setBlockedReason(parseBlockedReason(r.getFullComment()));
                }

            } else {
                // ① 신규
                ApiRecord r = new ApiRecord();
                r.setRepositoryName(repositoryName);
                r.setApiPath(a.getApiPath());
                r.setHttpMethod(a.getHttpMethod());
                r.setCreatedIp(clientIp);
                updateExtractedFields(r, a, now);
                r.setStatus(calculateStatus(r, reviewThreshold));
                if ("차단완료".equals(r.getStatus())) {
                    r.setBlockedDate(parseBlockedDate(r.getFullComment()));
                    r.setBlockedReason(parseBlockedReason(r.getFullComment()));
                }
                repository.save(r);
                saved++;
                continue;
            }

            repository.save(existing.get());
            saved++;
        }

        // ④ DB에 있지만 추출 결과에 없는 건 → "삭제" 처리 (차단완료 제외)
        Set<String> extractedKeys = apis.stream()
                .map(a -> a.getApiPath() + "|" + a.getHttpMethod())
                .collect(java.util.stream.Collectors.toSet());
        List<ApiRecord> allInRepo = repository.findByRepositoryName(repositoryName);
        for (ApiRecord r : allInRepo) {
            if ("차단완료".equals(r.getStatus()) || "삭제".equals(r.getStatus())) continue;
            String key = r.getApiPath() + "|" + r.getHttpMethod();
            if (!extractedKeys.contains(key)) {
                String oldStatus = r.getStatus();
                r.setStatus("삭제");
                r.setStatusOverridden(true);
                appendChangeLog(r, oldStatus + "→삭제: 재추출 시 소스에서 미발견");
            }
        }

        return saved;
    }

    /** 추출로 갱신해도 되는 필드만 업데이트 (수동 설정 필드는 건드리지 않음) */
    private void updateExtractedFields(ApiRecord r, ApiInfo a, java.time.LocalDateTime now) {
        r.setLastAnalyzedAt(now);
        r.setMethodName(a.getMethodName());
        r.setControllerName(a.getControllerName());
        r.setRepoPath(a.getRepoPath());
        r.setIsDeprecated(a.getIsDeprecated());
        r.setProgramId(a.getProgramId());
        r.setApiOperationValue(a.getApiOperationValue());
        r.setDescriptionTag(a.getDescriptionTag());
        r.setFullComment(a.getFullComment());
        r.setControllerComment(a.getControllerComment());
        r.setRequestPropertyValue(a.getRequestPropertyValue());
        r.setControllerRequestPropertyValue(a.getControllerRequestPropertyValue());
        r.setFullUrl(a.getFullUrl());
        r.setGitHistory(serializeGitHistory(a));
    }

    /** fullComment에서 [URL차단작업][YYYY-MM-DD] 패턴의 날짜 파싱 */
    private LocalDate parseBlockedDate(String fullComment) {
        if (fullComment == null) return null;
        Matcher m = BLOCKED_DATE_PATTERN.matcher(fullComment);
        return m.find() ? LocalDate.parse(m.group(1)) : null;
    }

    /** fullComment에서 차단근거 파싱: [URL차단작업][YYYY-MM-DD] 뒤의 텍스트 */
    private String parseBlockedReason(String fullComment) {
        if (fullComment == null) return null;
        Matcher m = BLOCKED_REASON_PATTERN.matcher(fullComment);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 상태 변경 로그 추가 (기존 로그에 이어붙임) */
    private void appendChangeLog(ApiRecord r, String msg) {
        r.setStatusChanged(true);
        String existing = r.getStatusChangeLog();
        r.setStatusChangeLog(existing != null ? existing + " | " + msg : msg);
    }

    /**
     * Whatap 호출건수를 DB에 반영하고 상태를 재계산합니다.
     * statusOverridden=true인 레코드는 상태 변경하지 않습니다.
     *
     * @param repoName      레포지토리명
     * @param pathToCount   apiPath → callCount 매핑
     */
    @Transactional
    public void updateCallCounts(String repoName, Map<String, Long> pathToCount) {
        int reviewThreshold = getReviewThreshold();
        List<ApiRecord> records = repository.findByRepositoryName(repoName);

        for (ApiRecord r : records) {
            // 차단완료 SKIP
            if ("차단완료".equals(r.getStatus())) continue;

            Long newCount = pathToCount.get(r.getApiPath());
            if (newCount != null) {
                Long oldCount = r.getCallCount();
                r.setCallCount(newCount);

                // 호출건수 0↔N 변화 감지
                boolean wasZero = (oldCount == null || oldCount == 0);
                boolean isZero  = (newCount == 0);
                if (wasZero && !isZero) {
                    appendChangeLog(r, "호출건수 0→" + newCount + "건 발생");
                } else if (!wasZero && isZero) {
                    appendChangeLog(r, "호출건수 " + oldCount + "→0건 변경");
                }
            }

            if (!r.isStatusOverridden()) {
                String oldStatus = r.getStatus();
                String newStatus = calculateStatus(r, reviewThreshold);
                if (!Objects.equals(oldStatus, newStatus)) {
                    appendChangeLog(r, oldStatus + "→" + newStatus + ": 호출건수 반영 시 상태 변경");
                }
                r.setStatus(newStatus);
            }
            repository.save(r);
        }
    }

    /**
     * 상태/차단대상/차단대상기준을 수동으로 일괄 변경합니다.
     * - status: null이면 자동 계산 복원, 값이면 수동 설정
     * - blockTarget: "__CLEAR__"이면 해제(null), 값이면 설정
     * - blockCriteria: "__CLEAR__"이면 해제(null), 값이면 설정
     * - updateStatus=false: status 변경 안 함, updateBlock=false: 차단대상 변경 안 함
     */
    @Transactional
    public int updateBulk(List<Long> ids, Map<String, Object> fields) {
        int reviewThreshold = getReviewThreshold();
        int updated = 0;
        for (Long id : ids) {
            Optional<ApiRecord> opt = repository.findById(id);
            if (opt.isEmpty()) continue;
            ApiRecord r = opt.get();

            if (fields.containsKey("status")) {
                String status = fields.get("status") != null ? fields.get("status").toString() : null;
                if (status == null || status.isBlank()) {
                    r.setStatusOverridden(false);
                    r.setStatus(calculateStatus(r, reviewThreshold));
                } else {
                    r.setStatus(status);
                    r.setStatusOverridden(true);
                }
            }

            if (fields.containsKey("blockTarget"))
                r.setBlockTarget(toNullableStr(fields.get("blockTarget")));
            if (fields.containsKey("blockCriteria"))
                r.setBlockCriteria(toNullableStr(fields.get("blockCriteria")));

            boolean reviewChanged = false;
            if (fields.containsKey("reviewResult"))  { r.setReviewResult(toNullableStr(fields.get("reviewResult"))); reviewChanged = true; }
            if (fields.containsKey("reviewOpinion")) { r.setReviewOpinion(toNullableStr(fields.get("reviewOpinion"))); reviewChanged = true; }
            if (fields.containsKey("reviewTeam"))    { r.setReviewTeam(toNullableStr(fields.get("reviewTeam"))); reviewChanged = true; }
            if (fields.containsKey("reviewManager")) { r.setReviewManager(toNullableStr(fields.get("reviewManager"))); reviewChanged = true; }
            if (reviewChanged) r.setReviewedAt(java.time.LocalDateTime.now());

            repository.save(r);
            updated++;
        }
        return updated;
    }

    private String toNullableStr(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 기존 호환용 — 상태만 변경 */
    @Transactional
    public int updateStatus(List<Long> ids, String status) {
        return updateBulk(ids, Map.of("status", status != null ? status : ""));
    }

    // ── 상태 계산 ────────────────────────────────────────────────────────────

    String calculateStatus(ApiRecord r, int reviewThreshold) {
        // 차단완료: fullComment에 [URL차단작업] 포함 AND @Deprecated 어노테이션 있음
        if ("Y".equals(r.getIsDeprecated()) && containsBlockText(r.getFullComment())) {
            return "차단완료";
        }
        return "사용";
    }

    private boolean containsBlockText(String fullComment) {
        if (fullComment == null) return false;
        return fullComment.contains("[URL차단작업]");
    }

    /**
     * 모든 커밋이 1년 초과인지 판단 (excludeNonBiz=false: 전체 커밋 대상)
     */
    private boolean areAllCommitsOlderThanOneYear(String gitHistoryJson) {
        return areAllCommitsOlderThanOneYear(gitHistoryJson, false);
    }

    /**
     * 모든 커밋이 1년 초과인지 판단
     * @param excludeNonBiz true이면 커밋 메시지에 "불필요" 또는 "로그"가 포함된 커밋은 제외 (침해사고 로그 등 비즈니스 무관 커밋)
     */
    private boolean areAllCommitsOlderThanOneYear(String gitHistoryJson, boolean excludeNonBiz) {
        if (gitHistoryJson == null || gitHistoryJson.isBlank() || "[]".equals(gitHistoryJson.trim())) {
            return false;
        }
        try {
            List<Map<String, String>> commits = objectMapper.readValue(
                    gitHistoryJson, new TypeReference<>() {});
            if (commits.isEmpty()) return false;
            LocalDate oneYearAgo = LocalDate.now().minusYears(1);
            boolean hasValidCommit = false;
            for (Map<String, String> c : commits) {
                String dateStr = c.get("date");
                if (dateStr == null || dateStr.isBlank()) continue;

                // 비즈니스 무관 커밋 제외 (침해사고 로그 등)
                if (excludeNonBiz) {
                    String msg = c.get("message");
                    if (msg != null && (msg.contains("불필요") || msg.contains("로그"))) continue;
                }

                hasValidCommit = true;
                LocalDate commitDate = LocalDate.parse(dateStr.trim());
                if (!commitDate.isBefore(oneYearAgo)) return false;
            }
            return hasValidCommit; // 유효한 커밋이 있고 모두 1년 초과
        } catch (Exception e) {
            return false;
        }
    }

    // ── 직렬화 헬퍼 ─────────────────────────────────────────────────────────

    private String serializeGitHistory(ApiInfo a) {
        List<String[]> gits = Arrays.asList(
                a.getGit1(), a.getGit2(), a.getGit3(), a.getGit4(), a.getGit5());
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String[] g : gits) {
            if (g == null || "-".equals(g[0])) continue;
            if (!first) sb.append(",");
            sb.append("{\"date\":\"").append(escJson(g[0]))
              .append("\",\"author\":\"").append(escJson(g[1]))
              .append("\",\"message\":\"").append(escJson(g[2]))
              .append("\"}");
            first = false;
        }
        return sb.append("]").toString();
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "");
    }

    private int getReviewThreshold() {
        return globalConfigRepository.findById(1L)
                .map(GlobalConfig::getReviewThreshold)
                .orElse(3);
    }
}
