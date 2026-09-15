package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ContainerException;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleRegistry;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.automation.concurrency.DynamicSemaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private RunWorkspaceService runWorkspaceService;

    private WorkflowExecutor workflowExecutor;
    private WorkflowExecutionRegistry registry;

    /**
     * Real threads for the concurrency tests. Torn down with shutdownNow() — never close(), which
     * would block forever if an assertion failed while a run is still parked on the semaphore.
     */
    private ExecutorService realThreads;

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
                skillRepository,
                broadcaster,
                virtualThreadExecutor,
                new DynamicSemaphore(10),
                new ExecutionContextFactory(workflowVersionService, runRepository, systemIdentity,
                        new WorkflowContextDataService(runRepository, stepResultRepository, todoRepository),
                        agentInstructionsService, commandExecutorService, workflowMemoryService, projectContextService),
                activityLogService,
                new RunCompletionHandler(templateVerificationService, activityLogService, deadLetterService),
                registry,
                new BlockSkillContext(commandExecutorService),
                new BlockStepRunner(stepResultRepository, broadcaster, new BlockOutputValidator(),
                        activityLogService, containerRepository, mock(RunLiveLogService.class)),
                new RunFinalizer(runRepository, broadcaster, eventPublisher),
                runWorkspaceService,
                new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), new WorkflowDefinitionHasher(), workflowVersionService, activityLogService)
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

    @org.junit.jupiter.api.AfterEach
    void stopRealThreads() throws InterruptedException {
        if (realThreads != null) {
            realThreads.shutdownNow();
            realThreads.awaitTermination(5, TimeUnit.SECONDS);
            realThreads = null;
        }
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

        WorkflowRun result = workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        // execute() returns immediately with RUNNING status
        assertThat(result.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void execute_allBlocksSucceed_savesCompleted() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getDurationMs()).isNotNull();
    }

    @Test
    void execute_recordsTriggerSourceAndDefinitionHashOnTheRun() {
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class))).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null, null, null, TriggerSource.SCHEDULE);

        // The run row is created (first save) before the async loop reloads it
        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        WorkflowRun created = captor.getAllValues().getFirst();
        assertThat(created.getTriggerSource()).isEqualTo(TriggerSource.SCHEDULE);
        assertThat(created.getDefinitionHash()).isEqualTo(new WorkflowDefinitionHasher().hash(testWorkflow));
    }

    @Test
    void execute_blockFails_withStopOnFailure_savesFailedRun() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed);

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        workflowExecutor.execute(testWorkflow, inputContext, TriggerSource.MANUAL);

        verify(simpleBlockExecutor, times(2)).execute(any(), any());
    }

    @Test
    void execute_seedsSystemVariables_includingProjectPath() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<ExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), ctxCaptor.capture()))
                .thenReturn(step);

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        verify(broadcaster, atLeastOnce()).broadcastStepResult(anyString(), any(StepResult.class));
    }

    @Test
    void pauseAndResumeExecution() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);
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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        workflowExecutor.execute(testWorkflow, inputContext, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
    void resumeBeforeFirstCompletedStep_keepsRunAndOriginalWorkspacePolicy() {
        WorkflowRun interrupted = WorkflowRun.builder().id("run-1").workflow(testWorkflow)
                .workflowName("Test Workflow").status(RunStatus.INTERRUPTED).startedAt(Instant.now())
                .metadata(new HashMap<>(Map.of("workspace", Map.of("sourceCommit", "original"),
                        "workspacePolicy", Map.of("mode", "isolated", "source", "commit"))))
                .build();
        // The current definition no longer enables isolation; persisted run policy must win.
        testWorkflow.setMetadata(Map.of());
        when(runRepository.findById("run-1")).thenReturn(Optional.of(interrupted));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of());
        when(runWorkspaceService.prepare(eq(interrupted), eq(testProject), anyMap(), any(), eq(true)))
                .thenReturn(testProject);
        when(simpleBlockExecutor.execute(any(), any()))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());

        WorkflowRun resumed = workflowExecutor.resumeFromStep("run-1");

        assertThat(resumed.getId()).isEqualTo("run-1");
        verify(runWorkspaceService).prepare(eq(interrupted), eq(testProject),
                argThat(meta -> RunWorkspaceService.enabled(meta)), any(), eq(true));
        verify(containerService, never()).createAndStartContainer(any());
        assertThat(captureLastSavedRun().getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void cancellationDuringLastStepWinsOverProcessFailure() {
        testWorkflow.setBlocks(List.of(testWorkflow.getBlocks().getFirst()));
        when(simpleBlockExecutor.execute(any(), any())).thenAnswer(inv -> {
            ((ExecutionContext) inv.getArgument(1)).cancel();
            return StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output("terminated").build();
        });
        workflowExecutor.execute(testWorkflow, Map.of(), TriggerSource.MANUAL);
        assertThat(captureLastSavedRun().getStatus()).isEqualTo(RunStatus.CANCELLED);
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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        WorkflowRun saved = captureLastSavedRun();
        String message = (String) saved.getError().get("message");
        assertThat(message).endsWith("HTTP 500 Internal Server Error");
        assertThat(message).contains("[... skipped 500 of 1000 characters ...]");
    }

    @Test
    void execute_exceptionDuringExecution_setsFailedWithMessage() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("Unexpected failure"));

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        return buildExecutor(executors, semaphore, new WorkflowExecutionRegistry(), virtualThreadExecutor);
    }

    /**
     * Helper for the concurrency tests: the run really executes on its own (virtual) thread and the
     * test keeps a handle on the registry, so it can cancel a run while the thread is still parked.
     */
    private WorkflowExecutor buildExecutor(List<BlockExecutor> executors, DynamicSemaphore semaphore,
                                           WorkflowExecutionRegistry executionRegistry,
                                           ExecutorService threads) {
        return new WorkflowExecutor(
                executors,
                containerService,
                commandExecutorService,
                runRepository,
                stepResultRepository,
                skillRepository,
                broadcaster,
                threads,
                semaphore,
                new ExecutionContextFactory(workflowVersionService, runRepository, systemIdentity,
                        new WorkflowContextDataService(runRepository, stepResultRepository, todoRepository),
                        agentInstructionsService, commandExecutorService, workflowMemoryService, projectContextService),
                activityLogService,
                new RunCompletionHandler(templateVerificationService, activityLogService, deadLetterService),
                executionRegistry,
                new BlockSkillContext(commandExecutorService),
                new BlockStepRunner(stepResultRepository, broadcaster, new BlockOutputValidator(),
                        activityLogService, containerRepository, mock(RunLiveLogService.class)),
                new RunFinalizer(runRepository, broadcaster, eventPublisher),
                runWorkspaceService,
                new RunAdmission(ModuleRegistry.of(Map.of(), Map.of()), new WorkflowDefinitionHasher(), workflowVersionService, activityLogService)
        );
    }

    // --- Group A: Block exception paths (WorkflowExecutor.java:472–480) ------

    @Test
    void execute_blockThrowsWorkflowExecutionException_savesFailedWithMessage() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new WorkflowExecutionException("custom block failure"));

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError().get("message")).isEqualTo("WorkflowExecutionException: custom block failure");
        verify(eventPublisher).publishEvent(any(WorkflowRunCompletedEvent.class));
    }

    @Test
    void execute_blockThrowsExceptionWithNullMessage_fallsBackToClassName() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException());

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);
        // If the semaphore wasn't released, this second execute() would block forever
        // (synchronous virtualThreadExecutor + acquire() in same thread).
        executor.execute(testWorkflow, null, TriggerSource.MANUAL);

        // Verify both attempts ran the block executor (1× thrown, then 2 successful blocks)
        verify(simpleBlockExecutor, atLeast(2)).execute(any(), any());
        // 1 permit available again at the end
        assertThat(singleSlot.availablePermits()).isEqualTo(1);
    }

    @Test
    void execute_releasesPermitOnlyAfterTerminalSave() {
        // TODO 7bb17b13: a queued run must not get the slot while this run still writes its
        // terminal row on the single SQLite connection.
        DynamicSemaphore singleSlot = new DynamicSemaphore(1);
        WorkflowExecutor executor = buildExecutor(List.of(simpleBlockExecutor), singleSlot);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());
        java.util.concurrent.atomic.AtomicInteger permitsAtTerminalSave = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(runRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId("run-1");
            if (saved.getStatus() != RunStatus.RUNNING) {
                permitsAtTerminalSave.set(singleSlot.availablePermits());
            }
            return saved;
        });

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);

        assertThat(permitsAtTerminalSave.get()).as("permit still held during the terminal save").isZero();
        assertThat(singleSlot.availablePermits()).as("permit released afterwards").isEqualTo(1);
    }

    // --- Group C: Registry cleanup (WorkflowExecutor.java:485) ---------------

    @Test
    void execute_blockThrows_registryUnregistersRun() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new RuntimeException("crash"));

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        exec.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        exec.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        exec.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        exec.execute(testWorkflow, null, TriggerSource.MANUAL);

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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        exec.execute(testWorkflow, null, TriggerSource.MANUAL);

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
        exec.execute(testWorkflow, null, TriggerSource.MANUAL);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    // ── Race fixes (TODO 32f86881): rejected submit + unregister ordering ──────────

    @Test
    void execute_executorRejectsSubmission_marksRunFailedAndThrows() {
        doThrow(new java.util.concurrent.RejectedExecutionException("shutting down"))
                .when(virtualThreadExecutor).submit(any(Runnable.class));

        assertThatThrownBy(() -> workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL))
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

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        assertThat(activeWhenTerminalStatusSaved).isNotEmpty().containsOnly(true);
        assertThat(registry.isRunActive("run-1")).isFalse();
    }

    // ── Container activity touch (TODO cd1685ff) ──────────────────────────────────

    @Test
    void execute_withContainer_touchesContainerActivityWithoutLoadingTheEntity() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        // A single JPQL update per step — not find + detached merge, which would drag the
        // eagerly mapped Project/Container graph through a second transaction.
        verify(containerRepository, times(2)).touchActivity(eq("docker-container-1"), any(Instant.class));
        verify(containerRepository, never()).findByDockerContainerId(anyString());
        verify(containerRepository, never()).save(any());
    }

    @Test
    void execute_containerActivityUpdateFails_doesNotFailTheRun() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());
        when(containerRepository.touchActivity(anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        assertThat(captureLastSavedRun().getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    // ── Cancel before thread registration (TODO 45d09d19) ──────────────────────────
    // A run is observable in the DB (RUNNING) the moment execute() returns, but it used to
    // become *controllable* only after the execution thread had passed the semaphore and the
    // container start. A cancel inside that window hit the ghost branch and was then overrun
    // by the thread that kept going. These tests pin the window shut from both ends.

    /** Stateful run stub: save() keeps the instance, findById() hands the same one back. */
    private AtomicReference<WorkflowRun> statefulRunRepository() {
        AtomicReference<WorkflowRun> stored = new AtomicReference<>();
        when(runRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            stored.set(run);
            return run;
        });
        when(runRepository.findById("run-1")).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        return stored;
    }

    /**
     * Upper bound for condition polling / latches. Generous on purpose (TODO 7c8e57c3): under a
     * load average of 30+ with several parallel Maven runs the 5 s bound tripped once; a
     * condition-based wait only costs the time it really needs, so the bound may be large.
     */
    private static final long AWAIT_SECONDS = 30;

    /** Polls the stateful stub until the run leaves RUNNING (no Awaitility dependency). */
    private WorkflowRun awaitTerminalStatus(AtomicReference<WorkflowRun> stored) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(AWAIT_SECONDS).toNanos();
        RunStatus last = null;
        while (System.nanoTime() < deadline) {
            WorkflowRun current = stored.get();
            last = current == null ? null : current.getStatus();
            if (last != null && last != RunStatus.RUNNING) {
                return current;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for a terminal run status", e);
            }
        }
        throw new AssertionError("run did not reach a terminal status within " + AWAIT_SECONDS + "s; last status was " + last);
    }

    /**
     * The executor saves the terminal status first and only then unregisters the run and releases the
     * permit (in its finally block). Tests running on real threads must therefore wait for the
     * release instead of asserting it right after {@link #awaitTerminalStatus}.
     */
    private static void awaitCondition(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(AWAIT_SECONDS).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(what + " did not happen within " + AWAIT_SECONDS + "s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what, e);
            }
        }
    }

    private static void awaitLatch(CountDownLatch latch, String what) {
        try {
            assertThat(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as(what).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + what, e);
        }
    }

    @Test
    void cancelExecution_duringRunInitialisation_isHonoured() {
        // The run row is listable from the first save on, so a second observer can cancel while
        // execute() is still assembling the snapshot / writing the input context. That cancel must
        // not fall through to the ghost branch and then be overrun by the execution thread.
        AtomicReference<WorkflowRun> stored = statefulRunRepository();
        // Would let the run finish COMPLETED if the cancel were dropped — never reached once it isn't.
        lenient().when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());
        AtomicBoolean cancelled = new AtomicBoolean();
        doAnswer(inv -> {
            if (cancelled.compareAndSet(false, true)) {
                workflowExecutor.cancelExecution("run-1");
            }
            return null;
        }).when(activityLogService).info(any(), anyString(), anyString(), anyString(), anyString());

        workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);

        assertThat(stored.get().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(stored.get().getError()).isNullOrEmpty();
        verify(simpleBlockExecutor, never()).execute(any(), any());
        verify(containerService, never()).getDockerContainerId(any());
        verify(stepResultRepository, never()).save(any());
        assertThat(registry.isRunActive("run-1")).isFalse();
    }

    @Test
    void execute_initialisationFails_leavesNoActiveRunBehind() {
        // Registering before the init work means the init-failure path has to clean up again —
        // otherwise the run stays "active" forever and the ghost watchdog skips it on purpose.
        AtomicInteger saves = new AtomicInteger();
        when(runRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            if (saves.incrementAndGet() == 2) throw new IllegalStateException("db down");
            return run;
        });

        assertThatThrownBy(() -> workflowExecutor.execute(testWorkflow, Map.of("k", "v"), TriggerSource.MANUAL))
                .isInstanceOf(WorkflowExecutionException.class);

        assertThat(registry.isRunActive("run-1")).isFalse();
        assertThat(registry.getActiveRunCount()).isZero();
        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(String.valueOf(saved.getError().get("message")))
                .contains("Failed to initialize workflow data");
    }

    @Test
    void execute_activityLogFailsDuringInit_marksRunFailedAndUnregisters() {
        doThrow(new RuntimeException("activity log down"))
                .when(activityLogService).info(any(), anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL))
                .isInstanceOf(WorkflowExecutionException.class);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(String.valueOf(saved.getError().get("message"))).contains("activity log down");
        assertThat(registry.isRunActive("run-1")).isFalse();
        verify(simpleBlockExecutor, never()).execute(any(), any());
    }

    @Test
    void execute_errorDuringInit_unregistersAndPropagatesTheError() {
        // An Error must not be swallowed and must not leave the run registered — the watchdog
        // protects registered runs on purpose, so the run would hang RUNNING until a restart.
        doThrow(new NoClassDefFoundError("boom"))
                .when(activityLogService).info(any(), anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL))
                .isInstanceOf(NoClassDefFoundError.class);

        assertThat(captureLastSavedRun().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(registry.isRunActive("run-1")).isFalse();
        assertThat(registry.getActiveRunCount()).isZero();
    }

    @Test
    void execute_submitThrowsError_unregistersAndPropagatesTheError() {
        // Same hole one step later: the submit itself can fail with an Error.
        doThrow(new NoClassDefFoundError("pool gone")).when(virtualThreadExecutor).submit(any(Runnable.class));

        assertThatThrownBy(() -> workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL))
                .isInstanceOf(NoClassDefFoundError.class);

        assertThat(captureLastSavedRun().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(registry.isRunActive("run-1")).isFalse();
    }

    @Test
    void cancelExecution_beforeThreadStarts_isHonoured() throws Exception {
        DynamicSemaphore singleSlot = new DynamicSemaphore(1);
        singleSlot.acquire(); // the only slot is taken — the run parks in acquire()
        WorkflowExecutionRegistry liveRegistry = new WorkflowExecutionRegistry();
        AtomicReference<WorkflowRun> stored = statefulRunRepository();

        realThreads = Executors.newVirtualThreadPerTaskExecutor();
        WorkflowExecutor executor =
                buildExecutor(List.of(simpleBlockExecutor), singleSlot, liveRegistry, realThreads);

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);
        executor.cancelExecution("run-1");
        singleSlot.release();

        WorkflowRun finished = awaitTerminalStatus(stored);
        assertThat(finished.getStatus()).isEqualTo(RunStatus.CANCELLED);
        // The ghost branch would have stamped a "no active execution thread" error onto the run.
        assertThat(finished.getError()).isNullOrEmpty();

        verify(simpleBlockExecutor, never()).execute(any(), any());
        verify(containerService, never()).getDockerContainerId(any());
        verify(stepResultRepository, never()).save(any());
        awaitCondition(() -> !liveRegistry.isRunActive("run-1") && singleSlot.availablePermits() == 1,
                "run unregistered and permit released");
    }

    @Test
    void executeAdHocCommand_runStillQueued_throws() throws Exception {
        DynamicSemaphore singleSlot = new DynamicSemaphore(1);
        singleSlot.acquire(); // the run never gets past acquire() during the assertion
        WorkflowExecutionRegistry liveRegistry = new WorkflowExecutionRegistry();
        AtomicReference<WorkflowRun> stored = statefulRunRepository();

        realThreads = Executors.newVirtualThreadPerTaskExecutor();
        WorkflowExecutor executor =
                buildExecutor(List.of(simpleBlockExecutor), singleSlot, liveRegistry, realThreads);
        executor.execute(testWorkflow, null, TriggerSource.MANUAL);

        // The provisional context makes the run cancellable, but there is no container and no
        // project cwd yet — an ad-hoc command must still be refused, not run on the host.
        assertThatThrownBy(() -> executor.executeAdHocCommand("run-1", "echo hello"))
                .isInstanceOf(WorkflowExecutionException.class);

        executor.cancelExecution("run-1");
        singleSlot.release();
        awaitTerminalStatus(stored);

        verify(commandExecutorService, never()).executeCommand(any(), anyString(), anyString());
    }

    @Test
    void cancelExecution_duringContainerStart_isHonoured() throws Exception {
        CountDownLatch containerStartEntered = new CountDownLatch(1);
        CountDownLatch containerStartMayFinish = new CountDownLatch(1);
        when(containerService.getDockerContainerId(any())).thenAnswer(inv -> {
            containerStartEntered.countDown();
            awaitLatch(containerStartMayFinish, "release of the container start");
            return "docker-container-1";
        });
        WorkflowExecutionRegistry liveRegistry = new WorkflowExecutionRegistry();
        AtomicReference<WorkflowRun> stored = statefulRunRepository();

        realThreads = Executors.newVirtualThreadPerTaskExecutor();
        WorkflowExecutor executor = buildExecutor(
                List.of(simpleBlockExecutor), new DynamicSemaphore(1), liveRegistry, realThreads);

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);
        awaitLatch(containerStartEntered, "the run to reach the container start");
        executor.cancelExecution("run-1");
        containerStartMayFinish.countDown();

        assertThat(awaitTerminalStatus(stored).getStatus()).isEqualTo(RunStatus.CANCELLED);

        verify(containerService, times(1)).getDockerContainerId(any());
        verify(simpleBlockExecutor, never()).execute(any(), any());
        assertThat(liveRegistry.isRunActive("run-1")).isFalse();
    }

    /**
     * The permit is taken before the container start, so a run parked there holds one. As long as
     * the run was not registered until *after* the container start, the watchdog saw 0 active runs
     * with 0 available permits and "reconciled" the live permit away — inflating the semaphore.
     */
    @Test
    void watchdog_doesNotReleasePermit_whileRunIsStartingContainer() throws Exception {
        CountDownLatch containerStartEntered = new CountDownLatch(1);
        CountDownLatch containerStartMayFinish = new CountDownLatch(1);
        when(containerService.getDockerContainerId(any())).thenAnswer(inv -> {
            containerStartEntered.countDown();
            awaitLatch(containerStartMayFinish, "release of the container start");
            return "docker-container-1";
        });
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
        DynamicSemaphore singleSlot = new DynamicSemaphore(1);
        WorkflowExecutionRegistry liveRegistry = new WorkflowExecutionRegistry();
        AtomicReference<WorkflowRun> stored = statefulRunRepository();
        GhostRunWatchdogService watchdog = new GhostRunWatchdogService(
                runRepository, liveRegistry, singleSlot, broadcaster, activityLogService,
                mock(com.automation.service.scheduler.SuspendState.class));

        realThreads = Executors.newVirtualThreadPerTaskExecutor();
        WorkflowExecutor executor =
                buildExecutor(List.of(simpleBlockExecutor), singleSlot, liveRegistry, realThreads);
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());

        executor.execute(testWorkflow, null, TriggerSource.MANUAL);
        awaitLatch(containerStartEntered, "the run to reach the container start");
        assertThat(singleSlot.availablePermits()).isZero();

        watchdog.detectGhostRuns();

        assertThat(singleSlot.availablePermits())
                .as("the parked run holds the permit — the watchdog must not hand out a second one")
                .isZero();
        containerStartMayFinish.countDown();
        awaitTerminalStatus(stored);
        awaitCondition(() -> singleSlot.availablePermits() == 1, "permit released after the run finished");
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
            workflowExecutor.execute(testWorkflow, null, TriggerSource.MANUAL);
        } catch (RuntimeException expected) {
            // the synchronous test executor propagates the failure — irrelevant here
        }

        assertThat(registry.isRunActive("run-1")).isFalse();
    }

    // --- Taint propagation end-to-end (Finding 8) -------------------------------------------------

    @Test
    void execute_webhookRun_blockOutputsAreEscapedInLaterShellTemplates() {
        AtomicInteger calls = new AtomicInteger();
        List<String> resolved = new ArrayList<>();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    ExecutionContext ctx = inv.getArgument(1);
                    resolved.add(ctx.resolveShellTemplate(
                            "echo {{ previous_output }} {{ step_0_output }} {{ branch }}", false));
                    return StepResult.builder().stepIndex(calls.getAndIncrement())
                            .status(StepStatus.COMPLETED).output("x; id").build();
                });

        workflowExecutor.execute(testWorkflow, new HashMap<>(Map.of(
                "branch", "main; id",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch"))), TriggerSource.MANUAL);

        // block 1 sees only the untrusted variable escaped, block 2 also the tainted output
        assertThat(resolved.get(0)).isEqualTo("echo  {{ step_0_output }} 'main; id'");
        assertThat(resolved.get(1)).isEqualTo("echo 'x; id' 'x; id' 'main; id'");
    }

    @Test
    void execute_webhookRun_contextDeltaCarriesTheTaintForResume() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("x; id").build());

        workflowExecutor.execute(testWorkflow, new HashMap<>(Map.of(
                "branch", "main; id",
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch"))), TriggerSource.MANUAL);

        ArgumentCaptor<StepResult> steps = ArgumentCaptor.forClass(StepResult.class);
        verify(stepResultRepository, atLeastOnce()).save(steps.capture());
        Map<String, Object> delta = steps.getAllValues().stream()
                .map(StepResult::getContextDelta).filter(java.util.Objects::nonNull)
                .reduce((first, second) -> second).orElseThrow();
        assertThat(delta)
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME))
                .containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE,
                        List.of("branch", "step_0_output", "step_1_output"));
    }

    @Test
    void execute_ordinaryRun_persistsNoTaintKeys() {
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("a | b").build());

        workflowExecutor.execute(testWorkflow, new HashMap<>(Map.of("branch", "main")), TriggerSource.MANUAL);

        ArgumentCaptor<StepResult> steps = ArgumentCaptor.forClass(StepResult.class);
        verify(stepResultRepository, atLeastOnce()).save(steps.capture());
        assertThat(steps.getAllValues()).allSatisfy(step -> assertThat(step.getContextDelta())
                .satisfiesAnyOf(delta -> assertThat(delta).isNull(),
                        delta -> assertThat(delta).doesNotContainKeys(
                                ExecutionContext.UNTRUSTED_KEYS_VARIABLE,
                                ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE)));
    }

    @Test
    void resumeFromStep_importedTaint_keepsTheReconstructedOutputEscaped() {
        Map<String, Object> inputContext = Map.of(
                ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE, List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME),
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("step_0_output"));
        WorkflowRun interrupted = WorkflowRun.builder().id("run-1").workflow(testWorkflow).workflowName("Test Workflow")
                .status(RunStatus.INTERRUPTED).startedAt(Instant.now()).inputContext(inputContext).build();
        WorkflowRun running = WorkflowRun.builder().id("run-1").workflow(testWorkflow).workflowName("Test Workflow")
                .status(RunStatus.RUNNING).startedAt(Instant.now()).build();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(interrupted)).thenReturn(Optional.of(running));
        StepResult done = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("x; id").build();
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of(done));
        List<String> resolved = new ArrayList<>();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenAnswer(inv -> {
                    resolved.add(((ExecutionContext) inv.getArgument(1))
                            .resolveShellTemplate("echo {{ previous_output }} {{ step_0_output }}", false));
                    return StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("done").build();
                });

        workflowExecutor.resumeFromStep("run-1");

        assertThat(resolved).containsExactly("echo 'x; id' 'x; id'");
    }
}
