package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ContainerException;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.ContainerRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.memory.WorkflowMemoryService;
import com.automation.service.project.ContainerService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.workflow.AgentInstructionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import com.automation.concurrency.DynamicSemaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutorTest {

    @Mock
    private ContainerService containerService;

    @Mock
    private CommandExecutorService commandExecutorService;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private com.automation.repository.StepResultRepository stepResultRepository;

    @Mock
    private ContainerRepository containerRepository;

    @Mock
    private com.automation.repository.SkillRepository skillRepository;

    @Mock
    private com.automation.repository.TodoRepository todoRepository;

    @Mock
    private WorkflowVersionService workflowVersionService;

    @Mock
    private ExecutionBroadcaster broadcaster;

    @Mock
    private ExecutorService virtualThreadExecutor;

    @Mock
    private BlockExecutor simpleBlockExecutor;

    @Mock
    private AgentInstructionsService agentInstructionsService;

    @Mock
    private ActivityLogService activityLogService;

    @Mock
    private WorkflowMemoryService workflowMemoryService;

    @Mock
    private ProjectContextService projectContextService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TemplateVerificationService templateVerificationService;

    @Mock
    private SystemIdentityService systemIdentity;

    private WorkflowExecutor workflowExecutor;

    private Workflow testWorkflow;
    private Project testProject;

    @BeforeEach
    void setUp() {
        // Run submitted tasks synchronously on the calling thread
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(virtualThreadExecutor).submit(any(Runnable.class));

        lenient().when(systemIdentity.getName()).thenReturn("MySuricates");
        lenient().when(systemIdentity.getVersion()).thenReturn("0.2.3");

        workflowExecutor = new WorkflowExecutor(
                List.of(simpleBlockExecutor),
                containerService,
                commandExecutorService,
                runRepository,
                stepResultRepository,
                containerRepository,
                skillRepository,
                broadcaster,
                virtualThreadExecutor,
                new DynamicSemaphore(10),
                agentInstructionsService,
                activityLogService,
                workflowMemoryService,
                projectContextService,
                eventPublisher,
                templateVerificationService,
                systemIdentity,
                todoRepository,
                workflowVersionService,
                new WorkflowExecutionRegistry()
        );

        testProject = Project.builder()
                .id("proj-1")
                .name("Test Project")
                .type(ProjectType.CODE)
                .path("/workspace/test")
                .build();

        testWorkflow = Workflow.builder()
                .id("wf-1")
                .project(testProject)
                .name("Test Workflow")
                .blocks(new ArrayList<>(List.of(
                        BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo 1"))).build(),
                        BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo 2"))).build()
                )))
                .activatedSkills(new ArrayList<>())
                .build();

        lenient().when(containerService.getDockerContainerId(any())).thenReturn("docker-container-1");
        lenient().when(agentInstructionsService.buildInstructionPrefix(any())).thenReturn("");
        lenient().when(simpleBlockExecutor.supports("simple")).thenReturn(true);
        lenient().when(runRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            return run;
        });
        lenient().when(runRepository.findById("run-1")).thenAnswer(inv -> {
            WorkflowRun run = WorkflowRun.builder()
                    .id("run-1").workflow(testWorkflow).status(RunStatus.RUNNING).startedAt(java.time.Instant.now()).build();
            return Optional.of(run);
        });
    }

    /** Captures the last WorkflowRun passed to runRepository.save() */
    private WorkflowRun captureLastSavedRun() {
        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        List<WorkflowRun> saved = captor.getAllValues();
        return saved.get(saved.size() - 1);
    }

    @Test
    void execute_returnsRunningImmediately() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        WorkflowRun result = workflowExecutor.execute(testWorkflow, null);

        // execute() returns immediately with RUNNING status
        assertThat(result.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void execute_allBlocksSucceed_savesCompleted() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getDurationMs()).isNotNull();
    }

    @Test
    void execute_blockFails_withStopOnFailure_savesFailedRun() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed);

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError()).isNotNull();
    }

    @Test
    void execute_blockFails_withContinueOnFailure_continuesExecution() {
        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder()
                        .type("simple")
                        .config(new HashMap<>(Map.of("command", "fail", "on_failure", "continue")))
                        .build(),
                BlockDefinition.builder()
                        .type("simple")
                        .config(new HashMap<>(Map.of("command", "pass")))
                        .build()
        )));

        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        StepResult success = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed, success);

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(simpleBlockExecutor, times(2)).execute(any(), any());
    }

    @Test
    void execute_noMatchingExecutor_throwsAndFails() {
        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("unknown-type").config(new HashMap<>()).build()
        )));
        when(simpleBlockExecutor.supports("unknown-type")).thenReturn(false);

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError()).containsKey("message");
    }

    @Test
    void execute_setsInputContextVariables() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        Map<String, Object> inputContext = Map.of("branch", "main", "env", "production");
        workflowExecutor.execute(testWorkflow, inputContext);

        verify(simpleBlockExecutor, times(2)).execute(any(), any());
    }

    @Test
    void execute_seedsSystemVariables_includingProjectPath() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        ExecutionContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getVariable("project_path")).isEqualTo("/workspace/test");
        assertThat(ctx.getProjectPath()).isEqualTo("/workspace/test");
        assertThat(ctx.getVariable("workflow_id")).isEqualTo("wf-1");
        assertThat(ctx.getVariable("workflow_name")).isEqualTo("Test Workflow");
        assertThat(ctx.getVariable("workflow_run_id")).isEqualTo("run-1");
        assertThat(ctx.getVariable("started_at")).isNotNull();
        assertThat(ctx.getVariable("home_dir")).isNotNull();
        assertThat(ctx.resolveTemplate("cd {{ project_path }}")).isEqualTo("cd /workspace/test");
    }

    @Test
    void execute_seedsRunTimestamp_inPathSafeFormat() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        ExecutionContext ctx = ctxCaptor.getValue();
        Object ts = ctx.getVariable("run_timestamp");
        assertThat(ts).isNotNull();
        // yyyyMMdd-HHmmss — no ':' so it's safe as a filename / directory name
        assertThat(String.valueOf(ts)).matches("\\d{8}-\\d{6}");
        assertThat(ctx.resolveTemplate("reports/{{ run_timestamp }}/summary.md"))
                .matches("reports/\\d{8}-\\d{6}/summary\\.md");
    }

    @Test
    void execute_writesSkillContextToProject() {
        Skill skill = Skill.builder()
                .id("skill-1").name("Reviewer").category(SkillCategory.CODE)
                .aiContext("Review context").build();
        testWorkflow.setActivatedSkills(new ArrayList<>(List.of(skill)));

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        verify(commandExecutorService).writeSkillContextToProject(
                eq("/workspace/test"), eq(testWorkflow.getActivatedSkills()));
    }

    @Test
    void execute_perBlockSkills_mergesAndRestores() {
        Skill workflowSkill = Skill.builder()
                .id("skill-wf").name("Workflow Skill").category(SkillCategory.CODE)
                .aiContext("Workflow context").build();
        Skill blockSkill = Skill.builder()
                .id("skill-block").name("Block Skill").category(SkillCategory.DATA)
                .aiContext("Block context").build();
        testWorkflow.setActivatedSkills(new ArrayList<>(List.of(workflowSkill)));

        // Block 0: no per-block skills, Block 1: has per-block skill
        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo 1"))).build(),
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "echo 2")))
                        .skillIds(new ArrayList<>(List.of("skill-block"))).build()
        )));

        when(skillRepository.findAllById(Set.of("skill-block"))).thenReturn(List.of(blockSkill));

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        // Verify: baseline write + merged write for block 1 + restore after block 1
        ArgumentCaptor<List<Skill>> skillCaptor = ArgumentCaptor.forClass(List.class);
        verify(commandExecutorService, atLeast(3)).writeSkillContextToProject(eq("/workspace/test"), skillCaptor.capture());

        List<List<Skill>> allCalls = skillCaptor.getAllValues();
        // First call: baseline (workflow skills only)
        assertThat(allCalls.get(0)).containsExactly(workflowSkill);
        // Second call: merged (workflow + block skills) for block 1
        assertThat(allCalls.get(1)).containsExactlyInAnyOrder(workflowSkill, blockSkill);
        // Third call: restore baseline after block 1
        assertThat(allCalls.get(2)).containsExactly(workflowSkill);
    }

    @Test
    void execute_broadcastsStepResults() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").durationMs(100L).build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        verify(broadcaster, atLeastOnce()).broadcastStepResult(anyString(), any(StepResult.class));
    }

    @Test
    void pauseAndResumeExecution() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        workflowExecutor.pauseExecution("non-existent-run");
        workflowExecutor.resumeExecution("non-existent-run");
    }

    @Test
    void cancelExecution_nonExistentRun_doesNotThrow() {
        workflowExecutor.cancelExecution("non-existent-run");
        // No exception expected
    }

    @Test
    void getContainerIdForRun_activeExecution_returnsContainerId() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();

        // Make execution block so we can check mid-flight
        // Since our mock runs synchronously, we check after execution
        // (the activeExecutions map is only populated during execution)
        // We need to capture the container ID during block execution
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    // During execution, the container ID should be accessible
                    String containerId = workflowExecutor.getContainerIdForRun("run-1");
                    assertThat(containerId).isEqualTo("docker-container-1");
                    return step;
                });

        workflowExecutor.execute(testWorkflow, null);
    }

    @Test
    void getContainerIdForRun_noActiveExecution_returnsNull() {
        String result = workflowExecutor.getContainerIdForRun("non-existent-run");
        assertThat(result).isNull();
    }

    @Test
    void execute_containerNotRunning_autoStartsContainer() {
        // Container not running initially
        when(containerService.getDockerContainerId(any()))
                .thenThrow(new ContainerException("No running container for project: proj-1"));

        Container autoStartedContainer = Container.builder()
                .dockerContainerId("auto-started-container")
                .status(ContainerStatus.RUNNING)
                .build();
        when(containerService.createAndStartContainer(any())).thenReturn(autoStartedContainer);

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        // Container was auto-started
        verify(containerService).createAndStartContainer(testProject);

        // WebSocket broadcasts for container_starting and container_started
        verify(broadcaster, atLeastOnce()).broadcastLogMessage(eq("run-1"), anyString(), any(Map.class));

        // Workflow completed successfully
        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void execute_mergesDefaultContextWithInputContext() {
        testWorkflow.setDefaultContext(new HashMap<>(Map.of("branch", "main", "env", "prod")));

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        Map<String, Object> inputContext = Map.of("branch", "develop");
        workflowExecutor.execute(testWorkflow, inputContext);

        // Verify merged context: branch=develop (overridden) + env=prod (default)
        ExecutionContext captured = ctxCaptor.getValue();
        assertThat(captured.getVariable("branch")).isEqualTo("develop");
        assertThat(captured.getVariable("env")).isEqualTo("prod");
    }

    @Test
    void execute_usesDefaultContextWhenNoInputProvided() {
        testWorkflow.setDefaultContext(new HashMap<>(Map.of("branch", "main")));

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        // Verify default context was applied
        ExecutionContext captured = ctxCaptor.getValue();
        assertThat(captured.getVariable("branch")).isEqualTo("main");
    }

    @Test
    void execute_lastBlockOutputContainsGateStatus_mergesIntoRunMetadata() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("Analysis done.\n\ngate_status: RED\ngate_category: lint-errors\ngate_description: Found 3 issues")
                .build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        // Single-block workflow so the last block has the gate_status output
        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "analyze"))).build()
        )));

        workflowExecutor.execute(testWorkflow, null);

        ArgumentCaptor<WorkflowRunCompletedEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowRunCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkflowRunCompletedEvent event = eventCaptor.getValue();
        assertThat(event.getRunMetadata()).containsEntry("gate_status", "RED");
        assertThat(event.getRunMetadata()).containsEntry("gate_category", "lint-errors");
        assertThat(event.getRunMetadata()).containsEntry("gate_description", "Found 3 issues");
    }

    @Test
    void execute_lastBlockOutputWithoutGateStatus_noGateMetadataAdded() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("Everything looks fine, no issues.")
                .build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        ArgumentCaptor<WorkflowRunCompletedEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowRunCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkflowRunCompletedEvent event = eventCaptor.getValue();
        assertThat(event.getRunMetadata()).doesNotContainKey("gate_status");
        assertThat(event.getRunMetadata()).doesNotContainKey("gate_category");
        assertThat(event.getRunMetadata()).doesNotContainKey("gate_description");
    }

    @Test
    void execute_publishesWorkflowRunCompletedEvent() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        ArgumentCaptor<WorkflowRunCompletedEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowRunCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkflowRunCompletedEvent event = eventCaptor.getValue();
        assertThat(event.getWorkflowId()).isEqualTo("wf-1");
        assertThat(event.getWorkflowName()).isEqualTo("Test Workflow");
        assertThat(event.getProjectId()).isEqualTo("proj-1");
        assertThat(event.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void cleanupZombieRuns_marksRunningRunsAsInterrupted() {
        WorkflowRun orphan1 = WorkflowRun.builder().id("orphan-1").status(RunStatus.RUNNING).startedAt(Instant.now()).build();
        WorkflowRun orphan2 = WorkflowRun.builder().id("orphan-2").status(RunStatus.RUNNING).startedAt(Instant.now()).build();
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(orphan1, orphan2));
        when(runRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of());

        workflowExecutor.cleanupZombieRuns();

        assertThat(orphan1.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(orphan1.getCompletedAt()).isNotNull();
        assertThat(orphan1.getError()).containsKey("message");
        assertThat(orphan2.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        verify(runRepository, times(2)).save(any(WorkflowRun.class));
    }

    @Test
    void cleanupZombieRuns_noZombies_doesNothing() {
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
        when(runRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of());

        workflowExecutor.cleanupZombieRuns();

        verify(runRepository, never()).save(any(WorkflowRun.class));
    }

    @Test
    void execute_containerStartFails_continuesWithHostExecution() {
        // Both getDockerContainerId and createAndStartContainer fail
        when(containerService.getDockerContainerId(any()))
                .thenThrow(new ContainerException("No container"));
        when(containerService.createAndStartContainer(any()))
                .thenThrow(new RuntimeException("Docker unavailable"));

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        // Workflow completed despite no container
        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void execute_templateVerification_recordedOnCompletion() {
        testWorkflow.setTemplateId("template-1");

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        verify(templateVerificationService).recordSuccessfulRun(
                eq("wf-1"), eq("template-1"), eq("proj-1"), eq("run-1"));
    }

    @Test
    void execute_templateVerification_notCalledWithoutTemplateId() {
        // testWorkflow.templateId is null by default
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        verify(templateVerificationService, never()).recordSuccessfulRun(any(), any(), any(), any());
    }

    @Test
    void execute_templateVerification_failureDoesNotAbortExecution() {
        testWorkflow.setTemplateId("template-1");

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);
        doThrow(new RuntimeException("DB error"))
                .when(templateVerificationService).recordSuccessfulRun(any(), any(), any(), any());

        workflowExecutor.execute(testWorkflow, null);

        // Workflow should still complete despite template verification failure
        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void getActiveRunCount_returnsZero_whenNoActiveRuns() {
        assertThat(workflowExecutor.getActiveRunCount()).isEqualTo(0);
    }

    @Test
    void getActiveRunCount_returnsCount_duringExecution() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    assertThat(workflowExecutor.getActiveRunCount()).isEqualTo(1);
                    return step;
                });

        workflowExecutor.execute(testWorkflow, null);

        // After execution completes, active count should be 0 again
        assertThat(workflowExecutor.getActiveRunCount()).isEqualTo(0);
    }

    @Test
    void executeAdHocCommand_noActiveRun_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
                WorkflowExecutionException.class,
                () -> workflowExecutor.executeAdHocCommand("non-existent", "echo hello")
        );
    }

    @Test
    void execute_writesWorkflowMemoryAndProjectContext() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        verify(workflowMemoryService).writeActiveMemoryToProject("wf-1", "/workspace/test");
        verify(projectContextService).writeProjectContextToProject(any(), eq("/workspace/test"));
    }

    @Test
    void execute_stepOutputVariables_setCorrectly() {
        StepResult step1 = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("output-from-step-0").build();
        StepResult step2 = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("output-from-step-1").build();

        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step1, step2);

        workflowExecutor.execute(testWorkflow, null);

        // After both blocks, context should have step output variables
        ExecutionContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getVariable("step_0_output")).isEqualTo("output-from-step-0");
        assertThat(ctx.getVariable("step_1_output")).isEqualTo("output-from-step-1");
    }

    @Test
    void execute_failedRun_publishesEventWithErrorInfo() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("big error").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed);

        workflowExecutor.execute(testWorkflow, null);

        ArgumentCaptor<WorkflowRunCompletedEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowRunCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkflowRunCompletedEvent event = eventCaptor.getValue();
        assertThat(event.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(event.getError()).isNotNull();
    }

    @Test
    void execute_exceptionDuringExecution_setsFailedWithMessage() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Unexpected failure"));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError().get("message")).isEqualTo("Unexpected failure");
    }

    @Test
    void execute_yellowGateStatus_setsCompleted() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("Check done.\n\ngate_status: YELLOW\ngate_category: warnings\ngate_description: 2 minor issues")
                .build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "check"))).build()
        )));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getMetadata()).containsEntry("gate_status", "YELLOW");
        assertThat(saved.getMetadata()).containsEntry("gate_category", "warnings");
    }

    @Test
    void execute_yellowGateStatus_logsWarning() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("gate_status: YELLOW\ngate_category: lint")
                .build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "lint"))).build()
        )));

        workflowExecutor.execute(testWorkflow, null);

        verify(activityLogService).warn(eq(ActivityLog.LogCategory.EXECUTION),
                contains("completed with warnings"), eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
    }

    @Test
    void execute_greenGateStatus_setsCompleted() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("All good.\n\ngate_status: GREEN\ngate_category: quality")
                .build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "check"))).build()
        )));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void execute_multipleBlocks_highestGateSeverityWins() {
        // First block: GREEN, second block: YELLOW — YELLOW should win
        StepResult greenStep = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("gate_status: GREEN\ngate_category: tests")
                .build();
        StepResult yellowStep = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED)
                .output("gate_status: YELLOW\ngate_category: lint\ngate_description: 2 warnings")
                .build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(greenStep, yellowStep);

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getMetadata()).containsEntry("gate_status", "YELLOW");
        assertThat(saved.getMetadata()).containsEntry("gate_category", "lint");
    }

    @Test
    void execute_redGateStatus_stillSetsCompleted() {
        // RED gate doesn't change RunStatus — AutoTodoService handles it via event
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("gate_status: RED\ngate_category: security\ngate_description: critical vulnerability")
                .build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "scan"))).build()
        )));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        // RED gate: run still COMPLETED (not FAILED), gate info in metadata
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getMetadata()).containsEntry("gate_status", "RED");
    }

    @Test
    void execute_setsSystemIdentityInRunMetadata() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getMetadata()).containsEntry("system", "MySuricates");
        assertThat(saved.getMetadata()).containsEntry("systemVersion", "0.2.3");
    }

    @Test
    void cancelExecution_duringExecution_marksRunCancelled() {
        // Arrange: cancel run mid-flight (after block 0, before block 1)
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    workflowExecutor.cancelExecution("run-1");
                    return step;
                });

        // Act
        workflowExecutor.execute(testWorkflow, null);

        // Assert: only first block ran, run was saved with CANCELLED
        verify(simpleBlockExecutor, times(1)).execute(any(BlockDefinition.class), any(ExecutionContext.class));
        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void cancelExecution_ghostRun_updatesPersistedStatusToCancelled() {
        // Run exists in DB but no active execution context in the registry
        // (simulates a stale RUNNING row after backend crash/restart).
        // findById("run-1") is already mocked in setUp to return a RUNNING run.

        workflowExecutor.cancelExecution("run-1");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        WorkflowRun saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void getActiveRunCount_noExecution_returnsZero() {
        assertThat(workflowExecutor.getActiveRunCount()).isZero();
    }

    @Test
    void isRunActive_inactiveRun_returnsFalse() {
        assertThat(workflowExecutor.isRunActive("non-existent-run")).isFalse();
    }
}
