package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.Skill;
import com.automation.entity.WorkflowRun;
import com.automation.entity.WorkflowVersion;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.memory.WorkflowMemoryService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionContextFactoryTest {

    @Mock private WorkflowVersionService workflowVersionService;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private SystemIdentityService systemIdentity;
    @Mock private WorkflowContextDataService contextDataService;
    @Mock private AgentInstructionsService agentInstructionsService;
    @Mock private CommandExecutorService commandExecutorService;
    @Mock private WorkflowMemoryService workflowMemoryService;
    @Mock private ProjectContextService projectContextService;

    @InjectMocks private ExecutionContextFactory factory;

    private Project project;

    @BeforeEach
    void setUp() {
        project = Project.builder().id("proj-1").path("/p").aiContext("ctx").build();
        lenient().when(agentInstructionsService.buildInstructionPrefix(any())).thenReturn("");
        lenient().when(systemIdentity.getName()).thenReturn("MySuricates");
        lenient().when(systemIdentity.getVersion()).thenReturn("0.7.4");
        lenient().when(workflowVersionService.getVersionCount("wf-1")).thenReturn(0);
        lenient().when(workflowVersionService.findLatest("wf-1")).thenReturn(Optional.empty());
    }

    private ExecutionContextFactory.ContextSeed seed(Map<String, Object> input, String instructions,
                                                    String model, String provider, String effort, String resume) {
        return new ExecutionContextFactory.ContextSeed("run-1", "wf-1", "Demo", project, "docker-1",
                input, instructions, model, provider, effort, resume);
    }

    private ExecutionContextFactory.ContextSeed seed(Map<String, Object> input) {
        return seed(input, null, "", "", "", null);
    }

    @Test
    void create_seedsInputAndSystemVariables() {
        ExecutionContext ctx = factory.create(seed(Map.of("foo", "bar")));

        assertThat(ctx.getVariable("foo")).isEqualTo("bar");
        assertThat(ctx.getVariable("workflow_run_id")).isEqualTo("run-1");
        assertThat(ctx.getVariable("workflow_id")).isEqualTo("wf-1");
        assertThat(ctx.getVariable("workflow_name")).isEqualTo("Demo");
        assertThat(ctx.getVariable("project_id")).isEqualTo("proj-1");
        assertThat(ctx.getVariable("project_path")).isEqualTo("/p");
        assertThat(ctx.getProjectPath()).isEqualTo("/p");
        assertThat(ctx.getContainerId()).isEqualTo("docker-1");
        assertThat(ctx.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(String.valueOf(ctx.getVariable("run_timestamp"))).matches("\\d{8}-\\d{6}");
        assertThat(ctx.getVariable("started_at")).isNotNull();
        assertThat(ctx.getVariable("home_dir")).isNotNull();
        assertThat(ctx.getRunMetadata()).containsEntry("system", "MySuricates").containsEntry("systemVersion", "0.7.4");
    }

    @Test
    void create_userSuppliedProjectId_isOverwrittenBySystemValue() {
        ExecutionContext ctx = factory.create(seed(Map.of("project_id", "spoofed")));

        assertThat(ctx.getVariable("project_id")).isEqualTo("proj-1");
    }

    @Test
    void create_resumePreviousOutput_winsOverInputVariable() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_output", "from-input"), null, "", "", "", "from-resume"));

        assertThat(ctx.getPreviousOutput()).isEqualTo("from-resume");
    }

    @Test
    void create_previousOutputFromInput_whenNotResuming() {
        ExecutionContext ctx = factory.create(seed(Map.of("previous_output", "from-input")));

        assertThat(ctx.getPreviousOutput()).isEqualTo("from-input");
    }

    @Test
    void create_workflowChangedSinceLastRun_trueWhenVersionNewerThanLastCompletedRun() {
        WorkflowVersion v = mock(WorkflowVersion.class);
        when(v.getCreatedAt()).thenReturn(Instant.parse("2026-09-01T10:00:00Z"));
        when(workflowVersionService.getVersionCount("wf-1")).thenReturn(3);
        when(workflowVersionService.findLatest("wf-1")).thenReturn(Optional.of(v));
        WorkflowRun last = WorkflowRun.builder().completedAt(Instant.parse("2026-08-01T10:00:00Z")).build();
        when(runRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc("wf-1", RunStatus.COMPLETED))
                .thenReturn(Optional.of(last));

        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getVariable("workflow_version")).isEqualTo("3");
        assertThat(ctx.getVariable("workflow_changed_since_last_run")).isEqualTo("true");
    }

    @Test
    void create_versionOlderThanLastCompletedRun_marksUnchanged() {
        WorkflowVersion v = mock(WorkflowVersion.class);
        when(v.getCreatedAt()).thenReturn(Instant.parse("2026-07-01T10:00:00Z"));
        when(workflowVersionService.getVersionCount("wf-1")).thenReturn(2);
        when(workflowVersionService.findLatest("wf-1")).thenReturn(Optional.of(v));
        WorkflowRun last = WorkflowRun.builder().completedAt(Instant.parse("2026-08-01T10:00:00Z")).build();
        when(runRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc("wf-1", RunStatus.COMPLETED))
                .thenReturn(Optional.of(last));

        assertThat(factory.create(seed(Map.of())).getVariable("workflow_changed_since_last_run")).isEqualTo("false");
    }

    @Test
    void create_noVersionHistory_marksUnchanged() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        assertThat(ctx.getVariable("workflow_version")).isEqualTo("0");
        assertThat(ctx.getVariable("workflow_changed_since_last_run")).isEqualTo("false");
    }

    @Test
    void create_versionServiceThrows_fallsBackToFalse() {
        when(workflowVersionService.getVersionCount("wf-1")).thenReturn(1);
        when(workflowVersionService.findLatest("wf-1")).thenThrow(new RuntimeException("db"));

        assertThat(factory.create(seed(Map.of())).getVariable("workflow_changed_since_last_run")).isEqualTo("false");
    }

    @Test
    void create_injectsPlatformData_andBlankModelProviderEffortStayNull() {
        ExecutionContext ctx = factory.create(seed(Map.of()));

        verify(contextDataService).injectPlatformData(ctx, "wf-1", "proj-1");
        assertThat(ctx.getModel()).isNull();
        assertThat(ctx.getProvider()).isNull();
        assertThat(ctx.getEffort()).isNull();
        assertThat(ctx.getInstructionPrefix()).isNull();
    }

    @Test
    void create_nonBlankInstructionsModelProviderEffort_areApplied() {
        when(agentInstructionsService.buildInstructionPrefix("be brief")).thenReturn("PREFIX");

        ExecutionContext ctx = factory.create(seed(Map.of(), "be brief", "claude-opus-4-6", "CLAUDE", "high", null));

        assertThat(ctx.getInstructionPrefix()).isEqualTo("PREFIX");
        assertThat(ctx.getModel()).isEqualTo("claude-opus-4-6");
        assertThat(ctx.getProvider()).isEqualTo("CLAUDE");
        assertThat(ctx.getEffort()).isEqualTo("high");
        assertThat(ctx.getVariable("model")).isEqualTo("claude-opus-4-6");
    }

    @Test
    void writeProjectContextFiles_delegatesToAllThreeWriters() {
        List<Skill> skills = List.of();

        factory.writeProjectContextFiles(project, "wf-1", skills);

        verify(commandExecutorService).writeSkillContextToProject("/p", skills);
        verify(workflowMemoryService).writeActiveMemoryToProject("wf-1", "/p");
        verify(projectContextService).writeProjectContextToProject("ctx", "/p");
    }
}
