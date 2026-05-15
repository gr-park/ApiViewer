package com.baek.viewer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelatedMenuAiAutoFillServiceTest {

    @Test
    void sanitizeMenuOverride_trimsAndCapsLength() {
        assertThat(RelatedMenuAiAutoFillService.sanitizeMenuOverride("  a\nb\tc  ")).isEqualTo("a b c");
        String longIn = "x".repeat(50);
        assertThat(RelatedMenuAiAutoFillService.sanitizeMenuOverride(longIn).length()).isLessThanOrEqualTo(29);
    }
}
