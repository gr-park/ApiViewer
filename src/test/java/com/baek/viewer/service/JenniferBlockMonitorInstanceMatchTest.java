package com.baek.viewer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JenniferBlockMonitorInstanceMatchTest {

    @Test
    void rejectsCommonPersSubstring() {
        assertThat(JenniferBlockMonitorService.jenniferInstanceNameMatchesRepo("loca-2.0.pers", "lp-pers")).isFalse();
    }

    @Test
    void acceptsExactAndBounded() {
        assertThat(JenniferBlockMonitorService.jenniferInstanceNameMatchesRepo("lp-pers", "lp-pers")).isTrue();
        assertThat(JenniferBlockMonitorService.jenniferInstanceNameMatchesRepo("LP-PERS", "lp-pers")).isTrue();
        assertThat(JenniferBlockMonitorService.jenniferInstanceNameMatchesRepo("app-lp-pers-api", "lp-pers")).isTrue();
        assertThat(JenniferBlockMonitorService.jenniferInstanceNameMatchesRepo("lp-pers-prod", "lp-pers")).isTrue();
    }
}
