package com.baek.viewer.repository;

import com.baek.viewer.model.ApmCallData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ApmCallDataRepository extends JpaRepository<ApmCallData, Long> {

    List<ApmCallData> findByRepositoryNameAndApiPathAndCallDate(
            String repositoryName, String apiPath, LocalDate callDate);

    /** (repo, apiPath, date, source) 단위 중복 체크 — source별로 별도 레코드 보관 */
    List<ApmCallData> findByRepositoryNameAndApiPathAndCallDateAndSource(
            String repositoryName, String apiPath, LocalDate callDate, String source);

    List<ApmCallData> findByRepositoryNameAndCallDateBetweenOrderByCallDateDesc(
            String repositoryName, LocalDate from, LocalDate to);

    /** 단일 API의 기간별 일자별 데이터 (차트용) */
    List<ApmCallData> findByRepositoryNameAndApiPathAndCallDateBetweenOrderByCallDateAsc(
            String repositoryName, String apiPath, LocalDate from, LocalDate to);

    List<ApmCallData> findByRepositoryNameAndSourceAndCallDateBetweenOrderByCallDateDesc(
            String repositoryName, String source, LocalDate from, LocalDate to);

    /**
     * 레포+API+날짜+source별 합계 (source별 중복 제거는 서비스 레이어에서 MAX로 처리).
     * 반환: [apiPath, callDate, source, callCount, errorCount]
     */
    @Query("SELECT a.apiPath, a.callDate, a.source, SUM(a.callCount), SUM(a.errorCount) " +
           "FROM ApmCallData a " +
           "WHERE a.repositoryName = :repo AND a.callDate BETWEEN :from AND :to " +
           "GROUP BY a.apiPath, a.callDate, a.source")
    List<Object[]> sumByRepoAndDateRange(@Param("repo") String repo,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    void deleteByRepositoryName(String repositoryName);

    void deleteByRepositoryNameAndSource(String repositoryName, String source);

    /** 전체 TRUNCATE 대용 — JPQL bulk DELETE (JPA deleteAll()보다 훨씬 빠름) */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ApmCallData a")
    int bulkDeleteAll();

    /** 레포 전체 bulk DELETE */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ApmCallData a WHERE a.repositoryName = :repo")
    int bulkDeleteByRepo(@Param("repo") String repo);

    /** 특정 source bulk DELETE (전체 레포) */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ApmCallData a WHERE a.source = :source")
    int bulkDeleteBySource(@Param("source") String source);

    /** 레포 + source bulk DELETE */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ApmCallData a WHERE a.repositoryName = :repo AND a.source = :source")
    int bulkDeleteByRepoAndSource(@Param("repo") String repo, @Param("source") String source);

    /** (repo, source, 기간) 일괄 삭제 — 수집 전 기존 데이터 제거용 */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ApmCallData a WHERE a.repositoryName = :repo AND a.source = :source AND a.callDate BETWEEN :from AND :to")
    int deleteByRepoSourceAndDateRange(@Param("repo") String repo, @Param("source") String source,
                                        @Param("from") LocalDate from, @Param("to") LocalDate to);

    // ═══════════════ URL 호출현황 집계 쿼리 ═══════════════

    /** 전체 요약: source별 총 호출/에러 건수 */
    @Query("SELECT a.source, SUM(a.callCount), SUM(a.errorCount), COUNT(DISTINCT a.apiPath) " +
           "FROM ApmCallData a WHERE a.callDate BETWEEN :from AND :to " +
           "GROUP BY a.source")
    List<Object[]> summaryBySource(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 레포별 요약: 총 호출/에러 건수 + API 수 */
    @Query("SELECT a.repositoryName, SUM(a.callCount), SUM(a.errorCount), COUNT(DISTINCT a.apiPath) " +
           "FROM ApmCallData a WHERE a.callDate BETWEEN :from AND :to " +
           "GROUP BY a.repositoryName ORDER BY SUM(a.callCount) DESC")
    List<Object[]> summaryByRepo(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** TOP N 호출건수 (repo 지정 시 해당 레포만). 정렬: callCount desc, errorCount desc, apiPath asc */
    @Query("SELECT a.repositoryName, a.apiPath, SUM(a.callCount), SUM(a.errorCount) " +
           "FROM ApmCallData a " +
           "WHERE a.callDate BETWEEN :from AND :to " +
           "  AND (:repo IS NULL OR a.repositoryName = :repo) " +
           "GROUP BY a.repositoryName, a.apiPath " +
           "ORDER BY SUM(a.callCount) DESC, SUM(a.errorCount) DESC, a.apiPath ASC")
    List<Object[]> topApis(@Param("from") LocalDate from, @Param("to") LocalDate to,
                            @Param("repo") String repo, org.springframework.data.domain.Pageable pageable);

    /**
     * URL별 집계 (페이징/검색용) — 4가지 기간 집계 동시 반환.
     * 반환: [repositoryName, apiPath, totalAll, totalError, year, month, week]
     * 명시적 countQuery 제공 (Spring Data JPA의 auto-count가 SELECT CASE WHEN 파라미터와 충돌 방지).
     */
    @Query(value = "SELECT a.repositoryName, a.apiPath, " +
           "SUM(a.callCount), SUM(a.errorCount), " +
           "SUM(CASE WHEN a.callDate >= :yearAgo THEN a.callCount ELSE 0 END), " +
           "SUM(CASE WHEN a.callDate >= :monthAgo THEN a.callCount ELSE 0 END), " +
           "SUM(CASE WHEN a.callDate >= :weekAgo THEN a.callCount ELSE 0 END) " +
           "FROM ApmCallData a " +
           "WHERE (:repo IS NULL OR a.repositoryName = :repo) " +
           "  AND (:q IS NULL OR LOWER(CAST(a.apiPath AS string)) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))) " +
           "GROUP BY a.repositoryName, a.apiPath",
           countQuery = "SELECT COUNT(DISTINCT CONCAT(a.repositoryName, '|', CAST(a.apiPath AS string))) " +
           "FROM ApmCallData a " +
           "WHERE (:repo IS NULL OR a.repositoryName = :repo) " +
           "  AND (:q IS NULL OR LOWER(CAST(a.apiPath AS string)) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))")
    org.springframework.data.domain.Page<Object[]> aggregatePaged(
            @Param("yearAgo") LocalDate yearAgo,
            @Param("monthAgo") LocalDate monthAgo,
            @Param("weekAgo") LocalDate weekAgo,
            @Param("repo") String repo, @Param("q") String q,
            org.springframework.data.domain.Pageable pageable);

    /** 단일 URL의 일별 세부 데이터 (상세보기용) */
    @Query("SELECT a.callDate, a.source, SUM(a.callCount), SUM(a.errorCount) " +
           "FROM ApmCallData a " +
           "WHERE a.repositoryName = :repo AND a.apiPath = :apiPath AND a.callDate BETWEEN :from AND :to " +
           "GROUP BY a.callDate, a.source ORDER BY a.callDate DESC, a.source ASC")
    List<Object[]> dailyByApi(@Param("repo") String repo, @Param("apiPath") String apiPath,
                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 특정 날짜 이전 데이터 조회 (아카이브용) — 대량 데이터 대비 스트림 지원 */
    @Query("SELECT a FROM ApmCallData a WHERE a.callDate < :cutoff ORDER BY a.callDate ASC")
    List<ApmCallData> findByCallDateBefore(@Param("cutoff") LocalDate cutoff);

    /** 특정 날짜 이전 데이터 건수 */
    @Query("SELECT COUNT(a) FROM ApmCallData a WHERE a.callDate < :cutoff")
    long countByCallDateBefore(@Param("cutoff") LocalDate cutoff);

    /** 특정 날짜 이전 데이터 일괄 삭제 (native DELETE — 빠름) */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ApmCallData a WHERE a.callDate < :cutoff")
    int deleteByCallDateBefore(@Param("cutoff") LocalDate cutoff);

    /** 월별 집계 (특정 URL) — YYYY-MM 그룹. SUBSTRING(CAST) 방식으로 H2/PostgreSQL 공통 호환. */
    @Query("SELECT SUBSTRING(CAST(a.callDate AS string), 1, 7), a.source, " +
           "SUM(a.callCount), SUM(a.errorCount) " +
           "FROM ApmCallData a " +
           "WHERE a.repositoryName = :repo AND a.apiPath = :apiPath " +
           "  AND a.callDate BETWEEN :from AND :to " +
           "GROUP BY SUBSTRING(CAST(a.callDate AS string), 1, 7), a.source " +
           "ORDER BY SUBSTRING(CAST(a.callDate AS string), 1, 7) DESC, a.source ASC")
    List<Object[]> monthlyByApi(@Param("repo") String repo, @Param("apiPath") String apiPath,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to);
}
