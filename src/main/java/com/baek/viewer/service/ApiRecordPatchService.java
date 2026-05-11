package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * URL 레코드 단건 필드 반영 — UI PATCH와 제안 승인이 동일 규칙을 쓰도록 공통화.
 */
@Service
public class ApiRecordPatchService {

    private static final Set<String> ALLOWED_PROPOSAL_KEYS = Set.of(
            "status", "statusChangeReason", "statusChangeSummary",
            "teamOverride", "managerOverride", "descriptionOverride",
            "blockCriteria", "memo", "reviewResult", "reviewOpinion", "reviewStage",
            "reviewTeam", "reviewManager", "cboScheduledDate", "deployScheduledDate", "deployCsr",
            "deployManager", "blockedDate", "blockedReason"
    );

    private final ApiStorageService storageService;

    public ApiRecordPatchService(ApiStorageService storageService) {
        this.storageService = storageService;
    }

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

        Map<String, Object> work = new LinkedHashMap<>(body);
        Object reasonRaw = work.remove("statusChangeReason");
        work.remove("statusChangeSummary");
        String reasonForLog = reasonRaw != null ? reasonRaw.toString().trim() : "";
        if (reasonForLog.isEmpty()) {
            reasonForLog = null;
        }

        if (r.isStatusOverridden()) {
            java.util.Set<String> allowed = java.util.Set.of("isNew", "statusChanged", "statusOverridden");
            boolean onlyFlagOps = !work.isEmpty() && work.keySet().stream().allMatch(allowed::contains);
            if (!onlyFlagOps) {
                throw new IllegalStateException("확정완료 상태의 레코드는 수정할 수 없습니다. 먼저 확정을 해제해 주세요.");
            }
        }

        boolean anyChanged = false;
        boolean reviewChanged = false;
        String oldStatusForLog = r.getStatus();

        if (work.containsKey("status")) {
            String st = work.get("status") != null ? work.get("status").toString().trim() : null;
            if (st == null || st.isBlank()) {
                r.setStatusOverridden(false);
                r.setStatus(storageService.computeAutoStatus(r));
                anyChanged = true;
            } else {
                if ("차단완료".equals(r.getStatus()) && !"차단완료".equals(st)) {
                    throw new IllegalStateException("차단완료 상태는 변경할 수 없습니다.");
                }
                r.setStatus(st);
                anyChanged = true;
            }
        }
        if (work.containsKey("blockedDate")) {
            String ds = work.get("blockedDate") != null ? work.get("blockedDate").toString().trim() : "";
            r.setBlockedDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds));
            anyChanged = true;
        }
        if (work.containsKey("blockedReason")) {
            r.setBlockedReason(work.get("blockedReason") != null ? work.get("blockedReason").toString() : null);
            anyChanged = true;
        }
        if (work.containsKey("blockCriteria")) {
            r.setBlockCriteria(work.get("blockCriteria") != null ? work.get("blockCriteria").toString() : null);
            anyChanged = true;
        }
        if (work.containsKey("isDeprecated")) {
            r.setIsDeprecated(work.get("isDeprecated") != null ? work.get("isDeprecated").toString() : null);
            anyChanged = true;
        }
        if (work.containsKey("memo")) {
            r.setMemo(work.get("memo") != null ? work.get("memo").toString() : null);
            anyChanged = true;
        }
        if (work.containsKey("teamOverride")) {
            r.setTeamOverride(work.get("teamOverride") != null ? work.get("teamOverride").toString() : null);
            anyChanged = true;
        }
        if (work.containsKey("managerOverride")) {
            Object mv = work.get("managerOverride");
            String mgrVal = (mv == null) ? null : mv.toString();
            if (mgrVal != null && mgrVal.isBlank()) mgrVal = null;
            r.setManagerOverride(mgrVal);
            r.setManagerOverridden(mgrVal != null);
            anyChanged = true;
        }
        if (work.containsKey("descriptionOverride")) {
            Object v = work.get("descriptionOverride");
            String s = v == null ? null : v.toString().trim();
            r.setDescriptionOverride(s == null || s.isEmpty() ? null : s);
            anyChanged = true;
        }
        if (work.containsKey("reviewResult")) {
            r.setReviewResult(work.get("reviewResult") != null ? work.get("reviewResult").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (work.containsKey("reviewOpinion")) {
            r.setReviewOpinion(work.get("reviewOpinion") != null ? work.get("reviewOpinion").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (work.containsKey("cboScheduledDate")) {
            String ds = work.get("cboScheduledDate") != null ? work.get("cboScheduledDate").toString().trim() : "";
            r.setCboScheduledDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds));
            anyChanged = true;
        }
        if (work.containsKey("deployScheduledDate")) {
            String ds = work.get("deployScheduledDate") != null ? work.get("deployScheduledDate").toString().trim() : "";
            r.setDeployScheduledDate(ds.isEmpty() ? null : java.time.LocalDate.parse(ds));
            anyChanged = true;
        }
        if (work.containsKey("deployCsr")) {
            r.setDeployCsr(work.get("deployCsr") != null ? work.get("deployCsr").toString() : null);
            anyChanged = true;
        }
        if (work.containsKey("deployManager")) {
            r.setDeployManager(work.get("deployManager") != null ? work.get("deployManager").toString() : null);
            anyChanged = true;
        }
        if (work.containsKey("reviewTeam")) {
            r.setReviewTeam(work.get("reviewTeam") != null ? work.get("reviewTeam").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (work.containsKey("reviewManager")) {
            r.setReviewManager(work.get("reviewManager") != null ? work.get("reviewManager").toString() : null);
            anyChanged = true;
            reviewChanged = true;
        }
        if (work.containsKey("reviewStage")) {
            r.setReviewStage(work.get("reviewStage") != null ? work.get("reviewStage").toString() : null);
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

        if (work.containsKey("status")) {
            String newStatusForLog = r.getStatus();
            if (!Objects.equals(oldStatusForLog, newStatusForLog)) {
                String logLine = "제안승인 " + oldStatusForLog + "→" + newStatusForLog;
                if (reasonForLog != null) {
                    logLine += " (사유: " + reasonForLog + ")";
                }
                r.setStatusChangeLog(ApiStorageService.appendChangeLogText(
                        r.getStatusChangeLog(),
                        logLine
                ));
            }
        }

        // 수동 판정 leaf 반영 시 statusOverridden (UI 단건과 동일 취지)
        if (work.containsKey("status")) {
            String st = r.getStatus();
            if (ApiStorageService.MANUAL_STATUSES.contains(st)) {
                r.setStatusOverridden(true);
            }
        }

        if (anyChanged || reviewChanged) {
            storageService.refreshAutoAnalyzedStatusAndMismatchFlag(r);
        }

        return anyChanged || reviewChanged;
    }
}
