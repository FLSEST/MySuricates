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
import java.util.Collection;
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
                Instant.now(), Instant.now(), null, List.of(), false);

        var enriched = service.enrichWithVerifications(response);

        assertThat(enriched.verifications()).hasSize(1);
        assertThat(enriched.verifications().get(0).projectName()).isEqualTo("MyProject");
    }

    @Test
    void recordSuccessfulRun_alreadyTested_notModifiedAgain() {
        when(verificationRepository.findByTemplateIdAndProjectId("tpl-1", "proj-1"))
                .thenReturn(Optional.empty());
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("tpl-1").name(Map.of("de", "Test")).qualityStatus("TESTED").build();
        when(templateRepository.findById("tpl-1")).thenReturn(Optional.of(template));

        service.recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");

        // Template is already TESTED, should not save it again
        verify(templateRepository, never()).save(any());
    }

    @Test
    void recordSuccessfulRun_newVerification_setsVerifiedAt() {
        when(verificationRepository.findByTemplateIdAndProjectId("tpl-1", "proj-1"))
                .thenReturn(Optional.empty());
        when(templateRepository.findById("tpl-1")).thenReturn(Optional.empty());
        Instant before = Instant.now();

        service.recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");

        ArgumentCaptor<TemplateVerification> captor = ArgumentCaptor.forClass(TemplateVerification.class);
        verify(verificationRepository).save(captor.capture());
        assertThat(captor.getValue().getVerifiedAt()).isAfterOrEqualTo(before);
        assertThat(captor.getValue().getProjectId()).isEqualTo("proj-1");
    }

    @Test
    void enrichWithVerifications_unknownProject_fallbackToUnknown() {
        when(projectRepository.findAll()).thenReturn(List.of()); // no projects

        TemplateVerification v = TemplateVerification.builder()
                .templateId("tpl-1").projectId("deleted-project").lastRunId("run-1")
                .verifiedAt(Instant.now()).build();
        when(verificationRepository.findByTemplateId("tpl-1")).thenReturn(List.of(v));

        var response = new WorkflowTemplateResponse(
                "tpl-1", Map.of("de", "Test"), Map.of(), "icon", "cat",
                List.of(), null, null, true, 0, "TESTED", List.of(), "1.0",
                Instant.now(), Instant.now(), null, List.of(), false);

        var enriched = service.enrichWithVerifications(response);

        assertThat(enriched.verifications().get(0).projectName()).isEqualTo("Unknown");
    }

    @Test
    void enrichWithVerifications_noVerifications_emptyList() {
        when(projectRepository.findAll()).thenReturn(List.of());
        when(verificationRepository.findByTemplateId("tpl-1")).thenReturn(List.of());

        var response = new WorkflowTemplateResponse(
                "tpl-1", Map.of("de", "Test"), Map.of(), "icon", "cat",
                List.of(), null, null, true, 0, "UNTESTED", List.of(), "1.0",
                Instant.now(), Instant.now(), null, List.of(), false);

        var enriched = service.enrichWithVerifications(response);

        assertThat(enriched.verifications()).isEmpty();
    }

    @Test
    void enrichAllWithVerifications_enrichesMultipleTemplates() {
        Project project = new Project();
        project.setId("p1");
        project.setName("MyProject");
        when(projectRepository.findAll()).thenReturn(List.of(project));

        TemplateVerification v1 = TemplateVerification.builder()
                .templateId("tpl-1").projectId("p1").lastRunId("r1").verifiedAt(Instant.now()).build();
        TemplateVerification v2 = TemplateVerification.builder()
                .templateId("tpl-2").projectId("p1").lastRunId("r2").verifiedAt(Instant.now()).build();
        when(verificationRepository.findByTemplateIdIn(anyCollection())).thenReturn(List.of(v1, v2));

        Instant now = Instant.now();
        var resp1 = new WorkflowTemplateResponse("tpl-1", Map.of("de", "T1"), Map.of(), "i", "c",
                List.of(), null, null, true, 0, "TESTED", List.of(), "1.0", now, now, null, List.of(), false);
        var resp2 = new WorkflowTemplateResponse("tpl-2", Map.of("de", "T2"), Map.of(), "i", "c",
                List.of(), null, null, true, 0, "TESTED", List.of(), "1.0", now, now, null, List.of(), false);

        var enriched = service.enrichAllWithVerifications(List.of(resp1, resp2));

        assertThat(enriched).hasSize(2);
        assertThat(enriched.get(0).verifications()).hasSize(1);
        assertThat(enriched.get(1).verifications()).hasSize(1);
        assertThat(enriched.get(0).verifications().get(0).projectName()).isEqualTo("MyProject");
    }

    @Test
    void enrichAllWithVerifications_loadsEveryTemplateInOneQueryWithTheSameResultAsOneByOne() {
        Project project = new Project();
        project.setId("p1");
        project.setName("MyProject");
        when(projectRepository.findAll()).thenReturn(List.of(project));

        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        TemplateVerification first = TemplateVerification.builder()
                .templateId("tpl-1").projectId("p1").lastRunId("r1").verifiedAt(at).build();
        TemplateVerification second = TemplateVerification.builder()
                .templateId("tpl-1").projectId("gone").lastRunId("r2").verifiedAt(at.plusSeconds(60)).build();
        TemplateVerification third = TemplateVerification.builder()
                .templateId("tpl-3").projectId("p1").lastRunId("r3").verifiedAt(at.plusSeconds(120)).build();
        // tpl-2 has no verification at all
        when(verificationRepository.findByTemplateIdIn(anyCollection())).thenReturn(List.of(first, third, second));
        when(verificationRepository.findByTemplateId("tpl-1")).thenReturn(List.of(first, second));
        when(verificationRepository.findByTemplateId("tpl-2")).thenReturn(List.of());
        when(verificationRepository.findByTemplateId("tpl-3")).thenReturn(List.of(third));

        List<WorkflowTemplateResponse> responses = List.of(template("tpl-1"), template("tpl-2"), template("tpl-3"));
        List<WorkflowTemplateResponse> oneByOne = responses.stream().map(service::enrichWithVerifications).toList();
        clearInvocations(verificationRepository);

        List<WorkflowTemplateResponse> enriched = service.enrichAllWithVerifications(responses);

        assertThat(enriched).isEqualTo(oneByOne);
        assertThat(enriched.get(0).verifications()).extracting(v -> v.lastRunId()).containsExactly("r1", "r2");
        assertThat(enriched.get(0).verifications().get(1).projectName()).isEqualTo("Unknown");
        assertThat(enriched.get(1).verifications()).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(verificationRepository, times(1)).findByTemplateIdIn(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("tpl-1", "tpl-2", "tpl-3");
        verify(verificationRepository, never()).findByTemplateId(anyString());
    }

    @Test
    void enrichAllWithVerifications_emptyList_returnsEmpty() {
        var enriched = service.enrichAllWithVerifications(List.of());
        assertThat(enriched).isEmpty();
        verifyNoInteractions(verificationRepository);
    }

    private static WorkflowTemplateResponse template(String id) {
        Instant now = Instant.parse("2026-10-01T09:00:00Z");
        return new WorkflowTemplateResponse(id, Map.of("de", id), Map.of(), "i", "c",
                List.of(), null, null, true, 0, "TESTED", List.of(), "1.0", now, now, null, List.of(), false);
    }
}
