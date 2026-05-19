package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.util.DescriptionOverrideSources;

import java.util.Map;

/** 관련메뉴(descriptionOverride) 값·출처 일괄 반영 */
public final class DescriptionOverrideHelper {

    private DescriptionOverrideHelper() {}

    public static void apply(ApiRecord r, String value, String sourceFromBody) {
        if (r == null) {
            return;
        }
        String s = value == null ? null : value.trim();
        if (s == null || s.isEmpty()) {
            r.setDescriptionOverride(null);
            r.setDescriptionOverrideSource(null);
            return;
        }
        r.setDescriptionOverride(s);
        if (sourceFromBody != null) {
            String norm = DescriptionOverrideSources.normalize(sourceFromBody);
            r.setDescriptionOverrideSource(norm != null ? norm : DescriptionOverrideSources.MANUAL);
        } else {
            r.setDescriptionOverrideSource(DescriptionOverrideSources.MANUAL);
        }
    }

    public static void applyFromPatchBody(ApiRecord r, Map<String, Object> body) {
        if (body == null || !body.containsKey("descriptionOverride")) {
            return;
        }
        Object v = body.get("descriptionOverride");
        String value = v == null ? null : v.toString();
        String source = body.containsKey("descriptionOverrideSource")
                ? (body.get("descriptionOverrideSource") == null ? null : body.get("descriptionOverrideSource").toString())
                : null;
        apply(r, value, source);
    }
}
