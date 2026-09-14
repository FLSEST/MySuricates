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

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, versionService, activityLog)
                .admit(wf, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.definitionHash()).isEqualTo(hash);
        assertThat(ticket.definitionVersionId()).isEqualTo("ver-7");
    }

    @Test
    void hashWithoutVersionWhenNoRowMatches() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher, versionService, activityLog)
                .admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket.definitionHash()).hasSize(64);
        assertThat(ticket.definitionVersionId()).isNull();
    }

    @Test
    void recordsNothingWhenProvenanceIsOff() {
        RunAdmission admission = new RunAdmission(
                ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, versionService, activityLog);

        RunAdmission.Ticket ticket = admission.admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket).isEqualTo(new RunAdmission.Ticket(null, null));
        verifyNoInteractions(versionService);
    }

    @Test
    void unhashableDefinitionDoesNotBlockTheRun() {
        Workflow broken = Workflow.builder().id("wf-x").blocks(new ArrayList<>()).activatedSkills(null).build();
        when(versionService.findVersionIdByHash(anyString(), any())).thenThrow(new IllegalStateException("db down"));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher, versionService, activityLog)
                .admit(broken, TriggerSource.WEBHOOK, Map.of());

        assertThat(ticket.definitionHash()).isNull();
    }

    @Test
    void sealedWorkflowWithMatchingHashRuns() {
        Workflow wf = workflow();
        wf.setSealed(true);
        wf.setSealedHash(hasher.hash(wf));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, versionService, activityLog)
                .admit(wf, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.definitionHash()).isNull(); // provenance off — but the seal was still verified
        verifyNoInteractions(activityLog);
    }

    @Test
    void tamperedSealedWorkflowIsRefusedAndLoggedEvenWithSwitchesOff() {
        Workflow wf = workflow();
        wf.setSealed(true);
        wf.setSealedHash("0".repeat(64));
        RunAdmission admission = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, versionService, activityLog);

        assertThatThrownBy(() -> admission.admit(wf, TriggerSource.WEBHOOK, Map.of()))
                .isInstanceOf(HardeningException.class)
                .satisfies(e -> assertThat(((HardeningException) e).getCode()).isEqualTo(ErrorCode.WORKFLOW_TAMPERED))
                .hasMessageContaining("no longer matches the seal");
        verify(activityLog).log(eq(LogLevel.ERROR), eq(LogCategory.SECURITY), anyString(), eq("RunAdmission"), eq("wf-1"),
                argThat(meta -> "WEBHOOK".equals(meta.get("triggerSource")) && hasher.hash(wf).equals(meta.get("actualHash"))),
                eq("wf-1"));
    }

    @Test
    void unverifiableSealIsRefused() {
        Workflow broken = Workflow.builder().id("wf-x").name("Broken").sealed(true).sealedHash("abc").build();
        broken.setMetadata(null);
        broken.setBlocks(null);
        Workflow spy = org.mockito.Mockito.spy(broken);
        org.mockito.Mockito.doThrow(new IllegalStateException("lazy")).when(spy).getActivatedSkills();

        assertThatThrownBy(() -> new RunAdmission(ModuleRegistry.allEnabled(), hasher, versionService, activityLog)
                .admit(spy, TriggerSource.MANUAL, Map.of()))
                .isInstanceOf(HardeningException.class)
                .hasMessageContaining("could not be verified");
    }
}
