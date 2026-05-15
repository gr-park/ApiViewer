package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelatedMenuDeficiencyCheckerTest {

    private final RelatedMenuDeficiencyChecker checker = new RelatedMenuDeficiencyChecker();

    @Test
    @DisplayName("빈 effective 텍스트 → 미흡")
    void empty_isDeficient() {
        assertThat(checker.isDeficientText("")).isTrue();
    }

    @Test
    @DisplayName("2자 이하 → 미흡")
    void shortText_isDeficient() {
        assertThat(checker.isDeficientText("a")).isTrue();
        assertThat(checker.isDeficientText("ab")).isTrue();
        assertThat(checker.isDeficientText("가나")).isTrue();
    }

    @Test
    @DisplayName("3~29자 정상 한글 → 미흡 아님")
    void normalKorean_ok() {
        assertThat(checker.isDeficientText("카드 한도 조회")).isFalse();
    }

    @Test
    @DisplayName("30자 이상 → 미흡")
    void longText_isDeficient() {
        assertThat(checker.isDeficientText("가".repeat(30))).isTrue();
    }

    @Test
    @DisplayName("@deprecated (대소문자) → 허용")
    void deprecatedAllowed() {
        assertThat(checker.isDeficientText("@deprecated")).isFalse();
        assertThat(checker.isDeficientText("설명 @Deprecated 끝")).isFalse();
    }

    @Test
    @DisplayName("@Override 등 → 미흡")
    void otherAnnotation_isDeficient() {
        assertThat(checker.isDeficientText("메뉴 @Override")).isTrue();
    }

    @Test
    @DisplayName("HTML 태그 → 미흡")
    void htmlTag_isDeficient() {
        assertThat(checker.isDeficientText("설명 <div>")).isTrue();
    }

    @Test
    @DisplayName("throw 키워드 → 미흡")
    void throwKeyword_isDeficient() {
        assertThat(checker.isDeficientText("throw new X")).isTrue();
    }

    @Test
    @DisplayName("effectiveMenuText — descriptionOverride 우선")
    void effective_priority() {
        ApiRecord r = new ApiRecord();
        r.setDescriptionOverride("오버라이드");
        r.setApiOperationValue("ApiOp");
        assertThat(checker.effectiveMenuText(r)).isEqualTo("오버라이드");
    }

    @Test
    @DisplayName("effectiveMenuText — '-' 는 스킵")
    void effective_skipsDash() {
        ApiRecord r = new ApiRecord();
        r.setDescriptionOverride("-");
        r.setApiOperationValue("실제메뉴");
        assertThat(checker.effectiveMenuText(r)).isEqualTo("실제메뉴");
    }

    @Test
    @DisplayName("fullComment 60자 초과 시 말줄임")
    void effective_truncatesFullComment() {
        ApiRecord r = new ApiRecord();
        String longFc = "x".repeat(70);
        r.setFullComment(longFc);
        assertThat(checker.effectiveMenuText(r)).hasSize(61);
        assertThat(checker.effectiveMenuText(r)).endsWith("…");
    }
}
