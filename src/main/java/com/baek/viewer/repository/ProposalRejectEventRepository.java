package com.baek.viewer.repository;

import com.baek.viewer.model.ProposalRejectEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProposalRejectEventRepository extends JpaRepository<ProposalRejectEvent, Long> {

    List<ProposalRejectEvent> findTop50ByAssigneeIdOrderByCreatedAtDesc(Long assigneeId);

    List<ProposalRejectEvent> findByAssigneeIdAndDismissedAtIsNull(Long assigneeId);

    List<ProposalRejectEvent> findByAssigneeIdAndRejectBatchId(Long assigneeId, String rejectBatchId);

    long countByAssigneeIdAndDismissedAtIsNull(Long assigneeId);

    @Query("SELECT COUNT(DISTINCT e.recordId) FROM ProposalRejectEvent e WHERE e.dismissedAt IS NULL AND EXISTS (SELECT 1 FROM ApiRecord r WHERE r.id = e.recordId AND (r.status IS NULL OR r.status <> '삭제'))")
    long countDistinctOpenRecordIds();

    @Query("SELECT COUNT(DISTINCT e.recordId) FROM ProposalRejectEvent e WHERE e.dismissedAt IS NULL AND EXISTS (SELECT 1 FROM ApiRecord r WHERE r.id = e.recordId AND r.repositoryName IN :repos AND (r.status IS NULL OR r.status <> '삭제'))")
    long countDistinctOpenRecordIdsForRepos(@Param("repos") Collection<String> repos);
}
