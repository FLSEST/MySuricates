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
import com.automation.service.workflow.RunAutoResumer;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.persistence.EntityManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the state a restart leaves behind for the auto-resume integration tests (TODO 90d10ba1):
 * a run whose first block completed and whose second block was still RUNNING, the run itself still
 * RUNNING (or PAUSED) — exactly what {@code RunControlService.cleanupZombieRuns} finds on startup.
 */
record InterruptedRunFixture(WorkflowExecutor executor, ProjectRepository projects, WorkflowRepository workflows,
                             WorkflowRunRepository runs, StepResultRepository steps, EntityManager entityManager,
                             RunAutoResumer resumer) {

    static final EnumSet<RunStatus> FINISHED = EnumSet.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS,
            RunStatus.FAILED, RunStatus.CANCELLED);

    Project project(Path dir) {
        return projects.saveAndFlush(Project.builder().name("auto-resume-" + System.nanoTime()).type(ProjectType.CODE)
                .path(dir.toString()).status(ProjectStatus.STOPPED).build());
    }

    /** Block 1 writes a.log; block 2 fails on its first attempt only (flag file), so a resume completes. */
    Workflow workflow(Project project, boolean optIn) {
        Map<String, Object> metadata = new HashMap<>();
        if (optIn) {
            metadata.put("autoResume", Map.of("enabled", true));
        }
        Workflow saved = workflows.saveAndFlush(Workflow.builder()
                .name("auto-resume-" + System.nanoTime()).project(project)
                .blocks(new ArrayList<>(List.of(shell("echo a >> a.log"),
                        shell("if [ -f b.flag ]; then echo b >> b.log; else touch b.flag; exit 1; fi"))))
                .activatedSkills(new ArrayList<>()).defaultContext(Map.of()).metadata(metadata).build());
        entityManager.clear();
        return workflows.findByIdWithSkills(saved.getId()).orElseThrow();
    }

    /** Runs the workflow (block 2 fails once) and rewinds the run into the in-flight state of a restart. */
    WorkflowRun inFlight(Workflow workflow, RunStatus status, Consumer<WorkflowRun> tweak) {
        WorkflowRun finished = awaitFinished(executor.execute(workflow, Map.of(), TriggerSource.MANUAL).getId());
        List<StepResult> persisted = steps.findByWorkflowRunIdOrderByStepIndexAsc(finished.getId());
        StepResult last = persisted.get(persisted.size() - 1);
        last.setStatus(StepStatus.RUNNING);
        last.setOutput(null);
        last.setCompletedAt(null);
        steps.saveAndFlush(last);

        WorkflowRun run = runs.findById(finished.getId()).orElseThrow();
        run.setStatus(status);
        run.setCompletedAt(null);
        run.setError(null);
        tweak.accept(run);
        runs.saveAndFlush(run);
        entityManager.clear();
        return run;
    }

    WorkflowRun reload(WorkflowRun run) {
        entityManager.clear();
        return runs.findById(run.getId()).orElseThrow();
    }

    void awaitResumerIdle() {
        await("the auto-resumer to finish its queue", resumer::isIdle);
    }

    WorkflowRun awaitFinished(String runId) {
        await("run " + runId + " to finish", () -> FINISHED.contains(runs.findById(runId).orElseThrow().getStatus()));
        return runs.findById(runId).orElseThrow();
    }

    static void await(String what, java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    int lines(Project project, String file) throws Exception {
        Path path = Path.of(project.getPath(), file);
        return Files.exists(path) ? Files.readAllLines(path).size() : 0;
    }

    private static BlockDefinition shell(String command) {
        return BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", command, "timeout_seconds", 5))).build();
    }
}
