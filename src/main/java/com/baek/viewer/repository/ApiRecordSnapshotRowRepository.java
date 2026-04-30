package com.baek.viewer.repository;

import com.baek.viewer.model.ApiRecordSnapshotRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ApiRecordSnapshotRowRepository extends JpaRepository<ApiRecordSnapshotRow, Long> {

    @Query("""
            SELECT r FROM ApiRecordSnapshotRow r
            WHERE r.snapshotId = :snapshotId
              AND (:repos IS NULL OR r.repositoryName IN :repos)
              AND (:status IS NULL OR r.status = :status)
              AND (
                :statusGroup IS NULL OR :statusGroup = ''
                OR (:statusGroup = 'block' AND r.status LIKE '①-%')
                OR (:statusGroup = 'review' AND r.status LIKE '②-%')
                OR (:statusGroup = 'blockResidual' AND r.status IN ('①-① 차단대상','①-② 담당자 판단'))
                OR (:statusGroup = 'blockExcluded' AND r.status IN ('①-③ 현업요청 제외대상','①-④ 사용으로 변경'))
              )
              AND (:httpMethod IS NULL OR r.httpMethod = :httpMethod)
              AND (:isDeprecated IS NULL OR r.isDeprecated = :isDeprecated)
              AND (:testSuspect IS NULL OR (:testSuspect = true AND r.testSuspectReason IS NOT NULL AND r.testSuspectReason <> ''))
              AND (:markingIncomplete IS NULL OR (:markingIncomplete = true AND r.blockMarkingIncomplete = true))
              AND (
                :q IS NULL OR :q = ''
                OR lower(r.apiPath) LIKE concat('%', lower(:q), '%')
                OR lower(r.methodName) LIKE concat('%', lower(:q), '%')
                OR lower(r.memo) LIKE concat('%', lower(:q), '%')
              )
            """)
    Page<ApiRecordSnapshotRow> pageByFilters(@Param("snapshotId") Long snapshotId,
                                            @Param("repos") List<String> repos,
                                            @Param("status") String status,
                                            @Param("statusGroup") String statusGroup,
                                            @Param("httpMethod") String httpMethod,
                                            @Param("isDeprecated") String isDeprecated,
                                            @Param("testSuspect") Boolean testSuspect,
                                            @Param("markingIncomplete") Boolean markingIncomplete,
                                            @Param("q") String q,
                                            Pageable pageable);

    @Query("SELECT COALESCE(r.status, '사용'), COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) GROUP BY r.status")
    List<Object[]> countGroupByStatus(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COALESCE(r.httpMethod, '?'), COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) GROUP BY r.httpMethod")
    List<Object[]> countGroupByMethod(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.isNew = true")
    long countNew(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.statusChanged = true")
    long countStatusChanged(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.reviewResult IS NOT NULL AND r.reviewResult <> ''")
    long countReviewed(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.isDeprecated = 'Y'")
    long countDeprecated(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.blockMarkingIncomplete = true")
    long countBlockMarkingIncomplete(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.testSuspectReason IS NOT NULL AND r.testSuspectReason <> ''")
    long countTestSuspect(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos)")
    long countAll(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);
}

