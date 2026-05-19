package com.baek.viewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code ai_prompt_template} 마지막 실행 기록 컬럼 — ddl-auto=update 가 누락하는 환경 보조.
 */
@Component
@Order(5)
public class AiPromptTemplateLastRunSchemaMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AiPromptTemplateLastRunSchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public AiPromptTemplateLastRunSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        addCol("last_test_reply", "TEXT");
        addCol("last_test_at", "TIMESTAMP");
        addCol("last_test_error", "VARCHAR(500)");
        addCol("last_prod_reply", "TEXT");
        addCol("last_prod_at", "TIMESTAMP");
        addCol("last_prod_meta", "VARCHAR(500)");
        addCol("last_prod_error", "VARCHAR(500)");
    }

    private void addCol(String col, String type) {
        try {
            jdbc.execute("ALTER TABLE ai_prompt_template ADD COLUMN IF NOT EXISTS " + col + " " + type);
            log.info("[스키마] ai_prompt_template.{} 컬럼 확인", col);
        } catch (Exception e) {
            try {
                jdbc.execute("ALTER TABLE ai_prompt_template ADD " + col + " " + type);
                log.info("[스키마] ai_prompt_template.{} 컬럼 추가", col);
            } catch (Exception e2) {
                log.debug("[스키마] ai_prompt_template {} 스킵: {}", col, e2.getMessage());
            }
        }
    }
}
