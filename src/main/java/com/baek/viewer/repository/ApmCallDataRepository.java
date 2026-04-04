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

    List<ApmCallData> findByRepositoryNameAndCallDateBetweenOrderByCallDateDesc(
            String repositoryName, LocalDate from, LocalDate to);

    /** 레포+API별 기간 합계 (callCount, errorCount) */
    @Query("SELECT a.apiPath, SUM(a.callCount), SUM(a.errorCount) FROM ApmCallData a " +
           "WHERE a.repositoryName = :repo AND a.callDate BETWEEN :from AND :to " +
           "GROUP BY a.apiPath")
    List<Object[]> sumByRepoAndDateRange(@Param("repo") String repo,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    void deleteByRepositoryName(String repositoryName);
}
