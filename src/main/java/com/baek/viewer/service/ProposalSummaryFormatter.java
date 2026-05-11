package com.baek.viewer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 제안 patch → 테이블/상세용 짧은 요약 문자열.
 */
public final class ProposalSummaryFormatter {

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("status", "상태");
        LABELS.put("teamOverride", "팀");
        LABELS.put("managerOverride", "담당자");
        LABELS.put("blockedDate", "차단일자");
        LABELS.put("blockedReason", "차단근거");
        LABELS.put("memo", "비고");
        LABELS.put("cboScheduledDate", "CBO예정");
        LABELS.put("deployScheduledDate", "차단예정");
        LABELS.put("deployManager", "차단작업담당");
        LABELS.put("deployCsr", "차단CSR");
        LABELS.put("blockCriteria", "차단기준");
        LABELS.put("descriptionOverride", "관련메뉴");
        LABELS.put("reviewResult", "현업검토결과");
        LABELS.put("reviewOpinion", "현업검토의견");
        LABELS.put("reviewStage", "검토단계");
        LABELS.put("reviewTeam", "검토팀");
        LABELS.put("reviewManager", "검토담당");
    }

    private ProposalSummaryFormatter() {
    }

    public static String format(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if ("statusChangeReason".equals(e.getKey()) || "statusChangeSummary".equals(e.getKey())) {
                continue;
            }
            if (e.getValue() == null) {
                continue;
            }
            String label = LABELS.getOrDefault(e.getKey(), e.getKey());
            String val = String.valueOf(e.getValue()).trim();
            if (val.isEmpty()) {
                continue;
            }
            if (val.length() > 48) {
                val = val.substring(0, 45) + "…";
            }
            parts.add(label + "→" + val);
        }
        if (parts.isEmpty()) {
            return "";
        }
        String s = String.join(", ", parts);
        if (s.length() > 220) {
            return s.substring(0, 217) + "…";
        }
        return s;
    }
}
