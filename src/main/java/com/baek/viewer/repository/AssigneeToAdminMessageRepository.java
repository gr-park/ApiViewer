package com.baek.viewer.repository;

import com.baek.viewer.model.AssigneeToAdminMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssigneeToAdminMessageRepository extends JpaRepository<AssigneeToAdminMessage, Long> {

    List<AssigneeToAdminMessage> findTop100ByAdminDismissedAtIsNullOrderByCreatedAtDesc();

    long countByAdminDismissedAtIsNull();
}
