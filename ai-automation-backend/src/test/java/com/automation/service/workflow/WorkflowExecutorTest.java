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
import java.util.Optional;
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
    private com.automation.service.dlq.DeadLetterService deadLetterService;

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
    private WorkflowExecutionRegistry registry;

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

        registry = new WorkflowExecutionRegistry();
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
                new ExecutionContextFactory(workflowVersionService, runRepository, systemIdentity,
                        new WorkflowContextDataService(runRepository, stepResultRepository, todoRepository),
                        agentInstructionsService, commandExecutorService, workflowMemoryService, projectContextService),
                activityLogService,
                eventPublisher,
                new RunCompletionHandler(templateVerificationService, activityLogService, deadLetterService),
                registry,
                new BlockOutputValidator()
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
    void execute_blockThrowsError_savesFailedRunInsteadOfLeavingRunning() {
        // Reproduces the 2026-07-27 incident: `mvn test` rewrote target/classes under the
        // running JVM, so lazily loaded classes vanished and blocks died with an Error
        // (not an Exception). The run then stayed RUNNING until the ghost watchdog reaped it.
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new NoClassDefFoundError("com/automation/service/todo/GateStatusResolver$1"));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError()).containsKey("message");
        assertThat(String.valueOf(saved.getError().get("message")))
                .startsWith("NoClassDefFoundError: ")
                .contains("GateStatusResolver");
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

    /**
     * Regression test for H1: the platform API key MUST NOT be exposed as a
     * {@code {{ api_key }}} template variable. Doing so would resolve the literal
     * key into block prompts, then into LLM output, and finally persist it in
     * {@code StepResult.output}. The key is now delivered via the
     * {@code AUTOMATION_API_KEY} env var on the spawned CLI process instead.
     */
    @Test
    void execute_injectsOpenTodosWithIdAndTags() {
        com.automation.entity.Todo todo = com.automation.entity.Todo.builder()
                .id("abc12345-1111-2222-3333-444455556666")
                .title("Fix endpoint X")
                .status(com.automation.entity.Todo.Status.OPEN)
                .priority(com.automation.entity.Todo.Priority.HIGH)
                .tags("audit:api-consistency,backend")
                .build();
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), any()))
                .thenReturn(List.of(todo));

        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        String openTodos = (String) ctxCaptor.getValue().getVariable("open_todos");
        assertThat(openTodos)
                .as("open_todos must contain id, priority, tags, title for filter-by-tag use cases")
                .contains("abc12345-1111-2222-3333-444455556666")
                .contains("[HIGH]")
                .contains("[audit:api-consistency,backend]")
                .contains("Fix endpoint X");
    }

    @Test
    void execute_openTodosWithoutTags_omitsTagBracket() {
        com.automation.entity.Todo todo = com.automation.entity.Todo.builder()
                .id("def-id")
                .title("Plain TODO")
                .status(com.automation.entity.Todo.Status.OPEN)
                .priority(com.automation.entity.Todo.Priority.MEDIUM)
                .tags(null)
                .build();
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), any()))
                .thenReturn(List.of(todo));

        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        String openTodos = (String) ctxCaptor.getValue().getVariable("open_todos");
        // No tag-bracket when tags are null: "- def-id [MEDIUM] Plain TODO"
        assertThat(openTodos).contains("- def-id [MEDIUM] Plain TODO");
    }

    @Test
    void execute_noOpenTodos_injectsPlaceholder() {
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), any()))
                .thenReturn(List.of());

        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        assertThat(ctxCaptor.getValue().getVariable("open_todos"))
                .isEqualTo("(keine offenen TODOs)");
    }

    @Test
    void execute_doesNotInjectApiKeyAsTemplateVariable() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        ExecutionContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getVariable("api_key"))
                .as("api_key must never be a template variable — it would leak into persisted StepResult.output")
                .isNull();
        // Templates referencing {{ api_key }} resolve to the literal placeholder
        // (no substitution), making the leak vector inert.
        assertThat(ctx.resolveTemplate("X-API-Key: {{ api_key }}"))
                .isEqualTo("X-API-Key: {{ api_key }}");
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
                    String containerId = registry.getContainerIdForRun("run-1");
                    assertThat(containerId).isEqualTo("docker-container-1");
                    return step;
                });

        workflowExecutor.execute(testWorkflow, null);
    }

    @Test
    void getContainerIdForRun_noActiveExecution_returnsNull() {
        String result = registry.getContainerIdForRun("non-existent-run");
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

    // --- resumeFromStep (previously untested; safety net for the WorkflowSnapshot refactoring) ----

    @Test
    void resumeFromStep_skipsCompletedSteps_seedsPreviousOutput_andCompletes() {
        WorkflowRun interrupted = WorkflowRun.builder().id("run-1").workflow(testWorkflow).workflowName("Test Workflow")
                .status(RunStatus.INTERRUPTED).startedAt(Instant.now()).inputContext(Map.of("foo", "bar")).build();
        WorkflowRun running = WorkflowRun.builder().id("run-1").workflow(testWorkflow).workflowName("Test Workflow")
                .status(RunStatus.RUNNING).startedAt(Instant.now()).build();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(interrupted)).thenReturn(Optional.of(running));
        StepResult done = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("first-output").build();
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of(done));
        List<String> previousOutputSeenByBlock = new java.util.ArrayList<>();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    previousOutputSeenByBlock.add(((ExecutionContext) inv.getArgument(1)).getPreviousOutput());
                    return StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("second-output").build();
                });

        workflowExecutor.resumeFromStep("run-1");

        ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(simpleBlockExecutor, times(1)).execute(any(BlockDefinition.class), ctx.capture());   // only block 2 ran
        assertThat(ctx.getValue().getVariable("step_0_output")).isEqualTo("first-output");
        assertThat(ctx.getValue().getVariable("foo")).isEqualTo("bar");
        assertThat(previousOutputSeenByBlock).containsExactly("first-output");   // seed from the last completed step
        assertThat(captureLastSavedRun().getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(broadcaster).broadcastRunStatus("run-1", RunStatus.RUNNING);
    }

    @Test
    void resumeFromStep_nonInterruptedRun_throws() {
        WorkflowRun completed = WorkflowRun.builder().id("run-1").workflow(testWorkflow).status(RunStatus.COMPLETED).build();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> workflowExecutor.resumeFromStep("run-1"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("cannot be resumed");
    }

    @Test
    void resumeFromStep_noCompletedSteps_fallsBackToFullRetry() {
        WorkflowRun interrupted = WorkflowRun.builder().id("run-1").workflow(testWorkflow).workflowName("Test Workflow")
                .status(RunStatus.INTERRUPTED).startedAt(Instant.now()).build();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(interrupted));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of());
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());

        workflowExecutor.resumeFromStep("run-1");

        verify(simpleBlockExecutor, times(2)).execute(any(BlockDefinition.class), any(ExecutionContext.class));   // both blocks
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
        assertThat(registry.getActiveRunCount()).isEqualTo(0);
    }

    @Test
    void getActiveRunCount_returnsCount_duringExecution() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    assertThat(registry.getActiveRunCount()).isEqualTo(1);
                    return step;
                });

        workflowExecutor.execute(testWorkflow, null);

        // After execution completes, active count should be 0 again
        assertThat(registry.getActiveRunCount()).isEqualTo(0);
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
    void execute_failedStep_persistsErrorTail() {
        String output = "x".repeat(970) + "HTTP 500 Internal Server Error";
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output(output).build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed);

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        String message = (String) saved.getError().get("message");
        assertThat(message).endsWith("HTTP 500 Internal Server Error");
        assertThat(message).contains("[... skipped 500 of 1000 characters ...]");
    }

    @Test
    void execute_exceptionDuringExecution_setsFailedWithMessage() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Unexpected failure"));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError().get("message")).isEqualTo("RuntimeException: Unexpected failure");
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
    @SuppressWarnings("unchecked")
    void execute_aggregatesGateSummaryAcrossSteps() {
        // Two-block workflow with mixed gates -> gate_summary aggregates both items + counts.
        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "first"))).build(),
                BlockDefinition.builder().type("simple").config(new HashMap<>(Map.of("command", "second"))).build()
        )));
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
        Map<String, Object> summary = (Map<String, Object>) saved.getMetadata().get("gate_summary");
        assertThat(summary).isNotNull();
        assertThat(summary).containsEntry("highest", "YELLOW");
        Map<String, Integer> counts = (Map<String, Integer>) summary.get("counts_by_status");
        assertThat(counts).containsEntry("GREEN", 1).containsEntry("YELLOW", 1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) summary.get("items");
        assertThat(items).hasSize(2);
        assertThat(items).anyMatch(i -> "GREEN".equals(i.get("status")) && "tests".equals(i.get("category")));
        assertThat(items).anyMatch(i -> "YELLOW".equals(i.get("status")) && "lint".equals(i.get("category")));
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
        assertThat(registry.getActiveRunCount()).isZero();
    }

    @Test
    void isRunActive_inactiveRun_returnsFalse() {
        assertThat(registry.isRunActive("non-existent-run")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Error-path tests (TODO 0bd95702): block exceptions, interrupts, sub-workflow
    // failure propagation, failRun branches, defensive try/catch blocks.
    // -------------------------------------------------------------------------

    /** Helper: re-build the executor with a custom BlockExecutor list and semaphore. */
    private WorkflowExecutor buildExecutor(List<BlockExecutor> executors, DynamicSemaphore semaphore) {
        return new WorkflowExecutor(
                executors,
                containerService,
                commandExecutorService,
                runRepository,
                stepResultRepository,
                containerRepository,
                skillRepository,
                broadcaster,
                virtualThreadExecutor,
                semaphore,
                new ExecutionContextFactory(workflowVersionService, runRepository, systemIdentity,
                        new WorkflowContextDataService(runRepository, stepResultRepository, todoRepository),
                        agentInstructionsService, commandExecutorService, workflowMemoryService, projectContextService),
                activityLogService,
                eventPublisher,
                new RunCompletionHandler(templateVerificationService, activityLogService, deadLetterService),
                new WorkflowExecutionRegistry(),
                new BlockOutputValidator()
        );
    }

    // --- Group A: Block exception paths (WorkflowExecutor.java:472–480) ------

    @Test
    void execute_blockThrowsWorkflowExecutionException_savesFailedWithMessage() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new WorkflowExecutionException("custom block failure"));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError().get("message")).isEqualTo("WorkflowExecutionException: custom block failure");
        verify(eventPublisher).publishEvent(any(WorkflowRunCompletedEvent.class));
    }

    @Test
    void execute_blockThrowsExceptionWithNullMessage_fallsBackToClassName() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException());

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError().get("message")).isEqualTo("RuntimeException: java.lang.RuntimeException");
    }

    @Test
    void execute_secondBlockThrows_firstBlockStillSavedAsCompleted() {
        StepResult ok = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(ok)
                .thenThrow(new RuntimeException("second block crashed"));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun savedRun = captureLastSavedRun();
        assertThat(savedRun.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(savedRun.getError().get("message")).isEqualTo("RuntimeException: second block crashed");

        // First block's StepResult must have been persisted before the crash
        ArgumentCaptor<StepResult> stepCaptor = ArgumentCaptor.forClass(StepResult.class);
        verify(stepResultRepository, atLeastOnce()).save(stepCaptor.capture());
        boolean firstBlockCompleted = stepCaptor.getAllValues().stream()
                .anyMatch(s -> s.getStatus() == StepStatus.COMPLETED);
        assertThat(firstBlockCompleted).isTrue();
    }

    @Test
    void execute_blockThrows_eventPublishedAndBroadcastFinalStatus() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("boom"));

        workflowExecutor.execute(testWorkflow, null);

        // Both broadcaster and eventPublisher must fire from the finally block
        verify(broadcaster).broadcastRunStatus("run-1", RunStatus.FAILED);
        ArgumentCaptor<WorkflowRunCompletedEvent> evt = ArgumentCaptor.forClass(WorkflowRunCompletedEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(evt.getValue().getError()).isNotNull();
    }

    // --- Group B: Semaphore / Interrupt (WorkflowExecutor.java:468–471) ------

    @Test
    void execute_interruptedDuringSemaphoreAcquire_callsFailRun() throws InterruptedException {
        DynamicSemaphore mockSemaphore = mock(DynamicSemaphore.class);
        doThrow(new InterruptedException("simulated")).when(mockSemaphore).acquire();
        WorkflowExecutor executor = buildExecutor(List.of(simpleBlockExecutor), mockSemaphore);

        executor.execute(testWorkflow, null);

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        boolean savedAsFailedDueToInterrupt = captor.getAllValues().stream()
                .anyMatch(r -> r.getStatus() == RunStatus.FAILED
                        && r.getError() != null
                        && "Interrupted while waiting for execution slot".equals(r.getError().get("message")));
        assertThat(savedAsFailedDueToInterrupt).isTrue();

        // Interrupt status must be re-set on the calling thread
        assertThat(Thread.interrupted()).isTrue();

        // Block executor must never have run
        verify(simpleBlockExecutor, never()).execute(any(), any());
    }

    @Test
    void execute_finallyAlwaysReleasesSemaphore_evenAfterException() {
        DynamicSemaphore singleSlot = new DynamicSemaphore(1);
        WorkflowExecutor executor = buildExecutor(List.of(simpleBlockExecutor), singleSlot);

        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("first run boom"))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());

        executor.execute(testWorkflow, null);
        // If the semaphore wasn't released, this second execute() would block forever
        // (synchronous virtualThreadExecutor + acquire() in same thread).
        executor.execute(testWorkflow, null);

        // Verify both attempts ran the block executor (1× thrown, then 2 successful blocks)
        verify(simpleBlockExecutor, atLeast(2)).execute(any(), any());
        // 1 permit available again at the end
        assertThat(singleSlot.availablePermits()).isEqualTo(1);
    }

    // --- Group C: Registry cleanup (WorkflowExecutor.java:485) ---------------

    @Test
    void execute_blockThrows_registryUnregistersRun() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("crash"));

        workflowExecutor.execute(testWorkflow, null);

        assertThat(registry.isRunActive("run-1")).isFalse();
        assertThat(registry.getActiveRunCount()).isZero();
    }

    // --- Group D: Sub-workflow failure propagation ---------------------------

    @Test
    void execute_subWorkflowBlockReturnsFailed_parentRunFails() {
        BlockExecutor subWorkflowExecutor = mock(BlockExecutor.class);
        lenient().when(subWorkflowExecutor.supports("sub-workflow")).thenReturn(true);
        lenient().when(simpleBlockExecutor.supports("sub-workflow")).thenReturn(false);
        when(subWorkflowExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder()
                        .stepIndex(0).status(StepStatus.FAILED)
                        .output("Sub-workflow run-X failed: timeout")
                        .build());

        WorkflowExecutor executor = buildExecutor(
                List.of(simpleBlockExecutor, subWorkflowExecutor),
                new DynamicSemaphore(10));

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("sub-workflow")
                        .config(new HashMap<>(Map.of("workflow_id", "child-wf"))).build()
        )));

        executor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError()).isNotNull();
        assertThat(String.valueOf(saved.getError().get("message"))).contains("Sub-workflow run-X failed");
    }

    @Test
    void execute_subWorkflowBlockThrows_parentRunFails() {
        BlockExecutor subWorkflowExecutor = mock(BlockExecutor.class);
        lenient().when(subWorkflowExecutor.supports("sub-workflow")).thenReturn(true);
        lenient().when(simpleBlockExecutor.supports("sub-workflow")).thenReturn(false);
        when(subWorkflowExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new WorkflowExecutionException("Sub-workflow timed out after 30 minutes"));

        WorkflowExecutor executor = buildExecutor(
                List.of(simpleBlockExecutor, subWorkflowExecutor),
                new DynamicSemaphore(10));

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("sub-workflow")
                        .config(new HashMap<>(Map.of("workflow_id", "child-wf"))).build()
        )));

        executor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError().get("message")).isEqualTo("WorkflowExecutionException: Sub-workflow timed out after 30 minutes");
    }

    // --- Group E: failRun path (WorkflowExecutor.java:516–518, 527–542) ------

    @Test
    void execute_runNotInRepository_callsFailRunInFinally() {
        // First findById (line 190) returns empty → orElseThrow → caught.
        // Second findById (inside failRun line 529) returns a RUNNING run that gets force-failed.
        when(runRepository.findById("run-1"))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.of(WorkflowRun.builder()
                        .id("run-1").workflow(testWorkflow).status(RunStatus.RUNNING)
                        .startedAt(Instant.now()).build()));

        workflowExecutor.execute(testWorkflow, null);

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        boolean savedAsFailedDbLoad = captor.getAllValues().stream()
                .anyMatch(r -> r.getStatus() == RunStatus.FAILED
                        && r.getError() != null
                        && "Failed to load workflow run from database".equals(r.getError().get("message")));
        assertThat(savedAsFailedDbLoad).isTrue();
        verify(broadcaster, atLeastOnce()).broadcastRunStatus("run-1", RunStatus.FAILED);
    }

    // --- Group F: Defensive paths --------------------------------------------

    @Test
    void execute_eventPublisherThrows_runStillSavedAndBroadcast() {
        StepResult ok = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(ok);
        doThrow(new RuntimeException("subscriber crashed"))
                .when(eventPublisher).publishEvent(any(WorkflowRunCompletedEvent.class));

        // Should not throw — the publish is wrapped in try/catch (Z. 506–515)
        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(broadcaster).broadcastRunStatus("run-1", RunStatus.COMPLETED);
    }

    // --- Group G: Block-type dispatching (shell/agentic/foreach/conditional) ---

    @Test
    void execute_shellBlock_dispatchesToShellExecutor() {
        BlockExecutor shellExec = mock(BlockExecutor.class);
        lenient().when(shellExec.supports("shell")).thenReturn(true);
        when(shellExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder()
                        .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("shell")
                        .config(new HashMap<>(Map.of("command", "echo hi"))).build()
        )));

        WorkflowExecutor exec = buildExecutor(List.of(shellExec), new DynamicSemaphore(10));
        exec.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(shellExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void execute_agenticBlock_dispatchesToAgenticExecutor() {
        BlockExecutor agenticExec = mock(BlockExecutor.class);
        lenient().when(agenticExec.supports("agentic")).thenReturn(true);
        when(agenticExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder()
                        .stepIndex(0).status(StepStatus.COMPLETED).output("agentic done").build());

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("agentic")
                        .config(new HashMap<>(Map.of("prompt", "do something"))).build()
        )));

        WorkflowExecutor exec = buildExecutor(List.of(agenticExec), new DynamicSemaphore(10));
        exec.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(agenticExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void execute_foreachBlock_dispatchesToForeachExecutor() {
        BlockExecutor foreachExec = mock(BlockExecutor.class);
        lenient().when(foreachExec.supports("foreach")).thenReturn(true);
        when(foreachExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder()
                        .stepIndex(0).status(StepStatus.COMPLETED).output("foreach done").build());

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("foreach")
                        .config(new HashMap<>(Map.of("items", List.of("a", "b")))).build()
        )));

        WorkflowExecutor exec = buildExecutor(List.of(foreachExec), new DynamicSemaphore(10));
        exec.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(foreachExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void execute_conditionalBlock_dispatchesToConditionalExecutor() {
        BlockExecutor conditionalExec = mock(BlockExecutor.class);
        lenient().when(conditionalExec.supports("conditional")).thenReturn(true);
        when(conditionalExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder()
                        .stepIndex(0).status(StepStatus.COMPLETED).output("condition met").build());

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("conditional")
                        .config(new HashMap<>(Map.of("condition", "true"))).build()
        )));

        WorkflowExecutor exec = buildExecutor(List.of(conditionalExec), new DynamicSemaphore(10));
        exec.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(conditionalExec, atLeastOnce()).execute(any(BlockDefinition.class), any(ExecutionContext.class));
    }

    @Test
    void execute_unknownBlockType_runFails() {
        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("nonexistent-type")
                        .config(new HashMap<>()).build()
        )));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void execute_blockExecutorThrowsRuntimeException_runFails() {
        BlockExecutor flaky = mock(BlockExecutor.class);
        lenient().when(flaky.supports("simple")).thenReturn(true);
        when(flaky.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Timeout after 30s"));

        WorkflowExecutor exec = buildExecutor(List.of(flaky), new DynamicSemaphore(10));
        exec.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError()).isNotNull();
    }

    @Test
    void execute_foreachBlockReturnsFailure_parentRunFails() {
        BlockExecutor foreachExec = mock(BlockExecutor.class);
        lenient().when(foreachExec.supports("foreach")).thenReturn(true);
        when(foreachExec.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder()
                        .stepIndex(0).status(StepStatus.FAILED).output("iteration 2 failed").build());

        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("foreach")
                        .config(new HashMap<>(Map.of("items", List.of("a", "b")))).build()
        )));

        WorkflowExecutor exec = buildExecutor(List.of(foreachExec), new DynamicSemaphore(10));
        exec.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    // ── Race fixes (TODO 32f86881): rejected submit + unregister ordering ──────────

    @Test
    void execute_executorRejectsSubmission_marksRunFailedAndThrows() {
        doThrow(new java.util.concurrent.RejectedExecutionException("shutting down"))
                .when(virtualThreadExecutor).submit(any(Runnable.class));

        assertThatThrownBy(() -> workflowExecutor.execute(testWorkflow, null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("run-1");

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(String.valueOf(saved.getError().get("message"))).contains("rejected");
        verify(broadcaster).broadcastRunStatus("run-1", RunStatus.FAILED);
    }

    @Test
    void executeBlocks_unregistersFromRegistryOnlyAfterFinalSave() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);
        List<Boolean> activeWhenTerminalStatusSaved = new ArrayList<>();
        when(runRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            if (run.getStatus() == RunStatus.COMPLETED) {
                activeWhenTerminalStatusSaved.add(registry.isRunActive("run-1"));
            }
            return run;
        });

        workflowExecutor.execute(testWorkflow, null);

        assertThat(activeWhenTerminalStatusSaved).isNotEmpty().containsOnly(true);
        assertThat(registry.isRunActive("run-1")).isFalse();
    }

    @Test
    void executeBlocks_unregistersEvenWhenFinalSaveFails() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);
        when(runRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            if (run.getStatus() == RunStatus.COMPLETED) throw new RuntimeException("db down");
            return run;
        });

        try {
            workflowExecutor.execute(testWorkflow, null);
        } catch (RuntimeException expected) {
            // the synchronous test executor propagates the failure — irrelevant here
        }

        assertThat(registry.isRunActive("run-1")).isFalse();
    }
}
