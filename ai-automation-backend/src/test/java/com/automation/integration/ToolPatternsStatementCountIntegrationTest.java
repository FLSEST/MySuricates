package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.metrics.SqlStatementCounter;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.learning.AgenticLearningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AgenticLearningService#getToolPatterns} reads the step metadata of a bounded run window in a
 * fixed number of statements (TODO 47c1cbe4). Before, it loaded every run of the workflow and then
 * its step entities, output text included, in batches of 30 – the statements and the bytes on the
 * single SQLite connection grew with the history. Measured like the other statement-count tests:
 * committed rows, no surrounding transaction.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ToolPatternsStatementCountIntegrationTest {

    /** Run ids of the window, then the metadata of their steps. */
    private static final int STATEMENTS = 2;

    /** Stands for the output text a step carries – which the analysis does not need. */
    private static final String OUTPUT = "x".repeat(20_000);

    @Autowired private AgenticLearningService agenticLearningService;
    @Autowired private TransactionTemplate transactions;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;

    private Project project;
    private Workflow workflow;
    private final List<String> runIds = new ArrayList<>();

    @BeforeEach
    void createWorkflow() {
        long nano = System.nanoTime();
        transactions.executeWithoutResult(status -> {
            project = projectRepository.save(Project.builder()
                    .name("tool-patterns-" + nano).type(ProjectType.CODE)
                    .path("/tmp/tool-patterns-" + nano).status(ProjectStatus.STOPPED).build());
            workflow = workflowRepository.save(Workflow.builder()
                    .name("tool-patterns-" + nano).project(project)
                    .activatedSkills(new ArrayList<>()).defaultContext(Map.of()).metadata(Map.of())
                    .build());
        });
    }

    @AfterEach
    void removeRows() {
        runIds.forEach(runRepository::deleteById);
        workflowRepository.deleteById(workflow.getId());
        projectRepository.deleteById(project.getId());
    }

    @Test
    void statementCountDoesNotGrowWithTheNumberOfRuns() {
        addRuns(1, Instant.now().minus(1, ChronoUnit.HOURS), List.of("Read", "Edit"));
        Measurement one = measure();

        // More than the @BatchSize(30) of WorkflowRun.steps: the entity path needed one statement more.
        addRuns(34, Instant.now().minus(2, ChronoUnit.HOURS), List.of("Read", "Edit"));
        Measurement many = measure();

        assertThat(one.result().get("totalAgenticRuns")).isEqualTo(1);
        assertThat(many.result().get("totalAgenticRuns")).isEqualTo(35);
        assertThat(many.result().get("topTools")).isEqualTo(List.of(
                Map.of("name", "Read", "count", 35), Map.of("name", "Edit", "count", 35)));
        assertThat(many.result().get("patterns"))
                .isEqualTo(List.of(Map.of("pattern", "Read -> Edit", "occurrences", 35)));
        assertThat(many.statements()).as("35 runs must cost what 1 run costs").isEqualTo(one.statements());
        assertThat(one.statements()).isEqualTo(STATEMENTS);
    }

    @Test
    void runsOutsideTheWindowAreNotRead() {
        // Behind the 90-day window.
        addRuns(1, Instant.now().minus(100, ChronoUnit.DAYS), List.of("Old", "Older"));
        addRuns(1, Instant.now().minus(1, ChronoUnit.DAYS), List.of("Read", "Edit"));

        Map<String, Object> result = agenticLearningService.getToolPatterns(workflow.getId());

        assertThat(result.get("totalAgenticRuns")).isEqualTo(1);
        assertThat(result.get("topTools")).isEqualTo(List.of(
                Map.of("name", "Read", "count", 1), Map.of("name", "Edit", "count", 1)));
    }

    @Test
    void workflowWithoutRuns_costsOneStatement() {
        Measurement none = measure();

        assertThat(none.result().get("totalAgenticRuns")).isEqualTo(0);
        assertThat(none.statements()).isEqualTo(1);
    }

    /** {@code count} runs, each with one agentic step that used {@code tools} and carries an output text. */
    private void addRuns(int count, Instant startedAt, List<String> tools) {
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                WorkflowRun run = WorkflowRun.builder().workflow(workflow).workflowName(workflow.getName())
                        .status(RunStatus.COMPLETED).startedAt(startedAt.minusSeconds(i)).build();
                run.addStep(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                        .startedAt(startedAt.minusSeconds(i)).output(OUTPUT)
                        .metadata(new HashMap<>(Map.of("blockType", "agentic", "toolNames", tools,
                                "costUsd", 0.01)))
                        .build());
                runIds.add(runRepository.save(run).getId());
            }
        });
    }

    private record Measurement(int statements, Map<String, Object> result) {}

    private Measurement measure() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("measured inside an open transaction — production has none around the call").isFalse();
        SqlStatementCounter.start();
        try {
            Map<String, Object> result = agenticLearningService.getToolPatterns(workflow.getId());
            return new Measurement(SqlStatementCounter.count(), result);
        } finally {
            SqlStatementCounter.stop();
        }
    }
}
