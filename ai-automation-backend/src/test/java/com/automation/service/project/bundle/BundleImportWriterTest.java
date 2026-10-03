package com.automation.service.project.bundle;

import com.automation.dto.bundle.BundleImportRequest.TargetProject;
import com.automation.dto.bundle.BundleImportResult;
import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleChain;
import com.automation.dto.bundle.ProjectBundle.BundleChainEntry;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundleProject;
import com.automation.dto.bundle.ProjectBundle.BundleSchedule;
import com.automation.dto.bundle.ProjectBundle.BundleTarget;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;
import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.service.chain.ChainService;
import com.automation.service.project.ProjectService;
import com.automation.service.scheduler.SchedulerService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Writer rules that need no database: module chains off, skills by name, the new project's fields. */
class BundleImportWriterTest {

    private final ProjectService projects = mock(ProjectService.class);
    private final WorkflowService workflows = mock(WorkflowService.class);
    private final ChainService chains = mock(ChainService.class);
    private final SchedulerService schedules = mock(SchedulerService.class);
    private final BundleImportWriter writer = new BundleImportWriter(projects, workflows, chains, schedules,
            new ObjectMapper().findAndRegisterModules());

    private static ProjectBundle bundle() {
        BundleDefinition def = new BundleDefinition("A", null, null,
                List.of(Map.of("type", "simple", "config", Map.of("command", "x"),
                        "skillIds", List.of("@@skill:git-tools@@", "@@skill:gone@@"))),
                null, null, null, null, null, Map.of(), Map.of(), List.of("git-tools", "gone"));
        return new ProjectBundle(ProjectBundle.FORMAT, 1, null, null,
                new BundleProject("P", "sub", "desc", "research", "ctx", Map.of("k", "v")),
                List.of(new BundleWorkflow("a", def)),
                List.of(new BundleChain("c", "C", null, null, true,
                        List.of(new BundleChainEntry("a", null, 0, true, Map.of(), null)))),
                List.of(new BundleSchedule(new BundleTarget(null, "c"), "0 0 1 * * *", null, null, null, null,
                                Map.of(), null, null, null, null),
                        new BundleSchedule(new BundleTarget("a", null), "0 0 2 * * *", null, null, null, null,
                                Map.of(), null, null, null, null)),
                List.of(), List.of(), List.of());
    }

    @Test
    void chainsOff_skipsChainsAndTheirSchedules_createsTheRestDisabled() {
        when(projects.createWithOptionalPath(any())).thenReturn(Project.builder().id("p-new").build());
        when(workflows.create(any(), eq(ChangeSource.IMPORT))).thenReturn(Workflow.builder().id("w-new").build());
        when(schedules.createDisabled(anyString(), isNull(), anyString(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(ScheduledWorkflow.builder().id("s-new").build());
        Set<String> warnings = new LinkedHashSet<>(List.of(BundleWarnings.CHAINS_OFF));

        BundleImportResult result = writer.write(bundle(), new TargetProject("new", null, " ", null), false,
                Map.of("git-tools", "skill-1"), false, warnings);

        assertThat(result.created()).isEqualTo(new BundleImportResult.Created(1, 0, 1));
        assertThat(result.skipped()).extracting(BundleImportResult.Skipped::kind).containsExactly("chain", "schedule");
        assertThat(result.warnings()).containsExactly(BundleWarnings.CHAINS_OFF);
        verify(chains, never()).create(any());
        verify(schedules).createDisabled(anyString(), isNull(), anyString(), anyString(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(schedules, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());

        ArgumentCaptor<CreateWorkflowRequest> wf = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflows).create(wf.capture(), eq(ChangeSource.IMPORT));
        assertThat(wf.getValue().activatedSkillIds()).containsExactly("skill-1");
        assertThat(wf.getValue().blocks().get(0).getSkillIds()).containsExactly("skill-1");
        assertThat(wf.getValue().projectId()).isEqualTo("p-new");

        ArgumentCaptor<CreateProjectRequest> project = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projects).createWithOptionalPath(project.capture());
        assertThat(project.getValue().name()).isEqualTo("P");
        assertThat(project.getValue().path()).isEmpty();
        assertThat(project.getValue().type()).hasToString("RESEARCH");
        assertThat(project.getValue().aiContext()).isEqualTo("ctx");
    }

    @Test
    void freeNameCountsUp() {
        assertThat(BundleImportWriter.freeName("A", Set.of("A", "A (Import)", "A (Import 2)"))).isEqualTo("A (Import 3)");
        assertThat(BundleImportWriter.freeName("A", Set.of("A"))).isEqualTo("A (Import)");
    }
}
