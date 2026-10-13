package com.automation.repository;

import com.automation.entity.TemplateVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TemplateVerificationRepository extends JpaRepository<TemplateVerification, String> {

    List<TemplateVerification> findByTemplateId(String templateId);

    /** The verifications of several templates in one query (the template list shows them all at once). */
    List<TemplateVerification> findByTemplateIdIn(Collection<String> templateIds);

    Optional<TemplateVerification> findByTemplateIdAndProjectId(String templateId, String projectId);
}
