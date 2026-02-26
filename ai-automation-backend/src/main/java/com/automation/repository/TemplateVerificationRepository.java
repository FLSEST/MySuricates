package com.automation.repository;

import com.automation.entity.TemplateVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateVerificationRepository extends JpaRepository<TemplateVerification, String> {

    List<TemplateVerification> findByTemplateId(String templateId);

    Optional<TemplateVerification> findByTemplateIdAndProjectId(String templateId, String projectId);
}
