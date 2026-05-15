package com.baek.viewer.repository;

import com.baek.viewer.model.ApiRecordSnapshotRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiRecordSnapshotRowRepository extends JpaRepository<ApiRecordSnapshotRow, Long> {

    Optional<ApiRecordSnapshotRow> findByIdAndSnapshotId(Long id, Long snapshotId);

    @Query("""
            SELECT r FROM ApiRecordSnapshotRow r
            WHERE r.snapshotId = :snapshotId
              AND (:repos IS NULL OR r.repositoryName IN :repos)
              AND (:status IS NULL OR r.status = :status)
              AND (
                :statusGroup IS NULL OR :statusGroup = ''
                OR (:statusGroup = 'block' AND r.status LIKE '①-%')
                OR (:statusGroup = 'blockWithDone' AND (r.status = '차단완료' OR r.status LIKE '①-%'))
                OR (:statusGroup = 'review' AND r.status LIKE '②-%')
                OR (:statusGroup = 'blockResidual' AND r.status IN ('①-① 차단대상','①-② 담당자 판단'))
                OR (:statusGroup = 'blockExcluded' AND r.status IN ('①-③ 현업요청 제외대상','①-④ 사용으로 변경'))
              )
              AND (:autoStatus IS NULL OR r.autoAnalyzedStatus = :autoStatus)
              AND (
                :autoStatusGroup IS NULL OR :autoStatusGroup = ''
                OR (:autoStatusGroup = 'block' AND r.autoAnalyzedStatus LIKE '①-%')
                OR (:autoStatusGroup = 'review' AND r.autoAnalyzedStatus LIKE '②-%')
                OR (:autoStatusGroup = 'use' AND r.autoAnalyzedStatus = '사용')
                OR (:autoStatusGroup = 'done' AND r.autoAnalyzedStatus = '차단완료')
              )
              AND (:expectedDone IS NULL OR :expectedDone = false OR (
                    r.autoAnalyzedStatus = '차단완료'
                    AND (r.status IS NULL OR r.status <> '차단완료')
              ))
              AND (:httpMethod IS NULL OR r.httpMethod = :httpMethod)
              AND (:isDeprecated IS NULL OR r.isDeprecated = :isDeprecated)
              AND (:testSuspect IS NULL OR (:testSuspect = true AND r.testSuspectReason IS NOT NULL AND r.testSuspectReason <> ''))
              AND (:pathParams IS NULL OR (:pathParams = true AND (
                    (r.pathParamPattern IS NOT NULL AND r.pathParamPattern <> '')
                    OR r.apiPath LIKE '%{%'
              )))
              AND (:markingIncomplete IS NULL OR (:markingIncomplete = true AND r.blockMarkingIncomplete = true))
              AND (:relatedMenuDeficient IS NULL OR (:relatedMenuDeficient = true AND r.relatedMenuDeficient = true))
              AND (
                :q IS NULL OR :q = ''
                OR lower(r.apiPath) LIKE concat('%', lower(:q), '%')
                OR lower(r.methodName) LIKE concat('%', lower(:q), '%')
                OR lower(r.memo) LIKE concat('%', lower(:q), '%')
              )
              AND (:recentCommitFrom IS NULL OR (r.lastGitCommitDate IS NOT NULL AND r.lastGitCommitDate >= :recentCommitFrom))
              AND (:recentCommitTo IS NULL OR (r.lastGitCommitDate IS NOT NULL AND r.lastGitCommitDate <= :recentCommitTo))
              AND (:blockedDateFrom IS NULL OR (r.blockedDate IS NOT NULL AND r.blockedDate >= :blockedDateFrom))
              AND (:blockedDateTo IS NULL OR (r.blockedDate IS NOT NULL AND r.blockedDate <= :blockedDateTo))
              AND (:blockedReason IS NULL OR :blockedReason = '' OR lower(r.blockedReason) LIKE concat('%', lower(:blockedReason), '%'))
              AND (:deployCsr IS NULL OR :deployCsr = '' OR lower(r.deployCsr) LIKE concat('%', lower(:deployCsr), '%'))
            """)
    Page<ApiRecordSnapshotRow> pageByFilters(@Param("snapshotId") Long snapshotId,
                                            @Param("repos") List<String> repos,
                                            @Param("status") String status,
                                            @Param("statusGroup") String statusGroup,
                                            @Param("autoStatus") String autoStatus,
                                            @Param("autoStatusGroup") String autoStatusGroup,
                                            @Param("expectedDone") Boolean expectedDone,
                                            @Param("httpMethod") String httpMethod,
                                            @Param("isDeprecated") String isDeprecated,
                                            @Param("testSuspect") Boolean testSuspect,
                                            @Param("pathParams") Boolean pathParams,
                                            @Param("markingIncomplete") Boolean markingIncomplete,
                                            @Param("relatedMenuDeficient") Boolean relatedMenuDeficient,
                                            @Param("q") String q,
                                            @Param("recentCommitFrom") java.time.LocalDate recentCommitFrom,
                                            @Param("recentCommitTo") java.time.LocalDate recentCommitTo,
                                            @Param("blockedDateFrom") java.time.LocalDate blockedDateFrom,
                                            @Param("blockedDateTo") java.time.LocalDate blockedDateTo,
                                            @Param("blockedReason") String blockedReason,
                                            @Param("deployCsr") String deployCsr,
                                            Pageable pageable);

    @Query("SELECT COALESCE(r.status, '사용'), COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) GROUP BY r.status")
    List<Object[]> countGroupByStatus(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COALESCE(r.autoAnalyzedStatus, '사용'), COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) GROUP BY r.autoAnalyzedStatus")
    List<Object[]> countGroupByAutoAnalyzedStatus(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COALESCE(r.httpMethod, '?'), COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) GROUP BY r.httpMethod")
    List<Object[]> countGroupByMethod(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.isNew = true AND (r.status IS NULL OR r.status <> '삭제')")
    long countNew(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.statusChanged = true")
    long countStatusChanged(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.reviewResult IS NOT NULL AND r.reviewResult <> '' AND (r.status IS NULL OR r.status <> '삭제')")
    long countReviewed(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.isDeprecated = 'Y' AND (r.status IS NULL OR r.status <> '삭제')")
    long countDeprecated(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.blockMarkingIncomplete = true AND (r.status IS NULL OR r.status <> '삭제')")
    long countBlockMarkingIncomplete(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("""
            SELECT COUNT(r) FROM ApiRecordSnapshotRow r
            WHERE r.snapshotId = :snapshotId
              AND (:repos IS NULL OR r.repositoryName IN :repos)
              AND r.autoAnalyzedStatus = '차단완료'
              AND (r.status IS NULL OR r.status <> '차단완료')
              AND (r.status IS NULL OR r.status <> '삭제')
            """)
    long countExpectedDone(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.testSuspectReason IS NOT NULL AND r.testSuspectReason <> '' AND (r.status IS NULL OR r.status <> '삭제')")
    long countTestSuspect(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("""
            SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos)
              AND (r.status IS NULL OR r.status <> '삭제')
              AND ((r.pathParamPattern IS NOT NULL AND r.pathParamPattern <> '') OR r.apiPath LIKE '%{%')
            """)
    long countPathParamPattern(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND r.relatedMenuDeficient = true AND (r.status IS NULL OR r.status <> '삭제')")
    long countRelatedMenuDeficient(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);

    @Query("SELECT COUNT(r) FROM ApiRecordSnapshotRow r WHERE r.snapshotId = :snapshotId AND (:repos IS NULL OR r.repositoryName IN :repos) AND (r.status IS NULL OR r.status <> '삭제')")
    long countAll(@Param("snapshotId") Long snapshotId, @Param("repos") List<String> repos);
}

