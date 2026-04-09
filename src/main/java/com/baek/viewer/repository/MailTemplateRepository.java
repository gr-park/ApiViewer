package com.baek.viewer.repository;

import com.baek.viewer.model.MailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailTemplateRepository extends JpaRepository<MailTemplate, Long> {
    List<MailTemplate> findAllByOrderByUpdatedAtDesc();
}
