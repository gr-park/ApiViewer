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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * 추출된 API 목록을 DB에 upsert합니다.
     * - (repositoryName, apiPath, httpMethod) 기준으로 기존 레코드 검색
     * - 없으면 신규 삽입, 있으면 업데이트
     * - statusOverridden=true인 레코드는 status를 변경하지 않고, 그 외는 자동 계산
     */
    @Transactional
    public int save(String repositoryName, List<ApiInfo> apis) {
        LocalDate today = LocalDate.now();
        int reviewThreshold = getReviewThreshold();

        for (ApiInfo a : apis) {
            Optional<ApiRecord> existing = repository.findByRepositoryNameAndApiPathAndHttpMethod(
                    repositoryName, a.getApiPath(), a.getHttpMethod());

            ApiRecord r = existing.orElse(new ApiRecord());

            r.setRepositoryName(repositoryName);
            r.setApiPath(a.getApiPath());
            r.setHttpMethod(a.getHttpMethod());
            r.setLastAnalyzedDate(today);
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

            // statusOverridden=true이면 status 유지, 아니면 자동 계산
            if (!r.isStatusOverridden()) {
                r.setStatus(calculateStatus(r, reviewThreshold));
            }

            repository.save(r);
        }
        return apis.size();
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
            Long count = pathToCount.get(r.getApiPath());
            if (count != null) {
                r.setCallCount(count);
            }
            if (!r.isStatusOverridden()) {
                r.setStatus(calculateStatus(r, reviewThreshold));
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
    public int updateBulk(List<Long> ids, String status, boolean updateStatus,
                          String blockTarget, String blockCriteria, boolean updateBlock) {
        int reviewThreshold = getReviewThreshold();
        int updated = 0;
        for (Long id : ids) {
            Optional<ApiRecord> opt = repository.findById(id);
            if (opt.isEmpty()) continue;
            ApiRecord r = opt.get();

            if (updateStatus) {
                if (status == null || status.isBlank()) {
                    r.setStatusOverridden(false);
                    r.setStatus(calculateStatus(r, reviewThreshold));
                } else {
                    r.setStatus(status);
                    r.setStatusOverridden(true);
                }
            }

            if (updateBlock) {
                r.setBlockTarget(blockTarget);
                r.setBlockCriteria(blockCriteria);
            }

            repository.save(r);
            updated++;
        }
        return updated;
    }

    /** 기존 호환용 — 상태만 변경 */
    @Transactional
    public int updateStatus(List<Long> ids, String status) {
        return updateBulk(ids, status, true, null, null, false);
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

    private boolean areAllCommitsOlderThanOneYear(String gitHistoryJson) {
        if (gitHistoryJson == null || gitHistoryJson.isBlank() || "[]".equals(gitHistoryJson.trim())) {
            return false; // 이력 없으면 판단 불가 → false
        }
        try {
            List<Map<String, String>> commits = objectMapper.readValue(
                    gitHistoryJson, new TypeReference<>() {});
            if (commits.isEmpty()) return false;
            LocalDate oneYearAgo = LocalDate.now().minusYears(1);
            for (Map<String, String> c : commits) {
                String dateStr = c.get("date");
                if (dateStr == null || dateStr.isBlank()) continue;
                LocalDate commitDate = LocalDate.parse(dateStr.trim());
                if (!commitDate.isBefore(oneYearAgo)) return false; // 1년 이내 커밋 있음
            }
            return true; // 모든 커밋이 1년 초과
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
