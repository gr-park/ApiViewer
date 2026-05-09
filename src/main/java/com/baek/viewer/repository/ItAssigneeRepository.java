package com.baek.viewer.repository;

import com.baek.viewer.model.ItAssignee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ItAssigneeRepository extends JpaRepository<ItAssignee, Long> {

    Optional<ItAssignee> findByTeamNameIgnoreCaseAndAssigneeNameIgnoreCase(String teamName, String assigneeName);

    /** teamQ·nameQ 가 빈 문자열이면 LIKE '%%' 로 전체 일치 */
    @Query("SELECT a FROM ItAssignee a WHERE " +
           "(LOWER(a.teamName) LIKE LOWER(CONCAT('%', :teamQ, '%'))) AND " +
           "(LOWER(a.assigneeName) LIKE LOWER(CONCAT('%', :nameQ, '%')))")
    Page<ItAssignee> searchByTeamAndName(@Param("teamQ") String teamQ, @Param("nameQ") String nameQ, Pageable pageable);
}
