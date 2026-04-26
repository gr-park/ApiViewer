package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ApiStorageService {

    private static final Logger log = LoggerFactory.getLogger(ApiStorageService.class);

    // ── 상태값 상수 (DB 값 = 화면 라벨, 13 leaf) ────────────────────────────
    public static final String STATUS_USE        = "사용";
    public static final String STATUS_DELETED    = "삭제";
    /** (1)-(1) 차단완료 — hasUrlBlock=Y 자동 */
    public static final String S_1_1 = "(1)-(1) 차단완료";
    /** (1)-(2) 호출0건+변경없음 — 옛 '최우선 차단대상' + logWorkExcluded=false */
    public static final String S_1_2 = "(1)-(2) 호출0건+변경없음";
    /** (1)-(3) 호출0건+변경있음(로그) — 옛 '최우선 차단대상' + logWorkExcluded=true */
    public static final String S_1_3 = "(1)-(3) 호출0건+변경있음(로그)";
    /** (1)-(4) 업무종료 — 옛 '후순위 차단대상' (수동) */
    public static final String S_1_4 = "(1)-(4) 업무종료";
    /** (1)-(5) 현업요청 차단제외 — reviewResult='차단대상 제외' 자동 */
    public static final String S_1_5 = "(1)-(5) 현업요청 차단제외";
    /** (2)-(1) 호출0건+로그건 — 옛 '검토필요대상' + callZero + recentLogOnly=true */
    public static final String S_2_1 = "(2)-(1) 호출0건+로그건";
    /** (2)-(2) 호출0건+변경있음 — 옛 '검토필요대상' + callZero + recentLogOnly=false */
    public static final String S_2_2 = "(2)-(2) 호출0건+변경있음";
    /** (2)-(3) 호출 1~reviewThreshold건 — 옛 '검토필요대상' + 1~threshold */
    public static final String S_2_3 = "(2)-(3) 호출 1~reviewThreshold건";
    /** (2)-(4) 호출 reviewThreshold+1건↑ — 옛 '검토필요대상' + threshold+1~upper */
    public static final String S_2_4 = "(2)-(4) 호출 reviewThreshold+1건↑";

    /** 차단대상 umbrella 의 leaf 5종 (자동/수동 모두 포함) */
    public static final List<String> BLOCK_LEAVES = List.of(S_1_1, S_1_2, S_1_3, S_1_4, S_1_5);
    /** 추가검토대상 umbrella 의 leaf 4종 (자동) */
    public static final List<String> REVIEW_LEAVES = List.of(S_2_1, S_2_2, S_2_3, S_2_4);

    /**
     * 수동 판단 전용 상태값 — 자동 재계산(calculateStatus)에서 보존되며,
     * 일괄 수정 시 selecting 만으로 statusOverridden=true 가 자동 적용된다.
     * 모두 의미상 "사용" 계열이며, 차단대상에서 사용으로 전환된 건과 검토필요에서
     * 사용으로 전환된 건을 구분한다. (옵션A: 라벨 그대로 유지, 대시보드에서 (1)-(6)/(2)-(5) 컬럼으로 자동 분류)
     */
    public static final List<String> MANUAL_STATUSES = List.of(
            "차단대상 → 사용",
            "검토필요 → 사용"
    );

    private final ApiRecordRepository repository;
    private final GlobalConfigRepository globalConfigRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiStorageService(ApiRecordRepository repository,
                             GlobalConfigRepository globalConfigRepository,
                             RepoConfigRepository repoConfigRepository) {
        this.repository = repository;
        this.globalConfigRepository = globalConfigRepository;
        this.repoConfigRepository = repoConfigRepository;
    }

    /**
     * URL 차단 태그 인식 패턴 — 대괄호 안에 'URL' 과 '차단' 문자열이 모두 포함된 토큰.
     * 순서 무관, 사이 공백·추가 문자 허용 (오타·변형 표기 수용).
     * 예: [URL차단작업] / [URL 차단작업] / [URL차단완료] / [차단URL완료] 등 모두 매칭.
     */
    private static final String URL_BLOCK_TAG_REGEX =
            "\\[(?=[^\\[\\]]*URL)(?=[^\\[\\]]*차단)[^\\[\\]]+\\]";
    private static final Pattern URL_BLOCK_TAG_PATTERN = Pattern.compile(URL_BLOCK_TAG_REGEX);
    private static final Pattern BLOCKED_DATE_PATTERN =
            Pattern.compile(URL_BLOCK_TAG_REGEX + "\\[(\\d{4}-\\d{2}-\\d{2})\\]");
    private static final Pattern BLOCKED_REASON_PATTERN =
            Pattern.compile(URL_BLOCK_TAG_REGEX + "\\[\\d{4}-\\d{2}-\\d{2}\\]\\s*(.+)");

    /**
     * 추출된 API 목록을 DB에 저장합니다.
     * ① 신규: 전체 필드 세팅
     * ② 기존 + 차단완료: SKIP (건드리지 않음)
     * ③ 기존 + 차단완료 아님: 추출 필드만 업데이트, 수동 설정 필드 보호
     *
     * 대용량 최적화:
     *  - 레포 전체를 한 번만 로드 후 키(apiPath|httpMethod) → ApiRecord Map 구성 (findByRepoAndPathAndMethod N+1 제거)
     *  - 수집 후 saveAll() 벌크 저장 (JDBC batch_size 500 활용)
     */
    @Transactional
    /** @return [saved, revertedToUsed] — revertedToUsed: "차단대상→사용(차단대상 제외)" 전환 건수 */
    public int[] save(String repositoryName, List<ApiInfo> apis, String clientIp) {
        log.info("[DB 저장 시작] repo={}, 건수={}, ip={}", repositoryName, apis.size(), clientIp);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int reviewThreshold = getReviewThreshold();
        int reviewUpper = getReviewUpperThreshold();

        // 프로그램ID별 담당자 매핑 로드
        List<Map<String, String>> managerMappings = loadManagerMappings(repositoryName);

        // ── 레포 전체 1회 로드 + 키 맵 구성 (N+1 제거) ──
        List<ApiRecord> allInRepo = repository.findByRepositoryName(repositoryName);
        Map<String, ApiRecord> existingMap = new HashMap<>(allInRepo.size() * 2);
        for (ApiRecord r : allInRepo) {
            existingMap.put(r.getApiPath() + "|" + r.getHttpMethod(), r);
        }

        List<ApiRecord> toInsert = new ArrayList<>();
        List<ApiRecord> toUpdate = new ArrayList<>();
        int revertedToUsed = 0;
        // 파서가 동일 (apiPath|httpMethod) 를 중복 반환할 경우 두 번째부터 insert 목록에서 제외 (PK 에러 방지)
        Set<String> pendingInsertKeys = new HashSet<>();

        for (ApiInfo a : apis) {
            String key = a.getApiPath() + "|" + a.getHttpMethod();
            ApiRecord existing = existingMap.get(key);

            if (existing != null) {
                // ② 차단완료 → SKIP
                if (S_1_1.equals(existing.getStatus())) continue;

                // ③ 기존 + 차단완료 아님 → 추출 필드만 업데이트
                existing.setNew(false); // 재분석 시 신규 플래그 해제
                String oldStatus = existing.getStatus();
                updateExtractedFields(existing, a, now);
                applyManagerMapping(existing, managerMappings);

                if (!existing.isStatusOverridden()) {
                    String newStatus = calculateStatus(existing, reviewThreshold, reviewUpper);
                    if (!Objects.equals(oldStatus, newStatus)) {
                        if (!S_1_1.equals(newStatus)) {
                            String logMsg = oldStatus + " → " + newStatus;
                            if (S_1_5.equals(newStatus) && "차단대상 제외".equals(existing.getReviewResult())) {
                                logMsg += " (현업검토결과=차단대상 제외)";
                                revertedToUsed++;
                                log.info("[차단대상→(1)-(5) 전환] id={} repo={} path={}",
                                        existing.getId(), repositoryName, existing.getApiPath());
                            }
                            appendChangeLog(existing, logMsg);
                        }
                    }
                    existing.setStatus(newStatus);
                }
                if (S_1_1.equals(existing.getStatus())) {
                    existing.setBlockedDate(parseBlockedDate(existing.getFullComment()));
                    existing.setBlockedReason(parseBlockedReason(existing.getFullComment()));
                }
                toUpdate.add(existing);
            } else {
                // ① 신규 — 동일 key 가 이미 toInsert 대기 중이면 중복 skip (파서 중복값 방어)
                if (!pendingInsertKeys.add(key)) {
                    log.warn("[중복 URL 스킵] repo={} key={} — 어노테이션 배열에 동일 경로가 중복 선언됨", repositoryName, key);
                    continue;
                }
                ApiRecord r = new ApiRecord();
                r.setRepositoryName(repositoryName);
                r.setApiPath(a.getApiPath());
                r.setHttpMethod(a.getHttpMethod());
                r.setCreatedIp(clientIp);
                r.setNew(true);
                r.setDataSource("ANALYSIS");
                updateExtractedFields(r, a, now);
                applyManagerMapping(r, managerMappings);
                r.setStatus(calculateStatus(r, reviewThreshold, reviewUpper));
                if (S_1_1.equals(r.getStatus())) {
                    r.setBlockedDate(parseBlockedDate(r.getFullComment()));
                    r.setBlockedReason(parseBlockedReason(r.getFullComment()));
                }
                toInsert.add(r);
            }
        }

        // ④ DB에 있지만 추출 결과에 없는 건 → "삭제" 처리 (차단완료 제외)
        Set<String> extractedKeys = new HashSet<>(apis.size() * 2);
        for (ApiInfo a : apis) extractedKeys.add(a.getApiPath() + "|" + a.getHttpMethod());
        List<ApiRecord> toMarkDeleted = new ArrayList<>();
        for (ApiRecord r : allInRepo) {
            if (S_1_1.equals(r.getStatus()) || "삭제".equals(r.getStatus())) continue;
            String key = r.getApiPath() + "|" + r.getHttpMethod();
            if (!extractedKeys.contains(key)) {
                String oldStatus = r.getStatus();
                r.setStatus("삭제");
                r.setStatusOverridden(true);
                r.setStatusChanged(true);
                appendChangeLog(r, oldStatus + "→삭제: 재추출 시 소스에서 미발견");
                toMarkDeleted.add(r);
            }
        }

        // ── 벌크 저장 (batch_size=500 적용) ──
        if (!toInsert.isEmpty())      repository.saveAll(toInsert);
        if (!toUpdate.isEmpty())      repository.saveAll(toUpdate);
        if (!toMarkDeleted.isEmpty()) repository.saveAll(toMarkDeleted);

        int saved = toInsert.size() + toUpdate.size();
        log.info("[DB 저장 완료] repo={}, 신규={}, 갱신={}, 삭제표시={}, 차단→사용전환={}", repositoryName,
                toInsert.size(), toUpdate.size(), toMarkDeleted.size(), revertedToUsed);
        return new int[]{saved, revertedToUsed};
    }

    /** 추출로 갱신해도 되는 필드만 업데이트 (수동 설정 필드는 건드리지 않음) */
    private void updateExtractedFields(ApiRecord r, ApiInfo a, java.time.LocalDateTime now) {
        r.setLastAnalyzedAt(now);
        r.setMethodName(a.getMethodName());
        r.setControllerName(a.getControllerName());
        r.setRepoPath(a.getRepoPath());
        r.setIsDeprecated(a.getIsDeprecated());
        r.setHasUrlBlock(a.getHasUrlBlock());
        r.setBlockMarkingIncomplete(a.isBlockMarkingIncomplete());
        r.setProgramId(a.getProgramId());
        r.setApiOperationValue(a.getApiOperationValue());
        r.setDescriptionTag(a.getDescriptionTag());
        r.setFullComment(a.getFullComment());
        r.setControllerComment(a.getControllerComment());
        r.setRequestPropertyValue(a.getRequestPropertyValue());
        r.setControllerRequestPropertyValue(a.getControllerRequestPropertyValue());
        r.setFullUrl(a.getFullUrl());
        r.setGitHistory(serializeGitHistory(a));
        // controllerFilePath: /{repoName}/{repoPath}
        if (a.getRepoPath() != null && r.getRepositoryName() != null) {
            r.setControllerFilePath("/" + r.getRepositoryName() + "/" + a.getRepoPath());
        }
    }

    /** fullComment에서 [URL차단작업][YYYY-MM-DD] 패턴의 날짜 파싱 */
    private LocalDate parseBlockedDate(String fullComment) {
        if (fullComment == null) return null;
        Matcher m = BLOCKED_DATE_PATTERN.matcher(fullComment);
        return m.find() ? LocalDate.parse(m.group(1)) : null;
    }

    /** fullComment에서 차단근거 파싱: [URL차단작업][YYYY-MM-DD] 뒤의 텍스트를 그대로 저장 */
    private String parseBlockedReason(String fullComment) {
        if (fullComment == null) return null;
        Matcher m = BLOCKED_REASON_PATTERN.matcher(fullComment);
        if (!m.find()) return null;
        String rest = m.group(1).trim();
        return rest.isEmpty() ? null : rest;
    }

    /** 상태 변경 로그 추가 (기존 로그에 이어붙임) */
    /** 레포설정의 managerMappings JSON 파싱 */
    private List<Map<String, String>> loadManagerMappings(String repositoryName) {
        try {
            Optional<RepoConfig> opt = repoConfigRepository.findByRepoName(repositoryName);
            if (opt.isPresent() && opt.get().getManagerMappings() != null) {
                String json = opt.get().getManagerMappings().trim();
                if (json.startsWith("[")) {
                    return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
                }
            }
        } catch (Exception e) {
            log.warn("[매핑 로드 실패] repo={}: {}", repositoryName, e.getMessage());
        }
        return List.of();
    }

    /** 프로그램ID 매핑 → managerOverride 자동 설정.
     *  managerOverridden=true(사용자가 UI에서 수동 지정)인 레코드는 건드리지 않는다.
     *  매핑에 매칭되지 않는 경우도 기존 값 유지 (자동 설정값 포함). */
    private void applyManagerMapping(ApiRecord r, List<Map<String, String>> mappings) {
        if (mappings.isEmpty()) return;
        if (r.isManagerOverridden()) return; // 수동 지정 보호
        String apiPath = r.getApiPath();
        if (apiPath == null) return;
        String pathUpper = apiPath.toUpperCase();
        for (Map<String, String> m : mappings) {
            String pid = m.get("programId");
            String mgr = m.get("managerName");
            if (pid != null && !pid.isBlank() && mgr != null && !mgr.isBlank()) {
                if (pathUpper.contains(pid.toUpperCase())) {
                    r.setManagerOverride(mgr);
                    return;
                }
            }
        }
        // 매핑에 해당하지 않으면 기존 managerOverride 유지
    }

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
        log.info("[호출건수 반영] repo={}, 매핑건수={}", repoName, pathToCount.size());
        int reviewThreshold = getReviewThreshold();
        int reviewUpper = getReviewUpperThreshold();
        List<ApiRecord> records = repository.findByRepositoryName(repoName);

        for (ApiRecord r : records) {
            // 차단완료 SKIP
            if (S_1_1.equals(r.getStatus())) continue;

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
                String newStatus = calculateStatus(r, reviewThreshold, reviewUpper);
                if (!Objects.equals(oldStatus, newStatus)) {
                    appendChangeLog(r, oldStatus + "→" + newStatus + ": 호출건수 반영 시 상태 변경");
                }
                r.setStatus(newStatus);
            }
            repository.save(r);
        }
        log.info("[호출건수 반영 완료] repo={}, 처리 레코드={}건", repoName, records.size());
    }

    /**
     * 상태/차단대상/차단대상기준을 수동으로 일괄 변경합니다.
     * - status: null이면 자동 계산 복원, 값이면 수동 설정
     * - blockTarget: "__CLEAR__"이면 해제(null), 값이면 설정
     * - blockCriteria: "__CLEAR__"이면 해제(null), 값이면 설정
     * - updateStatus=false: status 변경 안 함, updateBlock=false: 차단대상 변경 안 함
     */
    @Transactional
    public int updateBulk(List<Long> ids, Map<String, Object> fields, String clientIp) {
        log.info("[일괄 변경] 대상={}건, 필드={}, ip={}", ids.size(), fields.keySet(), clientIp);
        int reviewThreshold = getReviewThreshold();
        int reviewUpper = getReviewUpperThreshold();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int updated = 0;

        // 대용량 최적화: ID 청크로 findAllById 로 일괄 로드 후 saveAll 로 일괄 저장
        // (findById N회 × save N회 = 2N 쿼리 → 2회 쿼리 + 배치 INSERT)
        final int CHUNK = 500;
        for (int i = 0; i < ids.size(); i += CHUNK) {
            List<Long> chunk = ids.subList(i, Math.min(i + CHUNK, ids.size()));
            List<ApiRecord> records = repository.findAllById(chunk);
            List<ApiRecord> dirty = new ArrayList<>(records.size());

            for (ApiRecord r : records) {
                // 확정완료(statusOverridden=true) 건은 상태확정 해제만 허용, 나머지 모두 수정 가능
                if (r.isStatusOverridden()) {
                    if (fields.containsKey("statusOverridden")) {
                        Object val = fields.get("statusOverridden");
                        boolean locked = val instanceof Boolean ? (Boolean) val : "true".equals(String.valueOf(val));
                        r.setStatusOverridden(locked);
                        r.setModifiedAt(now);
                        if (clientIp != null) r.setModifiedIp(clientIp);
                        dirty.add(r);
                        updated++;
                    }
                    continue;
                }

                if (fields.containsKey("status")) {
                    String status = fields.get("status") != null ? fields.get("status").toString() : null;
                    if (status == null || status.isBlank()) {
                        r.setStatusOverridden(false);
                        r.setStatus(calculateStatus(r, reviewThreshold, reviewUpper));
                    } else {
                        r.setStatus(status);
                        // 수동 판단 상태는 자동 재계산되지 않도록 statusOverridden 자동 ON
                        if (MANUAL_STATUSES.contains(status)) {
                            r.setStatusOverridden(true);
                        }
                    }
                }

                // 상태확정 토글 (statusOverridden 직접 설정)
                if (fields.containsKey("statusOverridden")) {
                    Object val = fields.get("statusOverridden");
                    boolean locked = val instanceof Boolean ? (Boolean) val : "true".equals(String.valueOf(val));
                    r.setStatusOverridden(locked);
                }

                if (fields.containsKey("blockTarget"))
                    r.setBlockTarget(toNullableStr(fields.get("blockTarget")));
                if (fields.containsKey("blockCriteria"))
                    r.setBlockCriteria(toNullableStr(fields.get("blockCriteria")));
                if (fields.containsKey("teamOverride"))
                    r.setTeamOverride(toNullableStr(fields.get("teamOverride")));
                if (fields.containsKey("managerOverride")) {
                    String mgrVal = toNullableStr(fields.get("managerOverride"));
                    r.setManagerOverride(mgrVal);
                    // 값 설정 시 수동 플래그 ON, 비움 시 OFF (매핑 재갱신 허용)
                    r.setManagerOverridden(mgrVal != null);
                }

                boolean reviewChanged = false;
                if (fields.containsKey("reviewResult"))  { r.setReviewResult(toNullableStr(fields.get("reviewResult"))); reviewChanged = true; }
                if (fields.containsKey("reviewOpinion")) { r.setReviewOpinion(toNullableStr(fields.get("reviewOpinion"))); reviewChanged = true; }
                if (fields.containsKey("reviewTeam"))    { r.setReviewTeam(toNullableStr(fields.get("reviewTeam"))); reviewChanged = true; }
                if (fields.containsKey("reviewManager")) { r.setReviewManager(toNullableStr(fields.get("reviewManager"))); reviewChanged = true; }
                if (reviewChanged) r.setReviewedAt(now);

                // CBO/배포 일정 + 배포담당자 — 빈 문자열은 null 로 clear
                if (fields.containsKey("cboScheduledDate"))    r.setCboScheduledDate(toNullableDate(fields.get("cboScheduledDate")));
                if (fields.containsKey("deployScheduledDate")) r.setDeployScheduledDate(toNullableDate(fields.get("deployScheduledDate")));
                if (fields.containsKey("deployManager"))       r.setDeployManager(toNullableStr(fields.get("deployManager")));

                r.setModifiedAt(now);
                if (clientIp != null) r.setModifiedIp(clientIp);
                dirty.add(r);
                updated++;
            }
            if (!dirty.isEmpty()) repository.saveAll(dirty);
        }
        log.info("[일괄 변경 완료] 대상={}건, 변경={}건", ids.size(), updated);
        return updated;
    }

    private String toNullableStr(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** ISO yyyy-MM-dd 문자열 → LocalDate. 빈값/null/parse 실패 시 null. */
    private LocalDate toNullableDate(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return LocalDate.parse(s); }
        catch (Exception e) {
            log.warn("[일괄 변경] 날짜 파싱 실패: '{}' — null 처리", s);
            return null;
        }
    }

    /** 기존 호환용 — 상태만 변경 */
    @Transactional
    public int updateStatus(List<Long> ids, String status) {
        return updateBulk(ids, Map.of("status", status != null ? status : ""), null);
    }

    // ── 상태 계산 ────────────────────────────────────────────────────────────

    /** 기존 호환 — reviewUpperThreshold 미지정 시 reviewThreshold 와 동일하게 동작 */
    String calculateStatus(ApiRecord r, int reviewThreshold) {
        return calculateStatus(r, reviewThreshold, reviewThreshold);
    }

    /**
     * 자동 상태 판정 (umbrella sticky 규칙).
     *
     * 규칙:
     *   1. MANUAL_STATUSES 보존 (수동 → 사용)
     *   2. hasUrlBlock=Y → (1)-(1) 차단완료 (강한 신호, umbrella 무시)
     *   3. reviewResult='차단대상 제외' → (1)-(5) 현업요청 차단제외 (강한 신호)
     *   4. 현재 status가 BLOCK_LEAVES 또는 REVIEW_LEAVES umbrella 내:
     *        - 조건이 '사용'을 가리키면 → '사용'
     *        - 그 외에는 현재 leaf 보존 (차단↔검토 전이 / leaf 변경 없음)
     *   5. 현재 status가 사용/null/'차단완료' 등 umbrella 외부:
     *        - 조건에 따라 leaf 자유 할당
     */
    String calculateStatus(ApiRecord r, int reviewThreshold, int reviewUpperThreshold) {
        // 1. 수동 판단 상태 보존
        if (MANUAL_STATUSES.contains(r.getStatus())) {
            return r.getStatus();
        }
        // 2. 차단완료 — hasUrlBlock 자동
        if ("Y".equals(r.getHasUrlBlock())) {
            r.setLogWorkExcluded(false);
            r.setRecentLogOnly(false);
            return S_1_1;
        }
        // 3. 현업요청 차단제외 — reviewResult 자동
        if ("차단대상 제외".equals(r.getReviewResult())) {
            r.setLogWorkExcluded(false);
            r.setRecentLogOnly(false);
            return S_1_5;
        }

        // 조건 평가
        Long call = r.getCallCount();
        boolean callZero = (call == null || call == 0);
        int upper = Math.max(reviewThreshold, reviewUpperThreshold);
        boolean callMid  = (call != null && call >= 1 && call <= upper);
        boolean fullOld  = areAllCommitsOlderThanOneYear(r.getGitHistory(), false);
        boolean bizOld   = areAllCommitsOlderThanOneYear(r.getGitHistory(), true);
        // target umbrella 결정
        Umbrella target;
        if (callZero && (fullOld || bizOld)) target = Umbrella.BLOCK;
        else if ((callZero && !fullOld) || (callMid && fullOld)) target = Umbrella.REVIEW;
        else target = Umbrella.USE;

        // 4. umbrella sticky — 현재가 차단대상/추가검토대상 leaf 면 USE 전이만 허용
        String current = r.getStatus();
        boolean inBlock  = BLOCK_LEAVES.contains(current);
        boolean inReview = REVIEW_LEAVES.contains(current);
        if (inBlock || inReview) {
            if (target == Umbrella.USE) {
                r.setLogWorkExcluded(false);
                r.setRecentLogOnly(false);
                return STATUS_USE;
            }
            // umbrella 내부 보존 — leaf 변경 없음, 보조 플래그도 안 갱신
            return current;
        }

        // 5. 현재 사용/차단완료/null 등 외부 → 자유 할당
        return assignLeaf(target, r, callZero, fullOld, bizOld, call, reviewThreshold);
    }

    /** 외부 → umbrella 첫 진입 시 leaf 결정 + 보조 플래그 갱신 */
    private String assignLeaf(Umbrella target, ApiRecord r,
                              boolean callZero, boolean fullOld, boolean bizOld,
                              Long call, int reviewThreshold) {
        switch (target) {
            case BLOCK -> {
                if (callZero && fullOld) {
                    r.setLogWorkExcluded(false);
                    r.setRecentLogOnly(false);
                    return S_1_2; // 호출0건+변경없음
                }
                // callZero && bizOld (logWork 제외 시 1년 경과)
                r.setLogWorkExcluded(true);
                r.setRecentLogOnly(false);
                return S_1_3; // 호출0건+변경있음(로그)
            }
            case REVIEW -> {
                r.setLogWorkExcluded(false);
                if (callZero) {
                    boolean logOnly = recentCommitsAllLogOnly(r.getGitHistory());
                    r.setRecentLogOnly(logOnly);
                    return logOnly ? S_2_1 : S_2_2;
                }
                r.setRecentLogOnly(false);
                if (call != null && call <= reviewThreshold) return S_2_3;
                return S_2_4;
            }
            case USE -> {
                r.setLogWorkExcluded(false);
                r.setRecentLogOnly(false);
                return STATUS_USE;
            }
        }
        return STATUS_USE;
    }

    private enum Umbrella { USE, BLOCK, REVIEW }

    /**
     * git_history JSON 에서 1년 미만 커밋만 추려, 전부 로그성("로그"/"불필요" 키워드) 메시지인지 판정.
     * - 1년 미만 커밋이 0건이면 false (분류 의미 없음).
     * - 모든 1년 미만 커밋 메시지가 "로그" 또는 "불필요" 포함 → true.
     * - 한 건이라도 비-로그성 메시지가 있으면 false.
     */
    boolean recentCommitsAllLogOnly(String gitHistoryJson) {
        if (gitHistoryJson == null || gitHistoryJson.isBlank() || "[]".equals(gitHistoryJson.trim())) return false;
        try {
            List<Map<String, String>> commits = objectMapper.readValue(
                    gitHistoryJson, new TypeReference<>() {});
            if (commits.isEmpty()) return false;
            LocalDate oneYearAgo = LocalDate.now().minusYears(1);
            int recentCount = 0;
            for (Map<String, String> c : commits) {
                String dateStr = c.get("date");
                if (dateStr == null || dateStr.isBlank()) continue;
                LocalDate d;
                try { d = LocalDate.parse(dateStr.length() >= 10 ? dateStr.substring(0, 10) : dateStr); }
                catch (Exception e) { continue; }
                if (d.isBefore(oneYearAgo)) continue;
                recentCount++;
                String msg = c.get("message");
                if (msg == null) msg = "";
                boolean isLogish = msg.contains("로그") || msg.contains("불필요");
                if (!isLogish) return false;  // 비-로그성 1건 발견 → 즉시 false
            }
            return recentCount > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 패키지 공개 — ApiExtractorService 등에서도 동일 판정을 쓰도록 */
    static boolean containsUrlBlockTag(String text) {
        return text != null && URL_BLOCK_TAG_PATTERN.matcher(text).find();
    }

    private boolean containsBlockText(String fullComment) {
        return containsUrlBlockTag(fullComment);
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

    private int getReviewUpperThreshold() {
        return globalConfigRepository.findById(1L)
                .map(GlobalConfig::getReviewUpperThreshold)
                .orElse(10);
    }
}
