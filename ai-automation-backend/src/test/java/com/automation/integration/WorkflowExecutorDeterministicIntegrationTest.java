package com.automation.integration;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.execution.ExecutionCommandService;
import com.automation.service.project.ContainerService;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("integration")
class WorkflowExecutorDeterministicIntegrationTest {

    private static final EnumSet<RunStatus> TERMINAL_STATUSES = EnumSet.of(
            RunStatus.COMPLETED,
            RunStatus.COMPLETED_WITH_ERRORS,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.INTERRUPTED
    );

    @Autowired private WorkflowExecutor workflowExecutor;
    @Autowired private ExecutionCommandService executionCommandService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private ContainerService containerService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
    }

    @AfterEach
    void cleanup() {
        // Use deleteAll() instead of deleteAllInBatch() because Workflow has
        // @SQLRestriction("deleted_at IS NULL"); Hibernate generates an aliased
        // DELETE that SQLite rejects.
        stepResultRepository.deleteAll();
        workflowRunRepository.deleteAll();
        workflowRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void shellBlocks_resolvePreviousOutputAndPersistSteps() {
        Project project = createProject("shell-previous");
        Workflow workflow = createWorkflow(project, "shell previous output",
                shell("echo alpha"),
                shell("printf 'prev=%s\\n' '{{ previous_output }}'"));

        WorkflowRun run = executeAndWait(workflow, Map.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        List<StepResult> steps = stepsFor(run);
        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(StepResult::getStatus)
                .containsExactly(StepStatus.COMPLETED, StepStatus.COMPLETED);
        assertThat(steps.get(0).getOutput()).isEqualTo("alpha");
        assertThat(steps.get(1).getOutput()).isEqualTo("prev=alpha");
    }

    @Test
    void conditionalBlock_executesThenAndElseBranchesInShellMode() {
        Project project = createProject("conditional");
        Workflow workflow = createWorkflow(project, "conditional shell",
                block("conditional", Map.of(
                        "condition", "test \"{{ branch }}\" = then",
                        "then_command", "echo conditional-then",
                        "else_command", "echo conditional-else",
                        "execution_mode", "shell",
                        "timeout_seconds", 5
                )));

        WorkflowRun thenRun = executeAndWait(workflow, Map.of("branch", "then"));
        WorkflowRun elseRun = executeAndWait(workflow, Map.of("branch", "else"));

        assertThat(thenRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(elseRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(singleStepOutput(thenRun)).isEqualTo("conditional-then");
        assertThat(singleStepOutput(elseRun)).isEqualTo("conditional-else");
    }

    @Test
    void foreachBlock_iteratesListInShellMode() {
        Project project = createProject("foreach");
        Workflow workflow = createWorkflow(project, "foreach shell",
                block("foreach", Map.of(
                        "variable_name", "item",
                        "items", List.of("one", "two"),
                        "command", "echo item-{{ item }}",
                        "execution_mode", "shell",
                        "timeout_seconds", 5
                )));

        WorkflowRun run = executeAndWait(workflow, Map.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(singleStepOutput(run))
                .contains("[one]: item-one")
                .contains("[two]: item-two");
    }

    @Test
    void whileBlock_runsUntilShellConditionTurnsFalse() throws Exception {
        Project project = createProject("while");
        Files.writeString(Path.of(project.getPath(), "while.flag"), "run");
        Workflow workflow = createWorkflow(project, "while shell",
                block("while", Map.of(
                        "condition_command", "test -f while.flag",
                        "command", "echo loop-once; rm while.flag",
                        "max_iterations", 3,
                        "execution_mode", "shell",
                        "timeout_seconds", 5
                )));

        WorkflowRun run = executeAndWait(workflow, Map.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(singleStepOutput(run)).contains("[iteration 1]: loop-once");
        assertThat(singleStepOutput(run)).doesNotContain("[iteration 2]");
    }

    @Test
    void retryBlock_retriesFailedShellCommandAndCompletes() {
        Project project = createProject("retry");
        Workflow workflow = createWorkflow(project, "retry shell",
                block("retry", Map.of(
                        "command", "if [ -f retry.flag ]; then echo retry-ok; else touch retry.flag; echo retry-fail; exit 1; fi",
                        "max_retries", 1,
                        "delay", 0,
                        "execution_mode", "shell",
                        "timeout_seconds", 5
                )));

        WorkflowRun run = executeAndWait(workflow, Map.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(singleStepOutput(run)).isEqualTo("retry-ok");
    }

    @Test
    void switchBlock_executesMatchingCaseAndDefaultBranch() {
        Project project = createProject("switch");
        Workflow workflow = createWorkflow(project, "switch shell",
                block("switch", Map.of(
                        "expression", "{{ mode }}",
                        "cases", List.of(Map.of(
                                "value", "green",
                                "blocks", List.of(Map.of(
                                        "type", "shell",
                                        "config", Map.of("command", "echo switch-green")
                                ))
                        )),
                        "default", List.of(Map.of(
                                "type", "shell",
                                "config", Map.of("command", "echo switch-default")
                        ))
                )));

        WorkflowRun matchedRun = executeAndWait(workflow, Map.of("mode", "green"));
        WorkflowRun defaultRun = executeAndWait(workflow, Map.of("mode", "blue"));

        assertThat(matchedRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(defaultRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(singleStepOutput(matchedRun)).isEqualTo("switch-green");
        assertThat(singleStepOutput(defaultRun)).isEqualTo("switch-default");
    }

    @Test
    void subWorkflowBlock_executesChildWorkflowWithParentContext() {
        Project project = createProject("sub-workflow");
        Workflow child = createWorkflow(project, "child workflow",
                shell("echo child-{{ child_value }}-{{ previous_output }}"));
        Workflow parent = createWorkflow(project, "parent workflow",
                shell("echo parent-start"),
                block("sub-workflow", Map.of("workflow_id", child.getId())));

        WorkflowRun run = executeAndWait(parent, Map.of("child_value", "ctx"));

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        List<StepResult> steps = stepsFor(run);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).getOutput()).isEqualTo("child-ctx-parent-start");
    }

    /**
     * TODO 2a0695ff: The parent's platform data is not stored with the child run — the child gets its own injected
     * (its shell block sees them) — while the parent's own variables still reach it.
     */
    @Test
    void subWorkflowBlock_childRunStoresNoPlatformDataOfItsParent() {
        Project project = createProject("sub-workflow-platform");
        Workflow child = createWorkflow(project, "child platform",
                shell("echo child-{{ child_value }} {{ open_todos }}"));
        Workflow parent = createWorkflow(project, "parent platform",
                block("sub-workflow", Map.of("workflow_id", child.getId())));

        WorkflowRun run = executeAndWait(parent, Map.of("child_value", "ctx"));

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        StepResult subStep = stepsFor(run).getFirst();
        assertThat(subStep.getOutput()).isEqualTo("child-ctx (keine offenen TODOs)");
        WorkflowRun childRun = workflowRunRepository.findById((String) subStep.getMetadata().get("subRunId")).orElseThrow();
        assertThat(childRun.getInputContext()).containsEntry("child_value", "ctx")
                .doesNotContainKeys("previous_report", "open_todos", "request_metrics");
    }

    @Test
    void approvalBlock_autoAnswerStoresVariableForNextStep() {
        Project project = createProject("approval");
        Workflow workflow = createWorkflow(project, "approval auto",
                block("approval", Map.of(
                        "mode", "auto",
                        "question", "Continue?",
                        "auto_answer", "Yes",
                        "variable_name", "approval_answer"
                )),
                shell("echo answer-{{ approval_answer }}"));

        WorkflowRun run = executeAndWait(workflow, Map.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        List<StepResult> steps = stepsFor(run);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getOutput()).isEqualTo("Approved: Yes");
        assertThat(steps.get(1).getOutput()).isEqualTo("answer-Yes");
    }

    @Test
    void failingShellBlock_marksRunFailedAndPersistsStepError() {
        Project project = createProject("shell-failure");
        Workflow workflow = createWorkflow(project, "failing shell",
                shell("echo failing; exit 7"));

        WorkflowRun run = executeAndWait(workflow, Map.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        List<StepResult> steps = stepsFor(run);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(steps.get(0).getOutput()).contains("failing");
        assertThat(run.getError()).containsEntry("blockType", "shell");
    }

    @Test
    void resumeAfterWrapperBlock_runsTheBlocksThatNeverRan() {
        Project project = createProject("resume-foreach");
        Workflow workflow = createWorkflow(project, "resume after foreach",
                block("foreach", Map.of(
                        "variable_name", "item",
                        "items", List.of("one", "two"),
                        "command", "echo item-{{ item }}",
                        "execution_mode", "shell",
                        "timeout_seconds", 5
                )),
                shell("echo a >> a.log"),
                // Fails once, succeeds on the resumed attempt — so the run stops here and c never runs
                shell("if [ -f b.flag ]; then echo b >> b.log; else touch b.flag; exit 1; fi"),
                shell("echo c >> c.log"));

        WorkflowRun run = executeAndWait(workflow, Map.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        List<StepResult> steps = stepsFor(run);
        assertThat(steps).hasSize(3);            // foreach, a, b — c was never reached
        // The foreach consumed extra step indices for its items: b's index is ahead of its block position
        assertThat(steps.get(2).getStepIndex()).isGreaterThan(2);

        markInterrupted(run, steps.get(2));

        WorkflowRun resumed = resumeAndWait(run);

        assertThat(resumed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(lineCount(project, "a.log")).as("completed block must not run again").isEqualTo(1);
        assertThat(lineCount(project, "b.log")).as("interrupted block must run again").isEqualTo(1);
        assertThat(lineCount(project, "c.log")).as("block after the interrupted one must run").isEqualTo(1);
        assertThat(stepsFor(resumed)).extracting(StepResult::getStepIndex).doesNotHaveDuplicates();
        // Only metadata.blockIndex maps a step to its block: the step indices ran ahead, and the
        // interrupted attempt of b is still persisted next to its resumed one.
        assertThat(stepsFor(resumed)).extracting(s -> s.getMetadata().get("blockIndex"))
                .containsExactly(0, 1, 2, 2, 3);
    }

    /**
     * TODO cd0ea0f9: a resume keeps the interrupted attempt of a block next to its new one, so after
     * the first resume there is no longer exactly one step per block. The second resume must still
     * find the block that never finished instead of counting the stale attempt as a passed block.
     */
    @Test
    void secondResume_runsTheBlockInterruptedInTheFirstResumedSegment() {
        Project project = createProject("resume-twice");
        Workflow workflow = createWorkflow(project, "resume twice",
                block("foreach", Map.of(
                        "variable_name", "item",
                        "items", List.of("one", "two"),
                        "command", "echo item-{{ item }}",
                        "execution_mode", "shell",
                        "timeout_seconds", 5
                )),
                shell("echo a >> a.log"),
                shell("if [ -f b.flag ]; then echo b >> b.log; else touch b.flag; exit 1; fi"),
                shell("if [ -f c.flag ]; then echo c >> c.log; else touch c.flag; exit 1; fi"),
                shell("echo d >> d.log"));

        WorkflowRun run = executeAndWait(workflow, Map.of());
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        List<StepResult> firstSegment = stepsFor(run);
        markInterrupted(run, firstSegment.get(firstSegment.size() - 1));   // b interrupted

        WorkflowRun afterFirstResume = resumeAndWait(run);
        assertThat(afterFirstResume.getStatus()).isEqualTo(RunStatus.FAILED);   // c failed once
        List<StepResult> secondSegment = stepsFor(afterFirstResume);
        markInterrupted(run, secondSegment.get(secondSegment.size() - 1)); // c interrupted

        WorkflowRun afterSecondResume = resumeAndWait(run);

        assertThat(afterSecondResume.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(lineCount(project, "a.log")).as("a ran in the first segment only").isEqualTo(1);
        assertThat(lineCount(project, "b.log")).as("b completed in the first resume only").isEqualTo(1);
        assertThat(lineCount(project, "c.log")).as("c never finished before — it must run now").isEqualTo(1);
        assertThat(lineCount(project, "d.log")).as("d runs exactly once, after c").isEqualTo(1);
        assertThat(stepsFor(afterSecondResume)).extracting(StepResult::getStepIndex).doesNotHaveDuplicates();
        assertThat(stepsFor(afterSecondResume)).extracting(s -> s.getMetadata().get("blockIndex"))
                .containsExactly(0, 1, 2, 2, 3, 3, 4);
    }

    /** Puts the run into the state an application restart leaves behind: run INTERRUPTED, last step still RUNNING. */
    private void markInterrupted(WorkflowRun run, StepResult lastStep) {
        StepResult step = stepResultRepository.findById(lastStep.getId()).orElseThrow();
        step.setStatus(StepStatus.RUNNING);
        step.setOutput(null);
        step.setCompletedAt(null);
        stepResultRepository.saveAndFlush(step);

        WorkflowRun stored = workflowRunRepository.findById(run.getId()).orElseThrow();
        stored.setStatus(RunStatus.INTERRUPTED);
        stored.setCompletedAt(null);
        stored.setError(null);
        workflowRunRepository.saveAndFlush(stored);
        entityManager.clear();
    }

    private int lineCount(Project project, String fileName) {
        Path file = Path.of(project.getPath(), fileName);
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            return Files.readAllLines(file).size();
        } catch (Exception e) {
            throw new AssertionError("Failed to read " + file, e);
        }
    }

    private Project createProject(String name) {
        Path projectPath = tempDir.resolve(name);
        try {
            Files.createDirectories(projectPath);
        } catch (Exception e) {
            throw new AssertionError("Failed to create temp project directory", e);
        }
        return projectRepository.saveAndFlush(Project.builder()
                .name(name + "-" + System.nanoTime())
                .type(ProjectType.CODE)
                .path(projectPath.toString())
                .status(ProjectStatus.STOPPED)
                .build());
    }

    private Workflow createWorkflow(Project project, String name, BlockDefinition... blocks) {
        Workflow saved = workflowRepository.saveAndFlush(Workflow.builder()
                .name(name + "-" + System.nanoTime())
                .project(project)
                .blocks(new ArrayList<>(List.of(blocks)))
                .activatedSkills(new ArrayList<>())
                .defaultContext(Map.of())
                .metadata(Map.of())
                .build());
        entityManager.clear();
        return workflowRepository.findByIdWithSkills(saved.getId()).orElseThrow();
    }

    private WorkflowRun executeAndWait(Workflow workflow, Map<String, Object> context) {
        return waitForTerminal(workflowExecutor.execute(workflow, context, TriggerSource.MANUAL));
    }

    /** Resumes through the transactional service the REST endpoint uses — the executor itself needs the session. */
    private WorkflowRun resumeAndWait(WorkflowRun run) {
        executionCommandService.resumeFromStep(run.getId());
        return waitForTerminal(workflowRunRepository.findById(run.getId()).orElseThrow());
    }

    private WorkflowRun waitForTerminal(WorkflowRun started) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        RunStatus lastStatus = started.getStatus();
        while (Instant.now().isBefore(deadline)) {
            entityManager.clear();
            WorkflowRun current = workflowRunRepository.findById(started.getId()).orElseThrow();
            lastStatus = current.getStatus();
            if (TERMINAL_STATUSES.contains(lastStatus)) {
                return current;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for workflow run " + started.getId(), e);
            }
        }
        throw new AssertionError("Workflow run " + started.getId() + " did not finish within timeout; last status was " + lastStatus);
    }

    private List<StepResult> stepsFor(WorkflowRun run) {
        entityManager.clear();
        return stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());
    }

    private String singleStepOutput(WorkflowRun run) {
        List<StepResult> steps = stepsFor(run);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo(StepStatus.COMPLETED);
        return steps.get(0).getOutput();
    }

    private static BlockDefinition shell(String command) {
        return block("shell", Map.of("command", command, "timeout_seconds", 5));
    }

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder()
                .type(type)
                .config(config)
                .build();
    }
}
