package com.baek.viewer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecalcRepoScopeTest {

    @Test
    @DisplayName("repoNames 콤마 분리 파싱")
    void parseRepoNames_csv() {
        assertThat(RecalcRepoScope.parseRepoNames(null, "a, b ,c"))
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("repoName 단일 + repoNames 병합")
    void parseRepoNames_merge() {
        assertThat(RecalcRepoScope.parseRepoNames("x", "a"))
                .containsExactly("a", "x");
    }

    @Test
    @DisplayName("ALL 은 무시")
    void parseRepoNames_ignoresAll() {
        assertThat(RecalcRepoScope.parseRepoNames("ALL", "ALL")).isEmpty();
    }
}
