package com.baek.viewer.repository;

import com.baek.viewer.model.ApiRecordStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApiRecordStatusEventRepository extends JpaRepository<ApiRecordStatusEvent, Long> {

    List<ApiRecordStatusEvent> findByRecordIdOrderByCreatedAtAscIdAsc(Long recordId);

    @Query("""
            SELECT e FROM ApiRecordStatusEvent e
            WHERE e.recordId IN :recordIds
            ORDER BY e.recordId ASC, e.createdAt DESC, e.id DESC
            """)
    List<ApiRecordStatusEvent> findLatestCandidatesByRecordIds(@Param("recordIds") Collection<Long> recordIds);

    Optional<ApiRecordStatusEvent> findTopByRecordIdOrderByCreatedAtDescIdDesc(Long recordId);
}
