package com.baek.viewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code description_override_source} — api_record / snapshot_row.
 */
@Component
@Order(6)
public class DescriptionOverrideSourceSchemaMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DescriptionOverrideSourceSchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public DescriptionOverrideSourceSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        tryAlter("api_record");
        tryAlter("api_record_snapshot_row");
    }

    private void tryAlter(String table) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS description_override_source VARCHAR(20)");
            log.info("[스키마] {}.description_override_source 컬럼 확인", table);
        } catch (Exception e) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ADD description_override_source VARCHAR(20)");
                log.info("[스키마] {}.description_override_source 컬럼 추가", table);
            } catch (Exception e2) {
                log.debug("[스키마] {} description_override_source 스킵: {}", table, e2.getMessage());
            }
        }
    }
}
