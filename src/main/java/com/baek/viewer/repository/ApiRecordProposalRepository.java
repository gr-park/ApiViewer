package com.baek.viewer.repository;

import com.baek.viewer.model.ApiRecordProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApiRecordProposalRepository extends JpaRepository<ApiRecordProposal, Long> {

    Optional<ApiRecordProposal> findByRecordId(Long recordId);

    List<ApiRecordProposal> findByRecordIdIn(Collection<Long> recordIds);

    void deleteByRecordId(Long recordId);

    @Query("SELECT COUNT(DISTINCT p.recordId) FROM ApiRecordProposal p WHERE EXISTS (SELECT 1 FROM ApiRecord r WHERE r.id = p.recordId AND (r.status IS NULL OR r.status <> '삭제'))")
    long countDistinctPendingRecords();

    @Query("SELECT COUNT(DISTINCT p.recordId) FROM ApiRecordProposal p WHERE EXISTS (SELECT 1 FROM ApiRecord r WHERE r.id = p.recordId AND r.repositoryName IN :repos AND (r.status IS NULL OR r.status <> '삭제'))")
    long countDistinctPendingRecordsForRepos(@Param("repos") Collection<String> repos);

    @Query("SELECT p.recordId FROM ApiRecordProposal p WHERE p.recordId IN :ids")
    List<Long> findRecordIdsHavingProposal(@Param("ids") Collection<Long> ids);
}
