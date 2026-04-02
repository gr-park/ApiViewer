package com.baek.viewer.repository;

import com.baek.viewer.model.ApiRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiRecordRepository extends JpaRepository<ApiRecord, Long> {

    List<ApiRecord> findByRepositoryName(String repositoryName);

    Optional<ApiRecord> findByRepositoryNameAndApiPathAndHttpMethod(
            String repositoryName, String apiPath, String httpMethod);

    @Query("SELECT DISTINCT r.repositoryName FROM ApiRecord r ORDER BY r.repositoryName")
    List<String> findAllRepositoryNames();

    List<ApiRecord> findByBlockTargetIsNotNull();
}
