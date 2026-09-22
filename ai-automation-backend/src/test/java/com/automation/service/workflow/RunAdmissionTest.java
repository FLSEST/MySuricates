package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.BlockDefinition;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.modules.ModuleRegistry;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunAdmissionTest {

    @Mock private WorkflowVersionService versionService;
    @Mock private ActivityLogService activityLog;
    @Mock private WorkflowSkillPins skillPins;

    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();

    private static Workflow workflow() {
        return Workflow.builder().id("wf-1").name("Deploy")
                .blocks(new ArrayList<>(List.of(BlockDefinition.builder().type("shell").config(new java.util.HashMap<>(Map.of("command", "make"))).build())))
                .activatedSkills(new ArrayList<>())
                .build();
    }

    @Test
    void recordsHashAndMatchingVersionWhenProvenanceIsOn() {
        Workflow wf = workflow();
        String hash = hasher.hash(wf);
        when(versionService.findVersionIdByHash("wf-1", hash)).thenReturn(Optional.of("ver-7"));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(wf, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.definitionHash()).isEqualTo(hash);
        assertThat(ticket.definitionVersionId()).isEqualTo("ver-7");
    }

    @Test
    void hashWithoutVersionWhenNoRowMatches() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket.definitionHash()).hasSize(64);
        assertThat(ticket.definitionVersionId()).isNull();
    }

    @Test
    void recordsNothingWhenProvenanceIsOff() {
        RunAdmission admission = new RunAdmission(
                ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog);

        RunAdmission.Ticket ticket = admission.admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket).isEqualTo(new RunAdmission.Ticket(null, null, false, null)); // seal state is recorded regardless
        verifyNoInteractions(versionService);
    }

    @Test
    void unhashableDefinitionDoesNotBlockTheRun() {
        Workflow broken = Workflow.builder().id("wf-x").blocks(new ArrayList<>()).activatedSkills(null).build();
        when(versionService.findVersionIdByHash(anyString(), any())).thenThrow(new IllegalStateException("db down"));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(broken, TriggerSource.WEBHOOK, Map.of());

        assertThat(ticket.definitionHash()).isNull();
    }

    @Test
    void sealedWorkflowWithMatchingHashRuns() {
        Workflow wf = workflow();
        wf.setSealed(true);
        wf.setSealedHash(hasher.hash(wf));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(wf, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.definitionHash()).isNull(); // provenance off — but the seal was still verified
        verifyNoInteractions(activityLog);
    }

    @Test
    void tamperedSealedWorkflowIsRefusedAndLoggedEvenWithSwitchesOff() {
        Workflow wf = workflow();
        wf.setSealed(true);
        wf.setSealedHash("0".repeat(64));
        RunAdmission admission = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog);

        assertThatThrownBy(() -> admission.admit(wf, TriggerSource.WEBHOOK, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.WORKFLOW_TAMPERED))
                .hasMessageContaining("no longer matches the seal");
        verify(activityLog).log(eq(LogLevel.ERROR), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                argThat(meta -> "WEBHOOK".equals(meta.get("triggerSource")) && hasher.hash(wf).equals(meta.get("actualHash"))),
                eq("wf-1"));
    }

    private RunAdmission policyOn() {
        return new RunAdmission(ModuleRegistry.of(Map.of(),
                Map.of("sealing", "true", "require-sealed-for-unattended", "true")), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog);
    }

    @Test
    void unattendedRunOfUnsealedWorkflowIsRefusedWhenThePolicyIsOn() {
        assertThatThrownBy(() -> policyOn().admit(workflow(), TriggerSource.SCHEDULE, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.UNATTENDED_REQUIRES_SEAL))
                .hasMessageContaining("SCHEDULE");
        verify(activityLog).log(eq(LogLevel.WARN), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                argThat(meta -> "SCHEDULE".equals(meta.get("effectiveSource"))), eq("wf-1"));
        verifyNoInteractions(versionService);
    }

    @Test
    void attendedRunsAndSealedWorkflowsPassThePolicy() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());
        RunAdmission admission = policyOn();

        assertThat(admission.admit(workflow(), TriggerSource.MANUAL, Map.of()).definitionHash()).hasSize(64);
        Workflow sealed = workflow();
        sealed.setSealed(true);
        sealed.setSealedHash(hasher.hash(sealed));
        assertThat(admission.admit(sealed, TriggerSource.WEBHOOK, Map.of()).definitionHash()).isEqualTo(sealed.getSealedHash());
        verifyNoInteractions(activityLog);
    }

    @Test
    void policyOffLetsUnattendedRunsOfUnsealedWorkflowsThrough() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(workflow(), TriggerSource.TODO_TRIGGER, Map.of());

        verifyNoInteractions(activityLog);
    }

    @Test
    void chainAndSubWorkflowRunsInheritTheirOrigin_unknownCountsAsUnattended() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());
        RunAdmission admission = policyOn();

        admission.admit(workflow(), TriggerSource.CHAIN, Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL"));
        assertThatThrownBy(() -> admission.admit(workflow(), TriggerSource.SUB_WORKFLOW,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "WEBHOOK"))).isInstanceOf(HardeningException.class);
        assertThatThrownBy(() -> admission.admit(workflow(), TriggerSource.CHAIN, Map.of()))
                .isInstanceOf(HardeningException.class).hasMessageContaining("unknown");
        assertThatThrownBy(() -> admission.admit(workflow(), TriggerSource.CHAIN,
                Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SUB_WORKFLOW"))).isInstanceOf(HardeningException.class);
    }

    @Test
    void unverifiableSealIsRefused() {
        Workflow broken = Workflow.builder().id("wf-x").name("Broken").sealed(true).sealedHash("abc").build();
        broken.setMetadata(null);
        broken.setBlocks(null);
        Workflow spy = org.mockito.Mockito.spy(broken);
        org.mockito.Mockito.doThrow(new IllegalStateException("lazy")).when(spy).getActivatedSkills();

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(spy, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class)
                .hasMessageContaining("could not be verified");
    }

    private Workflow sealedWithPins(Map<String, String> pins) {
        Workflow sealed = workflow();
        sealed.setSealed(true);
        sealed.setSealedHash(hasher.hash(sealed));
        sealed.setSealedSkillHashes(WorkflowSkillPins.toJson(pins));
        return sealed;
    }

    @Test
    void sealedWorkflowWhoseSkillsStillMatchRuns() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1"));

        new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(sealed, TriggerSource.SCHEDULE, Map.of());

        verifyNoInteractions(activityLog);
    }

    @Test
    void sealedWorkflowWithAChangedSkillIsRefused() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1", "skill-b", "h2"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1", "skill-b", "tampered"));

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.WORKFLOW_TAMPERED))
                .hasMessageContaining("skill-b").hasMessageNotContaining("skill-a");
        verify(activityLog).log(eq(LogLevel.ERROR), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                any(), eq("wf-1"));
        verifyNoInteractions(versionService);
    }

    @Test
    void deletedSkillOfASealedWorkflowIsRefused() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", WorkflowSkillPins.MISSING));

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class).hasMessageContaining("skill-a");
    }

    @Test
    void unverifiableSkillPinsAreRefused() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(sealed, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class).hasMessageContaining("skills could not be verified");
    }

    @Test
    void sealWithoutSkillPinsStillRunsButDoesNotCheckSkills() {
        Workflow legacy = workflow();
        legacy.setSealed(true);
        legacy.setSealedHash(hasher.hash(legacy));

        new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(legacy, TriggerSource.MANUAL, Map.of());

        verifyNoInteractions(skillPins, activityLog);
    }

    @Test
    void recordsTheSealStateAndTheVerifiedSkillPinsOnTheTicket() {
        Workflow sealed = sealedWithPins(Map.of("skill-a", "h1"));
        when(skillPins.pins(sealed)).thenReturn(Map.of("skill-a", "h1"));
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher,
                new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(sealed, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.sealed()).isTrue();
        assertThat(ticket.sealedSkillPins()).isEqualTo("{\"skill-a\":\"h1\"}");
        assertThat(ticket.definitionHash()).isEqualTo(sealed.getSealedHash());
    }

    @Test
    void unsealedRunIsRecordedAsNotSealed() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher,
                new WorkflowSealVerifier(hasher, skillPins), versionService, activityLog)
                .admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket.sealed()).isFalse();
        assertThat(ticket.sealedSkillPins()).isNull();
    }
}
