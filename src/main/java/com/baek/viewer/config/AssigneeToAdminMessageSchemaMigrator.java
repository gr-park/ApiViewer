package com.baek.viewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 미로그인「관리자에게 문의」는 {@code sender_assignee_id} 가 null 이다.
 * 초기 스키마가 NOT NULL 이었던 DB에서 INSERT 오류(23502)가 나므로,
 * H2/PostgreSQL 공통 문법으로 null 허용으로 맞춘다. ddl-auto=update 가 이를 자동 반영하지 않는 경우가 있다.
 */
@Component
@Order(2)
public class AssigneeToAdminMessageSchemaMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AssigneeToAdminMessageSchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public AssigneeToAdminMessageSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        try {
            jdbc.execute(
                    "ALTER TABLE assignee_to_admin_message ALTER COLUMN sender_assignee_id DROP NOT NULL");
            log.info("[스키마] assignee_to_admin_message.sender_assignee_id NULL 허용 반영");
        } catch (Exception e) {
            log.debug("[스키마] sender_assignee_id DROP NOT NULL 스킵: {}", e.getMessage());
        }
    }
}
