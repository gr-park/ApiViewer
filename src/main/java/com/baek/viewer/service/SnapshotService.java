package com.baek.viewer.service;

import com.baek.viewer.config.SnapshotProperties;
import com.baek.viewer.model.ApiRecordSnapshot;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.ApiRecordSnapshotRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final EntityManager em;
    private final ApiRecordSnapshotRepository snapshotRepository;
    private final GlobalConfigRepository globalConfigRepository;
    private final SnapshotProperties snapshotProperties;

    public SnapshotService(EntityManager em,
                           ApiRecordSnapshotRepository snapshotRepository,
                           GlobalConfigRepository globalConfigRepository,
                           SnapshotProperties snapshotProperties) {
        this.em = em;
        this.snapshotRepository = snapshotRepository;
        this.globalConfigRepository = globalConfigRepository;
        this.snapshotProperties = snapshotProperties;
    }

    @Transactional
    public ApiRecordSnapshot createSnapshot(String snapshotType, String label, String sourceRepo, String clientIp) {
        // [정책] 스냅샷은 항상 '시점 기준 전체(풀)'로 생성한다.
        // - sourceRepo 파라미터는 호출 호환을 위해 유지하되, DB의 sourceRepo 컬럼에는 저장하지 않는다(=NULL).
        // - 호출자가 넘긴 repoName은 라벨로만 추적성 보존(예: "Extract <repo> @ <시각>").
        String triggerRepo = (sourceRepo != null && !sourceRepo.isBlank()) ? sourceRepo.trim() : null;
        ApiRecordSnapshot s = new ApiRecordSnapshot();
        s.setSnapshotAt(LocalDateTime.now());
        s.setSnapshotType(snapshotType);
        s.setLabel(label);
        s.setSourceRepo(null);
        s.setCreatedIp(clientIp);
        s.setRecordCount(0L);
        s = snapshotRepository.save(s);

        long snapshotId = s.getId();
        log.info("[SNAPSHOT] 생성 시작: id={}, type={}, triggerRepo={}, sourceRepo=null(정책: 항상 전체)",
                snapshotId, snapshotType, triggerRepo);

        // 1) row 복제 — api_record 전 행(status='삭제' 포함)
        int inserted = insertRowsFromApiRecord(snapshotId, null);

        // 2) 메타 업데이트
        s.setRecordCount((long) inserted);
        s = snapshotRepository.save(s);
        log.info("[SNAPSHOT] 생성 완료: id={}, rows={}", snapshotId, inserted);

        // 생성 직후 보관주기 초과 스냅샷 정리 (실패해도 스냅샷 생성엔 영향 없음)
        try {
            cleanupOldSnapshots(null);
        } catch (Exception e) {
            log.warn("[SNAPSHOT] cleanup 실패(무시): {}", e.getMessage());
        }
        return s;
    }

    /**
     * snapshot_row에 api_record를 행 단위로 복제.
     * [정책] 항상 전체 풀 복제 — status='삭제' 포함(repoFilter는 더 이상 사용하지 않음 — 시그니처는 하위 호환을 위해 보존).
     */
    private int insertRowsFromApiRecord(long snapshotId, String repoFilter) {
        // 컬럼은 api_record와 동일(거의 전체) + snapshot_id/source_id만 추가
        String sql = """
            INSERT INTO api_record_snapshot_row (
              snapshot_id, source_id,
              repository_name, api_path, http_method,
              last_analyzed_at, created_ip, modified_at, modified_ip, reviewed_ip,
              status, auto_analyzed_status, status_overridden, log_work_excluded, recent_log_only, test_suspect_reason, path_param_pattern,
              block_target, block_criteria, call_count, call_count_month, call_count_week,
              method_name, controller_name, repo_path, is_deprecated, has_url_block, block_marking_incomplete, related_menu_deficient,
              program_id, api_operation_value, description_tag, full_comment, controller_comment,
              request_property_value, controller_request_property_value, full_url, controller_file_path,
              memo, review_result, review_opinion, cbo_scheduled_date, deploy_scheduled_date,
              deploy_csr, deploy_manager, review_team, review_manager, reviewed_at,
              blocked_date, blocked_reason, status_changed, status_change_log, is_new, data_source,
              team_override, manager_override, manager_overridden, description_override, description_override_source, git_history,
              last_git_commit_date,
              review_stage, internal_reviewer, internal_reviewed_at, internal_memo,
              jira_epic_key, jira_issue_key, jira_issue_url, jira_synced_at
            )
            SELECT
              :snapshotId, r.id,
              r.repository_name, r.api_path, r.http_method,
              r.last_analyzed_at, r.created_ip, r.modified_at, r.modified_ip, r.reviewed_ip,
              r.status, r.auto_analyzed_status, r.status_overridden, r.log_work_excluded, r.recent_log_only, r.test_suspect_reason, r.path_param_pattern,
              r.block_target, r.block_criteria, r.call_count, r.call_count_month, r.call_count_week,
              r.method_name, r.controller_name, r.repo_path, r.is_deprecated, r.has_url_block, r.block_marking_incomplete, r.related_menu_deficient,
              r.program_id, r.api_operation_value, r.description_tag, r.full_comment, r.controller_comment,
              r.request_property_value, r.controller_request_property_value, r.full_url, r.controller_file_path,
              r.memo, r.review_result, r.review_opinion, r.cbo_scheduled_date, r.deploy_scheduled_date,
              r.deploy_csr, r.deploy_manager, r.review_team, r.review_manager, r.reviewed_at,
              r.blocked_date, r.blocked_reason, r.status_changed, r.status_change_log, r.is_new, r.data_source,
              r.team_override, r.manager_override, r.manager_overridden, r.description_override, r.description_override_source, r.git_history,
              r.last_git_commit_date,
              r.review_stage, r.internal_reviewer, r.internal_reviewed_at, r.internal_memo,
              r.jira_epic_key, r.jira_issue_key, r.jira_issue_url, r.jira_synced_at
            FROM api_record r
            """;

        Query q = em.createNativeQuery(sql);
        q.setParameter("snapshotId", snapshotId);
        return q.executeUpdate();
    }

    public Optional<ApiRecordSnapshot> getSnapshot(Long id) {
        return snapshotRepository.findById(id);
    }

    public Map<String, Object> toMeta(ApiRecordSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("snapshotAt", s.getSnapshotAt() != null ? s.getSnapshotAt().toString() : null);
        m.put("snapshotType", s.getSnapshotType());
        m.put("label", s.getLabel());
        m.put("sourceRepo", s.getSourceRepo());
        m.put("createdIp", s.getCreatedIp());
        m.put("recordCount", s.getRecordCount());
        return m;
    }

    public Map<String, Object> diff(long fromId, long toId, String mode, Integer limit) {
        int cap = (limit == null || limit <= 0) ? 5000 : Math.min(limit, 50_000);
        // fromId/toId == 0 이면 "현재 DB(api_record)" 기준으로 비교한다.
        Map<Key, Lite> from = (fromId == 0) ? loadLiteMapLive() : loadLiteMap(fromId);
        Map<Key, Lite> to = (toId == 0) ? loadLiteMapLive() : loadLiteMap(toId);

        boolean wantNew = mode == null || mode.isBlank() || "all".equalsIgnoreCase(mode) || "new".equalsIgnoreCase(mode);
        boolean wantDeleted = mode == null || mode.isBlank() || "all".equalsIgnoreCase(mode) || "deleted".equalsIgnoreCase(mode);
        boolean wantChanged = mode == null || mode.isBlank() || "all".equalsIgnoreCase(mode) || "changed".equalsIgnoreCase(mode);

        List<Map<String, Object>> news = new ArrayList<>();
        List<Map<String, Object>> deleted = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        boolean truncated = false;

        if (wantNew) {
            for (var e : to.entrySet()) {
                if (!from.containsKey(e.getKey())) {
                    news.add(e.getValue().toSummary(e.getKey()));
                    if (news.size() >= cap) { truncated = true; break; }
                }
            }
        }

        if (wantDeleted && !truncated) {
            for (var e : from.entrySet()) {
                if (!to.containsKey(e.getKey())) {
                    deleted.add(e.getValue().toSummary(e.getKey()));
                    if (deleted.size() >= cap) { truncated = true; break; }
                }
            }
        }

        if (wantChanged && !truncated) {
            for (var e : to.entrySet()) {
                Lite oldV = from.get(e.getKey());
                if (oldV == null) continue;
                Map<String, Map<String, Object>> ch = oldV.diff(e.getValue());
                if (!ch.isEmpty()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("repositoryName", e.getKey().repositoryName);
                    row.put("apiPath", e.getKey().apiPath);
                    row.put("httpMethod", e.getKey().httpMethod);
                    row.put("old", oldV.toMini());
                    row.put("new", e.getValue().toMini());
                    row.put("changes", ch);
                    changed.add(row);
                    if (changed.size() >= cap) { truncated = true; break; }
                }
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("fromId", fromId);
        resp.put("toId", toId);
        resp.put("mode", mode == null ? "all" : mode);
        resp.put("limit", cap);
        resp.put("truncated", truncated);
        resp.put("new", news);
        resp.put("deleted", deleted);
        resp.put("changed", changed);
        resp.put("counts", Map.of(
                "from", from.size(),
                "to", to.size(),
                "new", news.size(),
                "deleted", deleted.size(),
                "changed", changed.size()
        ));
        return resp;
    }

    @Transactional
    public Map<String, Object> cleanupOldSnapshots(Integer overrideRetentionDays) {
        int days = resolveRetentionDays(overrideRetentionDays);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        log.info("[SNAPSHOT] cleanup 시작: retentionDays={}, cutoff={}", days, cutoff);

        String delRowsSql = """
            DELETE FROM api_record_snapshot_row
            WHERE snapshot_id IN (SELECT id FROM api_record_snapshot WHERE snapshot_at < :cutoff)
            """;
        int deletedRows = em.createNativeQuery(delRowsSql).setParameter("cutoff", cutoff).executeUpdate();

        String delSnapSql = "DELETE FROM api_record_snapshot WHERE snapshot_at < :cutoff";
        int deletedSnapshots = em.createNativeQuery(delSnapSql).setParameter("cutoff", cutoff).executeUpdate();

        log.info("[SNAPSHOT] cleanup 완료: snapshots={}, rows={}", deletedSnapshots, deletedRows);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("retentionDaysApplied", days);
        resp.put("deletedSnapshots", deletedSnapshots);
        resp.put("deletedRows", deletedRows);
        return resp;
    }

    public int resolveRetentionDays(Integer overrideRetentionDays) {
        if (overrideRetentionDays != null && overrideRetentionDays > 0) return overrideRetentionDays;
        Integer fromDb = globalConfigRepository.findById(1L)
                .map(c -> c.getSnapshotRetentionDays())
                .orElse(null);
        if (fromDb != null && fromDb > 0) return fromDb;
        return Math.max(1, snapshotProperties.getRetentionDays());
    }

    @Transactional
    public Map<String, Object> deleteSnapshot(long snapshotId) {
        int rows = em.createNativeQuery("DELETE FROM api_record_snapshot_row WHERE snapshot_id = :id")
                .setParameter("id", snapshotId)
                .executeUpdate();
        int snaps = em.createNativeQuery("DELETE FROM api_record_snapshot WHERE id = :id")
                .setParameter("id", snapshotId)
                .executeUpdate();
        return Map.of("deletedSnapshots", snaps, "deletedRows", rows);
    }

    @Transactional
    public Map<String, Object> deleteSnapshots(List<Long> snapshotIds) {
        if (snapshotIds == null || snapshotIds.isEmpty()) {
            return Map.of("deletedSnapshots", 0, "deletedRows", 0);
        }
        List<Long> ids = snapshotIds.stream().filter(x -> x != null && x > 0).distinct().toList();
        if (ids.isEmpty()) return Map.of("deletedSnapshots", 0, "deletedRows", 0);

        Query delRows = em.createNativeQuery("DELETE FROM api_record_snapshot_row WHERE snapshot_id IN (:ids)");
        delRows.setParameter("ids", ids);
        int rows = delRows.executeUpdate();

        Query delSnaps = em.createNativeQuery("DELETE FROM api_record_snapshot WHERE id IN (:ids)");
        delSnaps.setParameter("ids", ids);
        int snaps = delSnaps.executeUpdate();

        return Map.of("deletedSnapshots", snaps, "deletedRows", rows);
    }

    @Transactional
    public Map<String, Object> deleteSnapshotsByDate(LocalDateTime from, LocalDateTime to) {
        if (from == null) from = LocalDate.of(2000, 1, 1).atStartOfDay();
        if (to == null) to = LocalDate.of(2999, 12, 31).atTime(java.time.LocalTime.MAX);
        @SuppressWarnings("unchecked")
        List<Number> idsRaw = em.createNativeQuery(
                "SELECT id FROM api_record_snapshot WHERE snapshot_at BETWEEN :from AND :to")
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<Long> ids = idsRaw.stream().map(Number::longValue).toList();
        Map<String, Object> res = new LinkedHashMap<>(deleteSnapshots(ids));
        res.put("from", from.toString());
        res.put("to", to.toString());
        res.put("ids", ids);
        return res;
    }

    /**
     * 선택한 풀 스냅샷으로 라이브 {@code api_record}를 덮어쓴다.
     * 기존 {@code api_record} 전 행을 삭제한 뒤, 스냅샷 행을 그대로 INSERT 한다.
     * {@code apm_call_data} 등 호출이력·집계 테이블은 변경하지 않는다.
     */
    @Transactional
    public Map<String, Object> restoreLiveFromSnapshot(long snapshotId) {
        if (snapshotId <= 0) {
            throw new IllegalArgumentException("snapshotId가 유효하지 않습니다.");
        }
        if (snapshotRepository.findById(snapshotId).isEmpty()) {
            throw new IllegalArgumentException("스냅샷을 찾을 수 없습니다: id=" + snapshotId);
        }
        Number cnt = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM api_record_snapshot_row WHERE snapshot_id = :sid")
                .setParameter("sid", snapshotId)
                .getSingleResult();
        long rowCount = cnt.longValue();
        if (rowCount == 0) {
            throw new IllegalArgumentException("스냅샷에 복구할 행이 없습니다: id=" + snapshotId);
        }

        log.warn("[SNAPSHOT] 라이브 DB(api_record) 복구 시작: snapshotId={}, snapshotRows={}", snapshotId, rowCount);
        log.debug("[SNAPSHOT] restore-live: DELETE api_record 전체 후 snapshot_id={} 행 INSERT", snapshotId);
        int deletedLive = em.createNativeQuery("DELETE FROM api_record").executeUpdate();

        String insertSql = """
                INSERT INTO api_record (
                  repository_name, api_path, http_method,
                  last_analyzed_at, created_ip, modified_at, modified_ip, reviewed_ip,
                  status, auto_analyzed_status, status_overridden, log_work_excluded, recent_log_only, test_suspect_reason, path_param_pattern,
                  block_target, block_criteria, call_count, call_count_month, call_count_week,
                  method_name, controller_name, repo_path, is_deprecated, has_url_block, block_marking_incomplete, related_menu_deficient,
                  program_id, api_operation_value, description_tag, full_comment, controller_comment,
                  request_property_value, controller_request_property_value, full_url, controller_file_path,
                  memo, review_result, review_opinion, cbo_scheduled_date, deploy_scheduled_date,
                  deploy_csr, deploy_manager, review_team, review_manager, reviewed_at,
                  blocked_date, blocked_reason, status_changed, status_change_log, is_new, data_source,
                  team_override, manager_override, manager_overridden, description_override, git_history,
                  last_git_commit_date,
                  review_stage, internal_reviewer, internal_reviewed_at, internal_memo,
                  jira_epic_key, jira_issue_key, jira_issue_url, jira_synced_at
                )
                SELECT
                  repository_name, api_path, http_method,
                  last_analyzed_at, created_ip, modified_at, modified_ip, reviewed_ip,
                  status, auto_analyzed_status, status_overridden, log_work_excluded, recent_log_only, test_suspect_reason, path_param_pattern,
                  block_target, block_criteria, call_count, call_count_month, call_count_week,
                  method_name, controller_name, repo_path, is_deprecated, has_url_block, block_marking_incomplete, related_menu_deficient,
                  program_id, api_operation_value, description_tag, full_comment, controller_comment,
                  request_property_value, controller_request_property_value, full_url, controller_file_path,
                  memo, review_result, review_opinion, cbo_scheduled_date, deploy_scheduled_date,
                  deploy_csr, deploy_manager, review_team, review_manager, reviewed_at,
                  blocked_date, blocked_reason, status_changed, status_change_log, is_new, data_source,
                  team_override, manager_override, manager_overridden, description_override, git_history,
                  last_git_commit_date,
                  review_stage, internal_reviewer, internal_reviewed_at, internal_memo,
                  jira_epic_key, jira_issue_key, jira_issue_url, jira_synced_at
                FROM api_record_snapshot_row
                WHERE snapshot_id = :sid
                """;
        int inserted = em.createNativeQuery(insertSql).setParameter("sid", snapshotId).executeUpdate();
        log.warn("[SNAPSHOT] 라이브 DB(api_record) 복구 완료: snapshotId={}, deletedLive={}, inserted={}",
                snapshotId, deletedLive, inserted);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("snapshotId", snapshotId);
        res.put("previousLiveRowsDeleted", deletedLive);
        res.put("insertedRows", inserted);
        res.put("note", "apm_call_data·apm_url_stat 등 호출이력/집계는 변경하지 않았습니다. 시점 불일치 시 집계·호출 화면을 별도 확인하세요.");
        return res;
    }

    private Map<Key, Lite> loadLiteMap(long snapshotId) {
        String sql = """
            SELECT repository_name, api_path, http_method,
                   status, status_overridden,
                   call_count, call_count_month, call_count_week,
                   has_url_block, is_deprecated, block_marking_incomplete,
                   review_result, review_opinion,
                   cbo_scheduled_date, deploy_scheduled_date, deploy_csr,
                   team_override, manager_override, manager_overridden,
                   description_override, memo, test_suspect_reason, path_param_pattern,
                   blocked_date, blocked_reason,
                   jira_issue_key, review_stage
            FROM api_record_snapshot_row
            WHERE snapshot_id = :sid
            """;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).setParameter("sid", snapshotId).getResultList();
        Map<Key, Lite> m = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            Key k = new Key(
                    str(r[0]), str(r[1]), str(r[2])
            );
            Lite v = new Lite(
                    str(r[3]),
                    bool(r[4]),
                    lng(r[5]), lng(r[6]), lng(r[7]),
                    str(r[8]), str(r[9]), bool(r[10]),
                    str(r[11]), str(r[12]),
                    str(r[13]), str(r[14]), str(r[15]),
                    str(r[16]), str(r[17]), bool(r[18]),
                    str(r[19]), str(r[20]), str(r[21]),
                    str(r[22]),
                    str(r[23]), str(r[24]),
                    str(r[25]), str(r[26])
            );
            m.put(k, v);
        }
        return m;
    }

    /**
     * 현재 DB(api_record) 기준 Lite 맵 로드.
     * - diff에서 fromId/toId == 0 일 때 사용
     * - insertRowsFromApiRecord와 동일하게 status='삭제' 행 포함(키 단위 diff 왜곡 방지)
     */
    private Map<Key, Lite> loadLiteMapLive() {
        String sql = """
            SELECT repository_name, api_path, http_method,
                   status, status_overridden,
                   call_count, call_count_month, call_count_week,
                   has_url_block, is_deprecated, block_marking_incomplete,
                   review_result, review_opinion,
                   cbo_scheduled_date, deploy_scheduled_date, deploy_csr,
                   team_override, manager_override, manager_overridden,
                   description_override, memo, test_suspect_reason, path_param_pattern,
                   blocked_date, blocked_reason,
                   jira_issue_key, review_stage
            FROM api_record
            """;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        Map<Key, Lite> m = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            Key k = new Key(str(r[0]), str(r[1]), str(r[2]));
            Lite v = new Lite(
                    str(r[3]),
                    bool(r[4]),
                    lng(r[5]), lng(r[6]), lng(r[7]),
                    str(r[8]), str(r[9]), bool(r[10]),
                    str(r[11]), str(r[12]),
                    str(r[13]), str(r[14]), str(r[15]),
                    str(r[16]), str(r[17]), bool(r[18]),
                    str(r[19]), str(r[20]), str(r[21]),
                    str(r[22]),
                    str(r[23]), str(r[24]),
                    str(r[25]), str(r[26])
            );
            m.put(k, v);
        }
        return m;
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private Boolean bool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s) || "Y".equalsIgnoreCase(s)) return true;
        if ("0".equals(s) || "false".equalsIgnoreCase(s) || "N".equalsIgnoreCase(s)) return false;
        return null;
    }
    private Long lng(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private record Key(String repositoryName, String apiPath, String httpMethod) {}

    private record Lite(
            String status,
            Boolean statusOverridden,
            Long callCount,
            Long callCountMonth,
            Long callCountWeek,
            String hasUrlBlock,
            String isDeprecated,
            Boolean blockMarkingIncomplete,
            String reviewResult,
            String reviewOpinion,
            String cboScheduledDate,
            String deployScheduledDate,
            String deployCsr,
            String teamOverride,
            String managerOverride,
            Boolean managerOverridden,
            String descriptionOverride,
            String memo,
            String testSuspectReason,
            String pathParamPattern,
            String blockedDate,
            String blockedReason,
            String jiraIssueKey,
            String reviewStage
    ) {
        Map<String, Object> toSummary(Key k) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repositoryName", k.repositoryName);
            m.put("apiPath", k.apiPath);
            m.put("httpMethod", k.httpMethod);
            m.put("status", status);
            m.put("deployScheduledDate", deployScheduledDate);
            m.put("reviewResult", reviewResult);
            return m;
        }

        Map<String, Object> toMini() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("statusOverridden", statusOverridden);
            m.put("callCount", callCount);
            m.put("hasUrlBlock", hasUrlBlock);
            m.put("isDeprecated", isDeprecated);
            m.put("reviewResult", reviewResult);
            m.put("deployScheduledDate", deployScheduledDate);
            m.put("teamOverride", teamOverride);
            m.put("managerOverride", managerOverride);
            m.put("descriptionOverride", descriptionOverride);
            m.put("memo", memo);
            m.put("jiraIssueKey", jiraIssueKey);
            m.put("reviewStage", reviewStage);
            return m;
        }

        Map<String, Map<String, Object>> diff(Lite other) {
            Map<String, Map<String, Object>> ch = new LinkedHashMap<>();
            add(ch, "status", status, other.status);
            add(ch, "statusOverridden", statusOverridden, other.statusOverridden);
            add(ch, "callCount", callCount, other.callCount);
            add(ch, "callCountMonth", callCountMonth, other.callCountMonth);
            add(ch, "callCountWeek", callCountWeek, other.callCountWeek);
            add(ch, "hasUrlBlock", hasUrlBlock, other.hasUrlBlock);
            add(ch, "isDeprecated", isDeprecated, other.isDeprecated);
            add(ch, "blockMarkingIncomplete", blockMarkingIncomplete, other.blockMarkingIncomplete);
            add(ch, "reviewResult", reviewResult, other.reviewResult);
            add(ch, "reviewOpinion", reviewOpinion, other.reviewOpinion);
            add(ch, "cboScheduledDate", cboScheduledDate, other.cboScheduledDate);
            add(ch, "deployScheduledDate", deployScheduledDate, other.deployScheduledDate);
            add(ch, "deployCsr", deployCsr, other.deployCsr);
            add(ch, "teamOverride", teamOverride, other.teamOverride);
            add(ch, "managerOverride", managerOverride, other.managerOverride);
            add(ch, "managerOverridden", managerOverridden, other.managerOverridden);
            add(ch, "descriptionOverride", descriptionOverride, other.descriptionOverride);
            add(ch, "memo", memo, other.memo);
            add(ch, "testSuspectReason", testSuspectReason, other.testSuspectReason);
            add(ch, "pathParamPattern", pathParamPattern, other.pathParamPattern);
            add(ch, "blockedDate", blockedDate, other.blockedDate);
            add(ch, "blockedReason", blockedReason, other.blockedReason);
            add(ch, "jiraIssueKey", jiraIssueKey, other.jiraIssueKey);
            add(ch, "reviewStage", reviewStage, other.reviewStage);
            return ch;
        }

        private static void add(Map<String, Map<String, Object>> out, String k, Object a, Object b) {
            if (a == null && b == null) return;
            if (a != null && a.equals(b)) return;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("from", a);
            v.put("to", b);
            out.put(k, v);
        }
    }
}

