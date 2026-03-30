package com.automation.service.workflow;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.Project;
import com.automation.entity.TemplateVerification;
import com.automation.entity.WorkflowTemplate;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TemplateVerificationRepository;
import com.automation.repository.WorkflowTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemplateVerificationServiceTest {

    @Mock private TemplateVerificationRepository verificationRepository;
    @Mock private WorkflowTemplateRepository templateRepository;
    @Mock private ProjectRepository projectRepository;

    private TemplateVerificationService service;

    @BeforeEach
    void setUp() {
        service = new TemplateVerificationService(verificationRepository, templateRepository, projectRepository);
    }

    @Test
    void recordSuccessfulRun_blankTemplateId_returns() {
        service.recordSuccessfulRun("wf-1", "", "proj-1", "run-1");
        service.recordSuccessfulRun("wf-1", null, "proj-1", "run-1");

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void recordSuccessfulRun_nullProjectId_returns() {
        service.recordSuccessfulRun("wf-1", "tpl-1", null, "run-1");

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void recordSuccessfulRun_newVerification_creates() {
        when(verificationRepository.findByTemplateIdAndProjectId("tpl-1", "proj-1"))
                .thenReturn(Optional.empty());
        when(templateRepository.findById("tpl-1")).thenReturn(Optional.empty());

        service.recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");

        ArgumentCaptor<TemplateVerification> captor = ArgumentCaptor.forClass(TemplateVerification.class);
        verify(verificationRepository).save(captor.capture());
        assertThat(captor.getValue().getTemplateId()).isEqualTo("tpl-1");
        assertThat(captor.getValue().getLastRunId()).isEqualTo("run-1");
    }

    @Test
    void recordSuccessfulRun_existingVerification_updates() {
        TemplateVerification existing = TemplateVerification.builder()
                .templateId("tpl-1").projectId("proj-1").lastRunId("old-run").build();
        when(verificationRepository.findByTemplateIdAndProjectId("tpl-1", "proj-1"))
                .thenReturn(Optional.of(existing));
        when(templateRepository.findById("tpl-1")).thenReturn(Optional.empty());

        service.recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "new-run");

        assertThat(existing.getLastRunId()).isEqualTo("new-run");
        verify(verificationRepository).save(existing);
    }

    @Test
    void recordSuccessfulRun_promotesUntestedToTested() {
        when(verificationRepository.findByTemplateIdAndProjectId("tpl-1", "proj-1"))
                .thenReturn(Optional.empty());
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-1").name(Map.of("de", "Test")).qualityStatus("UNTESTED").build();
        when(templateRepository.findById("tpl-1")).thenReturn(Optional.of(template));

        service.recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");

        assertThat(template.getQualityStatus()).isEqualTo("TESTED");
        verify(templateRepository).save(template);
    }

    @Test
    void getVerifications_returnsAll() {
        List<TemplateVerification> verifications = List.of(
                TemplateVerification.builder().templateId("tpl-1").projectId("p1").build());
        when(verificationRepository.findByTemplateId("tpl-1")).thenReturn(verifications);

        List<TemplateVerification> result = service.getVerifications("tpl-1");

        assertThat(result).hasSize(1);
    }

    @Test
    void enrichWithVerifications_addsProjectNames() {
        Project project = new Project();
        project.setId("p1");
        project.setName("MyProject");
        when(projectRepository.findAll()).thenReturn(List.of(project));

        TemplateVerification v = TemplateVerification.builder()
                .templateId("tpl-1").projectId("p1").lastRunId("run-1")
                .verifiedAt(Instant.now()).build();
        when(verificationRepository.findByTemplateId("tpl-1")).thenReturn(List.of(v));

        var response = new WorkflowTemplateResponse(
                "tpl-1", Map.of("de", "Test"), Map.of(), "icon", "cat",
                List.of(), null, null, true, 0, "TESTED", List.of(), "1.0",
                Instant.now(), Instant.now());

        var enriched = service.enrichWithVerifications(response);

        assertThat(enriched.verifications()).hasSize(1);
        assertThat(enriched.verifications().get(0).projectName()).isEqualTo("MyProject");
    }
}
