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
     * 기준시각(cutoff) 이전(<=) 스냅샷 중 가장 최신 1건의 ID (레포 필터 없음).
     * PostgreSQL: {@code (? IS NULL OR ... IN (?))} 형태는 바인딩 타입 추론 실패(42P18)가 나므로 분리한다.
     */
    @Query(value = """
            SELECT s.id
            FROM api_record_snapshot s
            WHERE s.snapshot_at <= :cutoff
            ORDER BY s.snapshot_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Long findLatestSnapshotIdAtOrBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 기준시각(cutoff) 이전(<=) 스냅샷 중, 지정 레포 중 하나 이상을 포함하는 가장 최신 1건의 ID.
     * {@code repos}는 비어 있지 않은 리스트여야 한다.
     */
    @Query(value = """
            SELECT s.id
            FROM api_record_snapshot s
            WHERE s.snapshot_at <= :cutoff
              AND EXISTS (
                SELECT 1
                FROM api_record_snapshot_row r
                WHERE r.snapshot_id = s.id
                  AND r.repository_name IN (:repos)
              )
            ORDER BY s.snapshot_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Long findLatestSnapshotIdAtOrBeforeMatchingRepos(@Param("cutoff") LocalDateTime cutoff, @Param("repos") List<String> repos);

    /**
     * 기준시각(cutoff) 이전(<=) 스냅샷 중 가장 최신 1건의 ID를 반환한다. (전체 스냅샷 우선)
     * - source_repo 가 비어있는(=전체 Extract 스냅샷) 건만 대상으로 한다.
     * - viewer.html 전체 조회(레포 선택 없음)에서 repo 단위 부분 스냅샷이 최신일 때 26건 등으로 보이는 문제를 방지.
     */
    @Query(value = """
            SELECT s.id
            FROM api_record_snapshot s
            WHERE s.snapshot_at <= :cutoff
              AND (s.source_repo IS NULL OR TRIM(s.source_repo) = '')
            ORDER BY s.snapshot_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Long findLatestGlobalSnapshotIdAtOrBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 특정 기간(from~to) 내 스냅샷 ID 목록(최신순). 레포 필터 없음.
     */
    @Query(value = """
            SELECT s.id
            FROM api_record_snapshot s
            WHERE s.snapshot_at >= :from
              AND s.snapshot_at <= :to
            ORDER BY s.snapshot_at DESC
            """, nativeQuery = true)
    List<Long> findIdsBySnapshotAtBetween(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         Pageable pageable);

    /**
     * 특정 기간 내 스냅샷 ID 목록(최신순) — 지정 레포 중 하나 이상을 포함하는 스냅샷만.
     * {@code repos}는 비어 있지 않은 리스트여야 한다.
     */
    @Query(value = """
            SELECT s.id
            FROM api_record_snapshot s
            WHERE s.snapshot_at >= :from
              AND s.snapshot_at <= :to
              AND EXISTS (
                SELECT 1
                FROM api_record_snapshot_row r
                WHERE r.snapshot_id = s.id
                  AND r.repository_name IN (:repos)
              )
            ORDER BY s.snapshot_at DESC
            """, nativeQuery = true)
    List<Long> findIdsBySnapshotAtBetweenMatchingRepos(@Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to,
                                                      @Param("repos") List<String> repos,
                                                      Pageable pageable);
}

