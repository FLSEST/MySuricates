package com.automation.repository;

import com.automation.dto.projection.RunOriginRow;
import com.automation.dto.projection.RunStateRow;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The lean run projections against the real SQLite file (TODO 982abd1a). */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowRunProjectionRepositoryTest {

    @Autowired private WorkflowRunProjectionRepository projections;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    private Workflow workflowA;
    private Workflow workflowB;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("projection-project-" + nano)
                .description("Test Project")
                .type(ProjectType.CODE)
                .path("/tmp/projection-project-" + nano)
                .status(ProjectStatus.STOPPED)
                .build());
        workflowA = workflowRepository.save(Workflow.builder().name("wf-a-" + nano).project(project).build());
        workflowB = workflowRepository.save(Workflow.builder().name("wf-b-" + nano).project(project).build());
    }

    private WorkflowRun run(Workflow workflow, RunStatus status, Instant startedAt) {
        return runRepository.save(WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflow.getName())
                .status(status)
                .startedAt(startedAt)
                .build());
    }

    @Test
    void findWorkflowRefsByRunIds_returnsIdWorkflowAndRecordedName_onlyForAskedRuns() {
        WorkflowRun waiting = run(workflowA, RunStatus.RUNNING, Instant.now());
        run(workflowB, RunStatus.RUNNING, Instant.now());
        entityManager.flush();
        entityManager.clear();

        List<Object[]> rows = projections.findWorkflowRefsByRunIds(List.of(waiting.getId(), "no-such-run"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(waiting.getId(), workflowA.getId(), workflowA.getName());
    }

    @Test
    void findRunStates_returnsStatusTimesAndWorkflow_onlyForAskedRuns() {
        Instant started = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        WorkflowRun done = run(workflowA, RunStatus.COMPLETED, started);
        done.setCompletedAt(started.plus(2, ChronoUnit.MINUTES));
        runRepository.save(done);
        run(workflowB, RunStatus.RUNNING, Instant.now());
        entityManager.flush();
        entityManager.clear();

        List<RunStateRow> rows = projections.findRunStates(List.of(done.getId(), "no-such-run"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(done.getId());
            assertThat(row.workflowId()).isEqualTo(workflowA.getId());
            assertThat(row.workflowName()).isEqualTo(workflowA.getName());
            assertThat(row.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(row.startedAt()).isEqualTo(started);
            assertThat(row.completedAt()).isEqualTo(started.plus(2, ChronoUnit.MINUTES));
        });
    }

    /**
     * From the context {@code findOriginColumns} reads only the parent trigger, as text (TODO a3b89355) — even
     * if the first row has none. For an expression without a declared type sqlite-jdbc takes the type
     * of the value in the current row; a NULL there reports NUMERIC. The rows come sorted by id.
     *
     * <p>Hibernate generates the ids randomly. Hence only after creation does the run with the
     * larger id get the parent trigger — without a search loop, which gave up at a very small parent id
     * (TODO 923e53d8).
     */
    @Test
    void findOriginColumns_readsTheParentSourceAsText_evenWhenTheFirstRowHasNone() {
        WorkflowRun first = originRun(Map.of());
        WorkflowRun second = originRun(Map.of());
        boolean firstSortsFirst = first.getId().compareTo(second.getId()) < 0;
        WorkflowRun withoutParent = firstSortsFirst ? first : second;
        WorkflowRun withParent = firstSortsFirst ? second : first;
        withParent.setInputContext(new HashMap<>(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE")));
        runRepository.save(withParent);
        assertThat(withoutParent.getId()).as("a run without parent sorts first").isLessThan(withParent.getId());
        entityManager.flush();
        entityManager.clear();

        List<Object[]> columns = projections.findOriginColumns(List.of(withParent.getId(), withoutParent.getId()));
        List<RunOriginRow> rows = projections.findOriginRows(List.of(withParent.getId(), withoutParent.getId()));

        assertThat(columns).extracting(row -> row[0]).containsExactly(withoutParent.getId(), withParent.getId());
        assertThat(rows).extracting(RunOriginRow::parentTriggerSource).containsExactly(null, "SCHEDULE");
        assertThat(rows).extracting(RunOriginRow::triggerSource).containsOnly(TriggerSource.CHAIN);
    }

    private WorkflowRun originRun(Map<String, Object> context) {
        return runRepository.save(WorkflowRun.builder().workflow(workflowA).workflowName(workflowA.getName())
                .status(RunStatus.COMPLETED).triggerSource(TriggerSource.CHAIN).inputContext(new HashMap<>(context)).build());
    }
}
