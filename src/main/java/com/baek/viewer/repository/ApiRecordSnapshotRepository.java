package com.baek.viewer.repository;

import com.baek.viewer.model.ApiRecordSnapshot;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ApiRecordSnapshotRepository extends JpaRepository<ApiRecordSnapshot, Long> {
    Page<ApiRecordSnapshot> findBySnapshotAtBetweenOrderBySnapshotAtDesc(LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * 기준시각(cutoff) 이전(<=) 스냅샷 중 가장 최신 1건의 ID를 반환한다.
     * repos가 있으면 해당 repos 중 1개 이상 row가 존재하는 스냅샷만 대상으로 한다.
     * (H2/PostgreSQL 호환: LIMIT 1)
     */
    @Query(value = """
            SELECT s.id
            FROM api_record_snapshot s
            WHERE s.snapshot_at <= :cutoff
              AND (:repos IS NULL OR EXISTS (
                SELECT 1
                FROM api_record_snapshot_row r
                WHERE r.snapshot_id = s.id
                  AND r.repository_name IN (:repos)
              ))
            ORDER BY s.snapshot_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Long findLatestSnapshotIdAtOrBefore(@Param("cutoff") LocalDateTime cutoff, @Param("repos") List<String> repos);
}

