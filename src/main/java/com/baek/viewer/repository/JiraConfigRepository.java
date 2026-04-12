package com.baek.viewer.repository;

import com.baek.viewer.model.JiraConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JiraConfigRepository extends JpaRepository<JiraConfig, Long> {

    /** 단일 설정 조회 — jira_config 테이블은 항상 0~1행 */
    default Optional<JiraConfig> findFirst() {
        return findAll().stream().findFirst();
    }
}
