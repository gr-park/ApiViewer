package com.baek.viewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code ai_related_menu_deficient_auto} — global_config 용. ddl-auto=update 가 누락하는 환경 보조.
 */
@Component
@Order(4)
public class AiRelatedMenuDeficientAutoSchemaMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AiRelatedMenuDeficientAutoSchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public AiRelatedMenuDeficientAutoSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        tryAlter();
    }

    private void tryAlter() {
        try {
            jdbc.execute("ALTER TABLE global_config ADD COLUMN IF NOT EXISTS ai_related_menu_deficient_auto VARCHAR(5)");
            log.info("[스키마] global_config.ai_related_menu_deficient_auto 컬럼 확인");
        } catch (Exception e) {
            try {
                jdbc.execute("ALTER TABLE global_config ADD ai_related_menu_deficient_auto VARCHAR(5)");
                log.info("[스키마] global_config.ai_related_menu_deficient_auto 컬럼 추가");
            } catch (Exception e2) {
                log.debug("[스키마] global_config ai_related_menu_deficient_auto 스킵: {}", e2.getMessage());
            }
        }
    }
}
