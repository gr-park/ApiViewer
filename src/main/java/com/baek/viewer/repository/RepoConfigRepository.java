package com.baek.viewer.repository;

import com.baek.viewer.model.RepoConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RepoConfigRepository extends JpaRepository<RepoConfig, Long> {
    List<RepoConfig> findAllByOrderByRepoNameAsc();

    @Query("SELECT r FROM RepoConfig r ORDER BY COALESCE(r.displayOrder, 999999), r.repoName")
    List<RepoConfig> findAllForDisplay();

    Optional<RepoConfig> findByRepoName(String repoName);
}
