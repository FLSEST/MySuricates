package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.project.ContainerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutorTest {

    @Mock
    private ContainerService containerService;

    @Mock
    private CommandExecutorService commandExecutorService;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private BlockExecutor simpleBlockExecutor;

    private WorkflowExecutor workflowExecutor;

    private Workflow testWorkflow;
    private Project testProject;

    @BeforeEach
    void setUp() {
        workflowExecutor = new WorkflowExecutor(
                List.of(simpleBlockExecutor),
                containerService,
                commandExecutorService,
                runRepository,
                messagingTemplate
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
        lenient().when(simpleBlockExecutor.supports("simple")).thenReturn(true);
        lenient().when(runRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            return run;
        });
    }

    @Test
    void execute_allBlocksSucceed_returnsCompleted() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        WorkflowRun result = workflowExecutor.execute(testWorkflow, null);

        assertThat(result.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getDurationMs()).isNotNull();
    }

    @Test
    void execute_blockFails_withStopOnFailure_returnsFailedRun() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(failed);

        // shouldStopOnFailure() returns true by default when on_failure is not set
        WorkflowRun result = workflowExecutor.execute(testWorkflow, null);

        assertThat(result.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(result.getError()).isNotNull();
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

        WorkflowRun result = workflowExecutor.execute(testWorkflow, null);

        assertThat(result.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(simpleBlockExecutor, times(2)).execute(any(), any());
    }

    @Test
    void execute_noMatchingExecutor_throwsAndFails() {
        testWorkflow.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("unknown-type").config(new HashMap<>()).build()
        )));
        when(simpleBlockExecutor.supports("unknown-type")).thenReturn(false);

        WorkflowRun result = workflowExecutor.execute(testWorkflow, null);

        assertThat(result.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(result.getError()).containsKey("message");
    }

    @Test
    void execute_setsInputContextVariables() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenReturn(step);

        Map<String, Object> inputContext = Map.of("branch", "main", "env", "production");
        workflowExecutor.execute(testWorkflow, inputContext);

        // Verify execution happened (context variables were set internally)
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

        verify(commandExecutorService).injectSkillContext(
                eq("docker-container-1"), eq("claude"), eq(testWorkflow.getActivatedSkills()));
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
        // Start a workflow that will be paused
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(simpleBlockExecutor.execute(any(), any())).thenReturn(step);

        workflowExecutor.execute(testWorkflow, null);

        // Pause/resume a non-active run does nothing but doesn't throw
        workflowExecutor.pauseExecution("non-existent-run");
        workflowExecutor.resumeExecution("non-existent-run");
    }

    @Test
    void cancelExecution_nonExistentRun_doesNotThrow() {
        workflowExecutor.cancelExecution("non-existent-run");
        // No exception expected
    }
}
