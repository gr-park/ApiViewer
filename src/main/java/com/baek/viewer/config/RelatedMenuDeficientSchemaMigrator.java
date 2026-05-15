package com.baek.viewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code related_menu_deficient} 컬럼 — ddl-auto=update 가 반영하지 않는 환경용 보조.
 * H2 / PostgreSQL 공통으로 ADD 시도 후 실패는 무시.
 */
@Component
@Order(3)
public class RelatedMenuDeficientSchemaMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RelatedMenuDeficientSchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public RelatedMenuDeficientSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        tryAlter("api_record");
        tryAlter("api_record_snapshot_row");
    }

    private void tryAlter(String table) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS related_menu_deficient BOOLEAN");
            log.info("[스키마] {}.related_menu_deficient 컬럼 확인", table);
        } catch (Exception e) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ADD related_menu_deficient BOOLEAN");
                log.info("[스키마] {}.related_menu_deficient 컬럼 추가", table);
            } catch (Exception e2) {
                log.debug("[스키마] {} related_menu_deficient 스킵: {}", table, e2.getMessage());
            }
        }
    }
}
