package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunAdmissionTest {

    @Mock private WorkflowVersionService versionService;

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

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), hasher, versionService)
                .admit(wf, TriggerSource.SCHEDULE, Map.of());

        assertThat(ticket.definitionHash()).isEqualTo(hash);
        assertThat(ticket.definitionVersionId()).isEqualTo("ver-7");
    }

    @Test
    void hashWithoutVersionWhenNoRowMatches() {
        when(versionService.findVersionIdByHash(anyString(), anyString())).thenReturn(Optional.empty());

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher, versionService)
                .admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket.definitionHash()).hasSize(64);
        assertThat(ticket.definitionVersionId()).isNull();
    }

    @Test
    void recordsNothingWhenProvenanceIsOff() {
        RunAdmission admission = new RunAdmission(
                ModuleRegistry.of(Map.of(), Map.of("provenance", "false")), hasher, versionService);

        RunAdmission.Ticket ticket = admission.admit(workflow(), TriggerSource.MANUAL, Map.of());

        assertThat(ticket).isEqualTo(new RunAdmission.Ticket(null, null));
        verifyNoInteractions(versionService);
    }

    @Test
    void unhashableDefinitionDoesNotBlockTheRun() {
        Workflow broken = Workflow.builder().id("wf-x").blocks(new ArrayList<>()).activatedSkills(null).build();
        when(versionService.findVersionIdByHash(anyString(), any())).thenThrow(new IllegalStateException("db down"));

        RunAdmission.Ticket ticket = new RunAdmission(ModuleRegistry.allEnabled(), hasher, versionService)
                .admit(broken, TriggerSource.WEBHOOK, Map.of());

        assertThat(ticket.definitionHash()).isNull();
    }
}
