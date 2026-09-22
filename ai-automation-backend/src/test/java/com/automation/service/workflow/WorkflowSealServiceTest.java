package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSealServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private ActivityLogService activityLog;
    @Mock private WorkflowSkillPins skillPins;
    @Mock private WorkflowSealHistory history;

    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();
    private final WorkflowSealSigner signer = RunAdmissionTest.unsignedSigner();
    private WorkflowSealService service;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        service = new WorkflowSealService(workflowRepository, hasher, skillPins, signer, history, ModuleRegistry.of(Map.of(), Map.of()), activityLog);
        workflow = Workflow.builder().id("wf-1").name("Deploy").blocks(new ArrayList<>()).instructions("go")
                .activatedSkills(new ArrayList<>()).build();
    }

    @Test
    void sealPinsTheCurrentHashAndSkillsAndLogsASecurityEvent() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillPins.pins(workflow)).thenReturn(Map.of("skill-a", "h1"));

        Workflow sealed = service.seal("wf-1");

        assertThat(sealed.isSealed()).isTrue();
        assertThat(sealed.getSealedHash()).isEqualTo(hasher.hash(workflow));
        assertThat(sealed.getSealedAt()).isNotNull();
        assertThat(sealed.getSealedSkillHashes()).isEqualTo("{\"skill-a\":\"h1\"}");
        verify(activityLog).log(LogLevel.WARN, LogCategory.SECURITY, "Workflow sealed: Deploy", "WorkflowSealService",
                "wf-1", Map.of("sealedHash", sealed.getSealedHash(), "skillHashes", Map.of("skill-a", "h1"), "signed", false), "wf-1");
    }

    @Test
    void workflowWithoutSkillsIsSealedWithAnEmptyPinSet() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillPins.pins(workflow)).thenReturn(Map.of());

        assertThat(service.seal("wf-1").getSealedSkillHashes()).isEqualTo("{}");
    }

    @Test
    void sealingASealPredatingSkillPinsAddsThePinsAndKeepsTheSeal() {
        java.time.Instant sealedAt = java.time.Instant.parse("2026-09-01T10:00:00Z");
        workflow.setSealed(true);
        workflow.setSealedHash(hasher.hash(workflow));
        workflow.setSealedAt(sealedAt);
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillPins.pins(workflow)).thenReturn(Map.of("skill-a", "h1"));

        Workflow resealed = service.seal("wf-1");

        assertThat(resealed.getSealedSkillHashes()).isEqualTo("{\"skill-a\":\"h1\"}");
        assertThat(resealed.getSealedAt()).isEqualTo(sealedAt);
        verify(activityLog).log(eq(LogLevel.WARN), eq(LogCategory.SECURITY),
                eq("Workflow seal extended to its skills: Deploy"), anyString(), eq("wf-1"), any(), eq("wf-1"));
    }

    @Test
    void sealPredatingSkillPinsIsNotExtendedWhenTheDefinitionNoLongerMatches() {
        workflow.setSealed(true);
        workflow.setSealedHash("tampered-away");
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> service.seal("wf-1"))
                .isInstanceOf(com.automation.exception.HardeningException.class)
                .hasMessageContaining("no longer matches its seal");
        verify(workflowRepository, never()).save(any());
        verify(skillPins, never()).pins(any());
    }

    @Test
    void sealingTwiceKeepsTheOriginalSeal() {
        workflow.setSealed(true);
        workflow.setSealedHash("original");
        workflow.setSealedSkillHashes("{}");
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));

        assertThat(service.seal("wf-1").getSealedHash()).isEqualTo("original");
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void unsealClearsTheSealAndLogsThePreviousHash() {
        workflow.setSealed(true);
        workflow.setSealedHash("abc");
        workflow.setSealedSkillHashes("{}");
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow unsealed = service.unseal("wf-1");

        assertThat(unsealed.isSealed()).isFalse();
        assertThat(unsealed.getSealedHash()).isNull();
        assertThat(unsealed.getSealedSkillHashes()).isNull();
        verify(activityLog).log(eq(LogLevel.WARN), eq(LogCategory.SECURITY), anyString(), anyString(), eq("wf-1"),
                eq(Map.of("previousSealedHash", "abc")), eq("wf-1"));
    }

    @Test
    void unknownWorkflowIsNotFound() {
        when(workflowRepository.findByIdWithSkills("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.seal("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sealingRecordsTheEventWithItsTimestampAndUnsealingToo() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillPins.pins(workflow)).thenReturn(Map.of("skill-a", "h1"));

        service.seal("wf-1");
        verify(history).record(eq(workflow), eq(com.automation.entity.WorkflowSealEvent.Action.SEALED),
                eq(hasher.hash(workflow)), eq(Map.of("skill-a", "h1")), eq(null));

        service.unseal("wf-1");
        verify(history).record(eq(workflow), eq(com.automation.entity.WorkflowSealEvent.Action.UNSEALED),
                anyString(), eq(null), eq(null));
    }

    @Test
    void unsealIsRefusedWhenSealingIsImmutable() {
        WorkflowSealService immutable = new WorkflowSealService(workflowRepository, hasher, skillPins, signer, history,
                ModuleRegistry.of(Map.of(), Map.of("sealing", "true", "seal-immutable", "true")), activityLog);
        workflow.setSealed(true);
        workflow.setSealedHash("abc");
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> immutable.unseal("wf-1"))
                .isInstanceOf(com.automation.exception.HardeningException.class)
                .hasMessageContaining("seal-immutable");
        assertThat(workflow.isSealed()).isTrue();
        verify(workflowRepository, never()).save(any());
        verify(activityLog).logRefusal(eq(LogLevel.WARN), eq(LogCategory.SECURITY), anyString(), anyString(),
                eq("wf-1"), any(), eq("wf-1"));
    }
}
