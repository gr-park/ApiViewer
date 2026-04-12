package com.baek.viewer.repository;

import com.baek.viewer.model.JiraUserMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JiraUserMappingRepository extends JpaRepository<JiraUserMapping, Long> {

    Optional<JiraUserMapping> findByUrlviewerName(String urlviewerName);

    List<JiraUserMapping> findAll();
}
