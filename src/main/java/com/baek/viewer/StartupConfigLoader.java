package com.baek.viewer;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.service.YamlConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class StartupConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigLoader.class);

    private final YamlConfigService yamlConfigService;
    private final GlobalConfigRepository globalConfigRepo;

    public StartupConfigLoader(YamlConfigService yamlConfigService, GlobalConfigRepository globalConfigRepo) {
        this.yamlConfigService = yamlConfigService;
        this.globalConfigRepo = globalConfigRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        String path = yamlConfigService.getDefaultConfigPath();
        log.info("[기동 시 설정 로드] YAML 경로={}", path);
        if (!new File(path).exists()) {
            log.warn("[기동 시 설정 로드] repos-config.yml 없음, 자동 동기화 건너뜀: {}", path);
            return;
        }
        try {
            var result = yamlConfigService.importFromYaml(path);
            log.info("[기동 시 설정 로드] 동기화 완료 — 추가 {}개, 업데이트 {}개", result.get("added"), result.get("updated"));
        } catch (Exception e) {
            log.error("[기동 시 설정 로드] 동기화 실패: {}", e.getMessage(), e);
        }
        // DB에 저장된 APM 로그 레벨 적용
        applyApmLogLevel();
    }

    private void applyApmLogLevel() {
        try {
            String level = globalConfigRepo.findById(1L).map(GlobalConfig::getApmLogLevel).orElse("INFO");
            ch.qos.logback.classic.Level lv = "DEBUG".equalsIgnoreCase(level)
                    ? ch.qos.logback.classic.Level.DEBUG : ch.qos.logback.classic.Level.INFO;
            ch.qos.logback.classic.LoggerContext ctx =
                    (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.getLogger("com.baek.viewer.service.WhatapApmService").setLevel(lv);
            ctx.getLogger("com.baek.viewer.service.JenniferApmService").setLevel(lv);
            ctx.getLogger("com.baek.viewer.service.ApiExtractorService").setLevel(lv);
            log.info("[로그레벨 적용] APM/분석 서비스 → {}", lv);
        } catch (Exception e) {
            log.warn("[APM 로그레벨 적용 실패] {}", e.getMessage());
        }
    }
}
