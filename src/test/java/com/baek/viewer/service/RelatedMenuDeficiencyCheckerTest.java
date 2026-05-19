package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelatedMenuDeficiencyCheckerTest {

    @Mock
    private GlobalConfigRepository globalConfigRepo;

    private RelatedMenuDeficiencyChecker checker;

    @BeforeEach
    void setUp() {
        GlobalConfig gc = new GlobalConfig();
        gc.setRelatedMenuDeficientMinLen(3);
        gc.setRelatedMenuDeficientMaxLen(29);
        lenient().when(globalConfigRepo.findById(1L)).thenReturn(Optional.of(gc));
        checker = new RelatedMenuDeficiencyChecker(globalConfigRepo, 3, 29);
    }

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
    @DisplayName("설정 변경 시 길이 기준 반영")
    void customLengthThresholds() {
        GlobalConfig gc = new GlobalConfig();
        gc.setRelatedMenuDeficientMinLen(5);
        gc.setRelatedMenuDeficientMaxLen(20);
        when(globalConfigRepo.findById(1L)).thenReturn(Optional.of(gc));

        assertThat(checker.isDeficientText("1234")).isTrue();
        assertThat(checker.isDeficientText("12345")).isFalse();
        assertThat(checker.isDeficientText("가".repeat(21))).isTrue();
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
