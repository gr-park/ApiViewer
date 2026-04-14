package com.baek.viewer.repository;

import com.baek.viewer.model.ApmUrlStat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ApmUrlStatRepository extends JpaRepository<ApmUrlStat, Long> {

    /** 레포별 전체 조회 (대시보드 in-memory 집계용) */
    java.util.List<ApmUrlStat> findByRepositoryName(String repositoryName);

    /** 레포 전체 삭제 (집계 갱신 시 delete + insert 패턴) */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ApmUrlStat s WHERE s.repositoryName = :repo")
    int deleteByRepo(@Param("repo") String repo);

    /** 전체 삭제 (apm_call_data 전체 삭제 시 연동) */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ApmUrlStat s")
    void deleteAllRows();

    /**
     * URL 호출현황 목록 페이징 (call-stats fast-path 전용).
     * apm_call_data 기준 집계이므로 api_record 분석 여부와 무관하게 전체 URL 노출.
     * 정렬(ORDER BY)은 Pageable.Sort 위임 — callCount / callCountMonth / callCountWeek 지정.
     * 반환: [repositoryName, apiPath, callCount, callCountMonth, callCountWeek]
     */
    @Query(value = "SELECT s.repositoryName, s.apiPath, s.callCount, s.callCountMonth, s.callCountWeek " +
                   "FROM ApmUrlStat s " +
                   "WHERE (:repo IS NULL OR s.repositoryName = :repo) " +
                   "  AND (:q IS NULL OR LOWER(CAST(s.apiPath AS string)) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))",
           countQuery = "SELECT COUNT(s) FROM ApmUrlStat s " +
                        "WHERE (:repo IS NULL OR s.repositoryName = :repo) " +
                        "  AND (:q IS NULL OR LOWER(CAST(s.apiPath AS string)) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))")
    Page<Object[]> pageStats(@Param("repo") String repo, @Param("q") String q, Pageable pageable);
}
