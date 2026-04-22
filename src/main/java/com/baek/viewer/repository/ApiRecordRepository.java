package com.baek.viewer.repository;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ApiRecordStatsDto;
import com.baek.viewer.model.ApiRecordSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApiRecordRepository extends JpaRepository<ApiRecord, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<ApiRecord> {

    List<ApiRecord> findByRepositoryName(String repositoryName);

    Optional<ApiRecord> findByJiraIssueKey(String jiraIssueKey);

    Optional<ApiRecord> findByRepositoryNameAndApiPathAndHttpMethod(
            String repositoryName, String apiPath, String httpMethod);

    @Query("SELECT DISTINCT r.repositoryName FROM ApiRecord r ORDER BY r.repositoryName")
    List<String> findAllRepositoryNames();

    List<ApiRecord> findByBlockTargetIsNotNull();

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    void deleteByRepositoryName(String repositoryName);

    /** 전체 TRUNCATE (네이티브) — WAL/undo 최소, bulk DELETE 대비 빠름 */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "TRUNCATE TABLE api_record", nativeQuery = true)
    void bulkDeleteAll();

    /** 레포 전체 bulk DELETE */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ApiRecord a WHERE a.repositoryName = :repo")
    int bulkDeleteByRepo(@Param("repo") String repo);

    // ── 경량 목록 조회 (fullComment, controllerComment, blockedReason 제외) ──
    @Query("SELECT r FROM ApiRecord r")
    List<ApiRecordSummary> findAllSummary();

    @Query("SELECT r FROM ApiRecord r WHERE r.repositoryName = :repo")
    List<ApiRecordSummary> findSummaryByRepositoryName(@Param("repo") String repositoryName);

    @Query("SELECT r FROM ApiRecord r WHERE r.blockTarget IS NOT NULL")
    List<ApiRecordSummary> findSummaryByBlockTargetIsNotNull();

    @Query("SELECT r FROM ApiRecord r WHERE r.status IN :statuses")
    List<ApiRecordSummary> findSummaryByStatusIn(@Param("statuses") List<String> statuses);

    // ── 서버사이드 페이지네이션 (Page<Summary>) ───────────────────────────
    @Query(value = "SELECT r FROM ApiRecord r",
           countQuery = "SELECT COUNT(r) FROM ApiRecord r")
    Page<ApiRecordSummary> pageAllSummary(Pageable pageable);

    @Query(value = "SELECT r FROM ApiRecord r WHERE r.repositoryName = :repo",
           countQuery = "SELECT COUNT(r) FROM ApiRecord r WHERE r.repositoryName = :repo")
    Page<ApiRecordSummary> pageSummaryByRepositoryName(@Param("repo") String repositoryName, Pageable pageable);

    @Query(value = "SELECT r FROM ApiRecord r WHERE r.status IN :statuses",
           countQuery = "SELECT COUNT(r) FROM ApiRecord r WHERE r.status IN :statuses")
    Page<ApiRecordSummary> pageSummaryByStatusIn(@Param("statuses") List<String> statuses, Pageable pageable);

    // ── viewer 배지용 서버 집계 (COUNT 쿼리만 사용 — 전량 로드 금지) ───────
    /** status 별 카운트 — (status, count) 튜플 배열 */
    @Query("SELECT COALESCE(r.status, '사용'), COUNT(r) FROM ApiRecord r GROUP BY r.status")
    List<Object[]> countGroupByStatus();

    @Query("SELECT COALESCE(r.status, '사용'), COUNT(r) FROM ApiRecord r WHERE r.repositoryName = :repo GROUP BY r.status")
    List<Object[]> countGroupByStatusForRepo(@Param("repo") String repo);

    @Query("SELECT COALESCE(r.httpMethod, '?'), COUNT(r) FROM ApiRecord r GROUP BY r.httpMethod")
    List<Object[]> countGroupByMethod();

    @Query("SELECT COALESCE(r.httpMethod, '?'), COUNT(r) FROM ApiRecord r WHERE r.repositoryName = :repo GROUP BY r.httpMethod")
    List<Object[]> countGroupByMethodForRepo(@Param("repo") String repo);

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.isNew = true")
    long countNew();

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.isNew = true AND r.repositoryName = :repo")
    long countNewForRepo(@Param("repo") String repo);

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.statusChanged = true")
    long countStatusChanged();

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.statusChanged = true AND r.repositoryName = :repo")
    long countStatusChangedForRepo(@Param("repo") String repo);

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.reviewResult IS NOT NULL AND r.reviewResult <> ''")
    long countReviewed();

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.reviewResult IS NOT NULL AND r.reviewResult <> '' AND r.repositoryName = :repo")
    long countReviewedForRepo(@Param("repo") String repo);

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.isDeprecated = 'Y'")
    long countDeprecated();

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.isDeprecated = 'Y' AND r.repositoryName = :repo")
    long countDeprecatedForRepo(@Param("repo") String repo);

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.blockMarkingIncomplete = true")
    long countBlockMarkingIncomplete();

    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.blockMarkingIncomplete = true AND r.repositoryName = :repo")
    long countBlockMarkingIncompleteForRepo(@Param("repo") String repo);

    // ── 전체 선택/벌크 작업용 ID 목록 조회 (경량) ─────────────────────────
    @Query("SELECT r.id FROM ApiRecord r")
    List<Long> findAllIds();

    @Query("SELECT r.id FROM ApiRecord r WHERE r.repositoryName = :repo")
    List<Long> findIdsByRepositoryName(@Param("repo") String repo);

    @Query("SELECT r.id FROM ApiRecord r WHERE r.status IN :statuses")
    List<Long> findIdsByStatusIn(@Param("statuses") List<String> statuses);

    // ── 통계 전용 경량 DTO 쿼리 ─────────────────────────────────────────────
    /**
     * 통계 집계용 — TEXT 컬럼(fullComment/gitHistory 등) 제외하고 필요한 컬럼만 로드.
     * 삭제 상태는 제외되며, 삭제 카운트는 {@link #countByStatus(String)} 로 별도 조회.
     */
    @Query("SELECT new com.baek.viewer.model.ApiRecordStatsDto("
            + "r.id, r.repositoryName, r.status, r.httpMethod, r.teamOverride, r.managerOverride, r.apiPath, r.lastAnalyzedAt, r.logWorkExcluded) "
            + "FROM ApiRecord r WHERE r.status IS NULL OR r.status <> '삭제'")
    List<ApiRecordStatsDto> findAllForStats();

    long countByStatus(String status);

    /**
     * 최우선 차단대상(로그작업이력 제외) 전용 카운트 — logWorkExcluded=false 건만 집계.
     * 과거 row 의 null 호환을 위해 "NULL OR FALSE" 양쪽 매칭.
     */
    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.status = :status "
            + "AND (r.logWorkExcluded IS NULL OR r.logWorkExcluded = false)")
    long countByStatusAndLogNotExcluded(@Param("status") String status);

    /** 레포별 동일 집계 */
    @Query("SELECT COUNT(r) FROM ApiRecord r WHERE r.status = :status "
            + "AND r.repositoryName = :repo "
            + "AND (r.logWorkExcluded IS NULL OR r.logWorkExcluded = false)")
    long countByStatusAndLogNotExcludedForRepo(@Param("status") String status, @Param("repo") String repo);

    // ── 호출현황(call-stats) fast-path ──────────────────────────────────
    /**
     * URL 호출현황 목록용 사전집계 쿼리.
     * apm_call_data(천만+건) 를 GROUP BY/SUM 하지 않고 사전 집계된 call_count* 컬럼을 직접 조회.
     * 정렬(ORDER BY)은 Pageable.Sort 로 위임 — 호출자가 week/month/year 에 맞게 callCountWeek / callCountMonth / callCount 지정.
     * 반환: [repositoryName, apiPath, callCount, callCountMonth, callCountWeek]
     */
    @Query(value = "SELECT r.repositoryName, r.apiPath, r.callCount, r.callCountMonth, r.callCountWeek " +
                   "FROM ApiRecord r " +
                   "WHERE (:repo IS NULL OR r.repositoryName = :repo) " +
                   "  AND (:q IS NULL OR LOWER(CAST(r.apiPath AS string)) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))) " +
                   "  AND (r.status IS NULL OR r.status <> '삭제')",
           countQuery = "SELECT COUNT(r) FROM ApiRecord r " +
                        "WHERE (:repo IS NULL OR r.repositoryName = :repo) " +
                        "  AND (:q IS NULL OR LOWER(CAST(r.apiPath AS string)) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))) " +
                        "  AND (r.status IS NULL OR r.status <> '삭제')")
    Page<Object[]> pageCallStats(@Param("repo") String repo, @Param("q") String q, Pageable pageable);

    // ── 벌크 UPDATE (대량 처리 시 N+1 제거) ────────────────────────────────
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ApiRecord r SET r.isNew = false WHERE r.id IN :ids AND r.isNew = true")
    int bulkClearIsNew(@Param("ids") Collection<Long> ids);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ApiRecord r SET r.statusChanged = false, r.statusChangeLog = null "
            + "WHERE r.id IN :ids AND r.statusChanged = true")
    int bulkClearStatusChanged(@Param("ids") Collection<Long> ids);
}
