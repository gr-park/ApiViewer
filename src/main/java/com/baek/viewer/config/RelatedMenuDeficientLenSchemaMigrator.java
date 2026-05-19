package com.baek.viewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code related_menu_deficient_min_len}, {@code related_menu_deficient_max_len} — global_config 용.
 */
@Component
@Order(5)
public class RelatedMenuDeficientLenSchemaMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RelatedMenuDeficientLenSchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public RelatedMenuDeficientLenSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        tryAlter("related_menu_deficient_min_len");
        tryAlter("related_menu_deficient_max_len");
    }

    private void tryAlter(String column) {
        try {
            jdbc.execute("ALTER TABLE global_config ADD COLUMN IF NOT EXISTS " + column + " INTEGER");
            log.info("[스키마] global_config.{} 컬럼 확인", column);
        } catch (Exception e) {
            try {
                jdbc.execute("ALTER TABLE global_config ADD " + column + " INTEGER");
                log.info("[스키마] global_config.{} 컬럼 추가", column);
            } catch (Exception e2) {
                log.debug("[스키마] global_config {} 스킵: {}", column, e2.getMessage());
            }
        }
    }
}
