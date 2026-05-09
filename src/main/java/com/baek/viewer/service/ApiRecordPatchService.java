package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * URL 레코드 단건 필드 반영 — UI PATCH와 제안 승인이 동일 규칙을 쓰도록 공통화.
 */
@Service
public class ApiRecordPatchService {

    private static final Set<String> ALLOWED_PROPOSAL_KEYS = Set.of(
            "status", "teamOverride", "managerOverride", "descriptionOverride",
            "blockCriteria", "memo", "reviewResult", "reviewOpinion", "reviewStage",
            "reviewTeam", "reviewManager", "cboScheduledDate", "deployScheduledDate", "deployCsr",
            "deployManager", "blockedDate", "blockedReason"
    );

    /** 제안 JSON에서 허용된 키만 남긴다. */
    public static Map<String, Object> filterProposalKeys(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        return raw.entrySet().stream()
                .filter(e -> ALLOWED_PROPOSAL_KEYS.contains(e.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, java.util.LinkedHashMap::new));
    }

    /**
     * {@link com.baek.viewer.controller.ApiViewController#updateRecord} 와 동일한 필드 규칙.
     * @return 변경 여부
     */
    public boolean applyPatch(ApiRecord r, Map<String, Object> body, String ip) {
        if (body == null || body.isEmpty()) return false;

        if (r.isStatusOverridden()) {
            java.util.Set<String> allowed = java.util.Set.of("isNew", "statusChanged", "statusOverridden");
            boolean onlyFlagOps = !body.isEmpty() && body.keySet().stream().allMatch(allowed::contains);
            if (!onlyFlagOps) {
                throw new IllegalStateException("확정완료 상태의 레코드는 수정할 수 없습니다. 먼저 확정을 해제해 주세요.");
            }
        }

        boolean anyChanged = false;
        boolean reviewChanged = false;
        String oldStatusForLog = r.getStatus();

        if (body.containsKey("status")) {
            String st = body.get("status") != null ? body.get("status").toString().trim() : null;
            if (st != null && !st.isBlank()) {
                if ("차단완료".equals(r.getStatus()) && !"차단완료".equals(st)) {
                    throw new IllegalStateException("차단완료 상태는 변경할 수 없습니다.");
                }
                if ("차단완료".equals(st) && !"차단완료".equals(r.getStatus())) {
                    throw new IllegalStateException("차단완료로의 상태 변경은 허용되지 않습니다.");
                }
                r.setStatus(st);
                anyChanged = true;
            }
        }
        if (body.containsKey("blockedDate")) {
            String ds = body.get("blockedDate") != null ? body.get("blockedDate").toString().trim() : "";
            r.setBlockedDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds));
            anyChanged = true;
        }
        if (body.containsKey("blockedReason")) {
            r.setBlockedReason(body.get("blockedReason") != null ? body.get("blockedReason").toString() : null);
            anyChanged = true;
        }
        if (body.containsKey("blockCriteria")) {
            r.setBlockCriteria(body.get("blockCriteria") != null ? body.get("blockCriteria").toString() : null);
            anyChanged = true;
        }
        if (body.containsKey("isDeprecated")) {
            r.setIsDeprecated(body.get("isDeprecated") != null ? body.get("isDeprecated").toString() : null);
            anyChanged = true;
        }
        if (body.containsKey("memo")) {
            r.setMemo(body.get("memo") != null ? body.get("memo").toString() : null);
            anyChanged = true;
        }
        if (body.containsKey("teamOverride")) {
            r.setTeamOverride(body.get("teamOverride") != null ? body.get("teamOverride").toString() : null);
            anyChanged = true;
        }
        if (body.containsKey("managerOverride")) {
            Object mv = body.get("managerOverride");
            String mgrVal = (mv == null) ? null : mv.toString();
            if (mgrVal != null && mgrVal.isBlank()) mgrVal = null;
            r.setManagerOverride(mgrVal);
            r.setManagerOverridden(mgrVal != null);
            anyChanged = true;
        }
        if (body.containsKey("descriptionOverride")) {
            Object v = body.get("descriptionOverride");
            String s = v == null ? null : v.toString().trim();
            r.setDescriptionOverride(s == null || s.isEmpty() ? null : s);
            anyChanged = true;
        }
        if (body.containsKey("reviewResult")) {
            r.setReviewResult(body.get("reviewResult") != null ? body.get("reviewResult").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (body.containsKey("reviewOpinion")) {
            r.setReviewOpinion(body.get("reviewOpinion") != null ? body.get("reviewOpinion").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (body.containsKey("cboScheduledDate")) {
            String ds = body.get("cboScheduledDate") != null ? body.get("cboScheduledDate").toString().trim() : "";
            r.setCboScheduledDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds));
            anyChanged = true;
        }
        if (body.containsKey("deployScheduledDate")) {
            String ds = body.get("deployScheduledDate") != null ? body.get("deployScheduledDate").toString().trim() : "";
            r.setDeployScheduledDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds));
            anyChanged = true;
        }
        if (body.containsKey("deployCsr")) {
            r.setDeployCsr(body.get("deployCsr") != null ? body.get("deployCsr").toString() : null);
            anyChanged = true;
        }
        if (body.containsKey("deployManager")) {
            r.setDeployManager(body.get("deployManager") != null ? body.get("deployManager").toString() : null);
            anyChanged = true;
        }
        if (body.containsKey("reviewTeam")) {
            r.setReviewTeam(body.get("reviewTeam") != null ? body.get("reviewTeam").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (body.containsKey("reviewManager")) {
            r.setReviewManager(body.get("reviewManager") != null ? body.get("reviewManager").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (body.containsKey("reviewStage")) {
            r.setReviewStage(body.get("reviewStage") != null ? body.get("reviewStage").toString() : null);
            anyChanged = true;
        }

        LocalDateTime now = LocalDateTime.now();
        if (anyChanged) {
            r.setModifiedAt(now);
            r.setModifiedIp(ip);
        }
        if (reviewChanged) {
            r.setReviewedAt(now);
            if (ip != null) r.setReviewedIp(ip);
        }

        if (body.containsKey("status")) {
            String newStatusForLog = r.getStatus();
            if (!Objects.equals(oldStatusForLog, newStatusForLog)) {
                r.setStatusChanged(true);
                r.setStatusChangeLog(ApiStorageService.appendChangeLogText(
                        r.getStatusChangeLog(),
                        "제안승인 " + oldStatusForLog + "→" + newStatusForLog
                ));
            }
        }

        // 수동 판정 leaf 반영 시 statusOverridden (UI 단건과 동일 취지)
        if (body.containsKey("status")) {
            String st = r.getStatus();
            if (ApiStorageService.MANUAL_STATUSES.contains(st)) {
                r.setStatusOverridden(true);
            }
        }

        return anyChanged || reviewChanged;
    }
}
