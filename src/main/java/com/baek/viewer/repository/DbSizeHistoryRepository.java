package com.baek.viewer.repository;

import com.baek.viewer.model.DbSizeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DbSizeHistoryRepository extends JpaRepository<DbSizeHistory, Long> {
    Optional<DbSizeHistory> findBySnapshotDate(LocalDate snapshotDate);
    List<DbSizeHistory> findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(LocalDate from);
    List<DbSizeHistory> findAllByOrderBySnapshotDateDesc();
}
