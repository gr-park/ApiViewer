package com.baek.viewer.repository;

import com.baek.viewer.model.AdminAssigneeNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminAssigneeNoticeRepository extends JpaRepository<AdminAssigneeNotice, Long> {

    List<AdminAssigneeNotice> findTop50ByAssigneeIdOrderByCreatedAtDesc(Long assigneeId);

    long countByAssigneeIdAndDismissedAtIsNull(Long assigneeId);
}
