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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
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
    private ContainerRepository containerRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

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
                containerRepository,
                messagingTemplate,
                virtualThreadExecutor,
                new Semaphore(10),
                agentInstructionsService,
                activityLogService,
                workflowMemoryService,
                projectContextService,
                eventPublisher,
                templateVerificationService,
                systemIdentity
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
    void execute_injectsSkillContext() {
        Skill skill = Skill.builder()
                .id("skill-1").name("Reviewer").category(SkillCategory.CODE)
                .aiContext("Review context").build();
        testWorkflow.setActivatedSkills(new ArrayList<>(List.of(skill)));

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        verify(commandExecutorService).injectSkillContextOnHost(
                eq("/workspace/test"), eq(testWorkflow.getActivatedSkills()));
    }

    @Test
    void execute_broadcastsStepResults() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").durationMs(100L).build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                contains("/topic/executions/"), any(Map.class));
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
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/executions/run-1/logs"), any(Map.class));

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
}
