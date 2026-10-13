package com.automation.service.workflow;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.dto.response.WorkflowTemplateResponse.TemplateVerificationInfo;
import com.automation.entity.TemplateVerification;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TemplateVerificationRepository;
import com.automation.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateVerificationService {

    private final TemplateVerificationRepository verificationRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final ProjectRepository projectRepository;

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

    @Transactional(readOnly = true)
    public WorkflowTemplateResponse enrichWithVerifications(WorkflowTemplateResponse response) {
        return response.withVerifications(toInfos(getVerifications(response.id()), projectNames()));
    }

    @Transactional(readOnly = true)
    public List<WorkflowTemplateResponse> enrichAllWithVerifications(List<WorkflowTemplateResponse> responses) {
        if (responses.isEmpty()) {
            return List.of();
        }
        Map<String, String> projectNames = projectNames();
        // One query for the whole list instead of one per template: with a single pooled connection
        // the per-template queries ran one after another.
        List<String> templateIds = responses.stream().map(WorkflowTemplateResponse::id).distinct().toList();
        Map<String, List<TemplateVerification>> byTemplate = verificationRepository.findByTemplateIdIn(templateIds)
                .stream()
                .collect(Collectors.groupingBy(TemplateVerification::getTemplateId));

        return responses.stream()
                .map(response -> response.withVerifications(
                        toInfos(byTemplate.getOrDefault(response.id(), List.of()), projectNames)))
                .toList();
    }

    private Map<String, String> projectNames() {
        return projectRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getId(), p -> p.getName(), (a, b) -> a));
    }

    private static List<TemplateVerificationInfo> toInfos(List<TemplateVerification> verifications,
                                                          Map<String, String> projectNames) {
        return verifications.stream()
                .map(v -> new TemplateVerificationInfo(
                        v.getProjectId(),
                        projectNames.getOrDefault(v.getProjectId(), "Unknown"),
                        v.getLastRunId(),
                        v.getVerifiedAt()
                ))
                .toList();
    }
}
