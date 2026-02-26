package com.automation.service.workflow;

import com.automation.entity.TemplateVerification;
import com.automation.repository.TemplateVerificationRepository;
import com.automation.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateVerificationService {

    private final TemplateVerificationRepository verificationRepository;
    private final WorkflowTemplateRepository templateRepository;

    @Transactional
    public void recordSuccessfulRun(String workflowId, String templateId, String projectId, String runId) {
        if (templateId == null || templateId.isBlank() || projectId == null) {
            return;
        }

        // Upsert verification
        var existing = verificationRepository.findByTemplateIdAndProjectId(templateId, projectId);
        if (existing.isPresent()) {
            var verification = existing.get();
            verification.setLastRunId(runId);
            verification.setVerifiedAt(Instant.now());
            verificationRepository.save(verification);
        } else {
            var verification = TemplateVerification.builder()
                    .templateId(templateId)
                    .projectId(projectId)
                    .lastRunId(runId)
                    .verifiedAt(Instant.now())
                    .build();
            verificationRepository.save(verification);
        }

        // Update template quality status if still UNTESTED
        templateRepository.findById(templateId).ifPresent(template -> {
            if ("UNTESTED".equals(template.getQualityStatus())) {
                template.setQualityStatus("TESTED");
                templateRepository.save(template);
                log.info("Template '{}' quality status updated to TESTED after successful run in project {}",
                        template.getName(), projectId);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<TemplateVerification> getVerifications(String templateId) {
        return verificationRepository.findByTemplateId(templateId);
    }
}
