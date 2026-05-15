package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * URL 현황 "관련메뉴" 표시값과 동일한 우선순위로 effective 텍스트를 구한 뒤 품질 이상 여부를 판정한다.
 * (viewer.html {@code allDesc} 와 동일 규칙)
 */
@Component
public class RelatedMenuDeficiencyChecker {

    private static final String DASH = "-";
    private static final int FULL_COMMENT_PREVIEW = 60;

    /** HTML 태그 형태 */
    private static final Pattern HTML_TAG = Pattern.compile("<\\s*/?[A-Za-z][\\w-]*(?:\\s+[^>]*)?>");

    /** @deprecated 만 허용; 그 외 {@code @Something} 은 미흡 */
    private static final Pattern ANNOTATION_OTHER_THAN_DEPRECATED =
            Pattern.compile("(?i)@(?!deprecated\\b)\\w+");

    private static final List<String> JAVA_KEYWORD_PARTS = List.of(
            "throw", "throws", "return", "public", "private", "protected", "static", "void",
            "class", "interface", "import", "package", "new", "if", "else", "try", "catch", "finally",
            "switch", "case", "default", "break", "continue", "for", "while", "do", "synchronized",
            "volatile", "transient", "native", "strictfp", "assert", "instanceof", "enum", "extends",
            "implements", "super", "this", "null", "true", "false", "boolean", "byte", "short", "int",
            "long", "float", "double", "char", "goto", "const"
    );

    private final Pattern javaKeyword;

    public RelatedMenuDeficiencyChecker() {
        String alt = String.join("|", JAVA_KEYWORD_PARTS);
        this.javaKeyword = Pattern.compile("\\b(?:" + alt + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    /** viewer allDesc 와 동일한 표시용 문자열 */
    public String effectiveMenuText(ApiRecord r) {
        if (r == null) return "";
        if (isPresent(r.getDescriptionOverride())) return r.getDescriptionOverride();
        if (isPresent(r.getApiOperationValue())) return r.getApiOperationValue();
        if (isPresent(r.getDescriptionTag())) return r.getDescriptionTag();
        String fc = r.getFullComment();
        if (isPresent(fc)) {
            if (fc.length() <= FULL_COMMENT_PREVIEW) return fc;
            return fc.substring(0, FULL_COMMENT_PREVIEW) + "…";
        }
        return "";
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank() && !DASH.equals(s.trim());
    }

    public boolean isDeficientText(String effective) {
        if (effective == null) effective = "";
        int len = effective.length();
        if (len <= 2) return true;
        if (len >= 30) return true;
        if (HTML_TAG.matcher(effective).find()) return true;
        if (ANNOTATION_OTHER_THAN_DEPRECATED.matcher(effective).find()) return true;
        if (javaKeyword.matcher(effective).find()) return true;
        return false;
    }

    public boolean isDeficient(ApiRecord r) {
        return isDeficientText(effectiveMenuText(r));
    }

    /** 엔티티의 {@code relatedMenuDeficient} 를 현재 필드 기준으로 갱신 */
    public void applyTo(ApiRecord r) {
        if (r == null) return;
        r.setRelatedMenuDeficient(isDeficient(r));
    }
}
