package com.automation.integration;

import com.automation.config.SchedulerConfig;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.project.ContainerService;
import com.automation.service.scheduler.CatchUpQueue;
import com.automation.service.scheduler.ScheduleSkipPolicy;
import com.automation.service.scheduler.ScheduledWorkflowExecutor;
import com.automation.service.scheduler.SchedulerMaintenanceMode;
import com.automation.service.scheduler.SchedulerTick;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.SuspendState;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Cold start with a run the previous lifecycle left RUNNING and a schedule of the same workflow that
 * missed its occurrence while the backend was down (TODO aa51d221).
 *
 * <p>Spring fires the first {@code @Scheduled} tick at ContextRefreshedEvent; the zombie cleanup
 * ({@link RunControlService#cleanupZombieRuns}) runs on ApplicationReadyEvent, after the
 * ApplicationRunners. Before the fix that first tick saw the leftover run as "target busy" and dropped
 * the catch-up, or — without a leftover — started a run that the cleanup then marked INTERRUPTED while
 * it was still running. The test replays that order with a fresh {@link SchedulerTick} (the context's
 * own one is past its start) against the real executor and database.
 */
@SpringBootTest
@ActiveProfiles("integration")
class SchedulerColdStartIntegrationTest {

    private static final EnumSet<RunStatus> TERMINAL = EnumSet.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS,
            RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED);

    @Autowired private ScheduledWorkflowRepository scheduledRepository;
    @Autowired private ScheduledWorkflowExecutor scheduledExecutor;
    @Autowired private ActivityLogService activityLogService;
    @Autowired private SchedulerConfig schedulerConfig;
    @Autowired private SchedulerMaintenanceMode maintenanceMode;
    @Autowired private SuspendState suspendState;
    @Autowired private ScheduleSkipPolicy skipPolicy;
    @Autowired private Clock clock;
    @Autowired private RunControlService runControlService;
    @Autowired private SchedulerTick contextTick;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private EntityManager entityManager;

    // Same bean overrides as WorkflowExecutorDeterministicIntegrationTest, so both share one context.
    @MockitoBean private ContainerService containerService;

    @TempDir
    Path tempDir;

    private final List<Runnable> cleanup = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            cleanup.get(i).run();
        }
    }

    /**
     * The tests below open their fresh tick by hand; this one checks the wiring: the context's own
     * tick received Spring Boot's ACCEPTING_TRAFFIC and is open once the context has started.
     */
    @Test
    void theContextsTick_isOpenedByTheReadinessEventOfTheStartup() {
        assertThat(ReflectionTestUtils.getField(contextTick, "startupCleanupDone")).isEqualTo(true);
    }

    /** The leftover RUNNING row made the target look busy: the first tick used to drop the catch-up. */
    @Test
    void theMissedOccurrenceWaitsForTheZombieCleanup_andItsRunIsNotInterrupted() {
        Workflow workflow = createWorkflow("echo caught-up");
        WorkflowRun zombie = workflowRunRepository.saveAndFlush(WorkflowRun.builder()
                .workflow(workflow).workflowName(workflow.getName())
                .status(RunStatus.RUNNING).startedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .build());
        cleanup.add(() -> workflowRunRepository.deleteById(zombie.getId()));
        Instant missedAt = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        ScheduledWorkflow schedule = scheduledRepository.saveAndFlush(ScheduledWorkflow.builder()
                .workflow(workflow).cronExpression("0 0 * * * *").enabled(true)
                .nextRunAt(missedAt).catchUpOnResume(true).build());
        cleanup.add(() -> scheduledRepository.deleteById(schedule.getId()));

        SchedulerTick tick = new SchedulerTick(scheduledRepository, scheduledExecutor, activityLogService,
                schedulerConfig, maintenanceMode, suspendState, skipPolicy, new CatchUpQueue(activityLogService), clock);

        // ContextRefreshedEvent: Spring's first tick, before the cleanup — it must leave everything as it is.
        tick.checkAndExecuteScheduled();
        entityManager.clear();
        assertThat(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(schedule.getId())).isEmpty();
        assertThat(scheduledRepository.findById(schedule.getId()).orElseThrow().getNextRunAt())
                .as("the occurrence is neither started nor skipped").isEqualTo(missedAt);

        // ApplicationReadyEvent: the cleanup — and right after it Spring Boot reports ACCEPTING_TRAFFIC.
        runControlService.cleanupZombieRuns();
        tick.openAfterStartupCleanup(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        tick.checkAndExecuteScheduled();

        entityManager.clear();
        List<WorkflowRun> caughtUp = workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(schedule.getId());
        caughtUp.forEach(run -> cleanup.add(() -> workflowRunRepository.deleteById(run.getId())));
        assertThat(caughtUp).as("the missed occurrence is caught up once").hasSize(1);
        assertThat(waitForTerminal(caughtUp.get(0)).getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(workflowRunRepository.findById(zombie.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(scheduledRepository.findById(schedule.getId()).orElseThrow().getNextRunAt()).isAfter(missedAt);
    }

    /**
     * Without a leftover run the first tick used to start the catch-up right away — RUNNING when the
     * cleanup came, so it was marked INTERRUPTED while its thread went on (the question of aa51d221).
     */
    @Test
    void noRunIsStartedBeforeTheCleanup_soTheCleanupCannotInterruptOne() {
        Workflow workflow = createWorkflow("sleep 2; echo caught-up");
        Instant missedAt = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        ScheduledWorkflow schedule = scheduledRepository.saveAndFlush(ScheduledWorkflow.builder()
                .workflow(workflow).cronExpression("0 0 * * * *").enabled(true)
                .nextRunAt(missedAt).catchUpOnResume(true).build());
        cleanup.add(() -> scheduledRepository.deleteById(schedule.getId()));
        cleanup.add(() -> workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(schedule.getId())
                .forEach(run -> workflowRunRepository.deleteById(run.getId())));

        SchedulerTick tick = new SchedulerTick(scheduledRepository, scheduledExecutor, activityLogService,
                schedulerConfig, maintenanceMode, suspendState, skipPolicy, new CatchUpQueue(activityLogService), clock);

        tick.checkAndExecuteScheduled();          // ContextRefreshedEvent
        runControlService.cleanupZombieRuns();    // ApplicationReadyEvent
        entityManager.clear();
        assertThat(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(schedule.getId()))
                .extracting(WorkflowRun::getStatus).doesNotContain(RunStatus.INTERRUPTED);

        tick.openAfterStartupCleanup(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        tick.checkAndExecuteScheduled();

        entityManager.clear();
        List<WorkflowRun> caughtUp = workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(schedule.getId());
        assertThat(caughtUp).hasSize(1);
        assertThat(waitForTerminal(caughtUp.get(0)).getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    private Workflow createWorkflow(String command) {
        Path projectPath = tempDir.resolve("cold-start-" + System.nanoTime());
        try {
            Files.createDirectories(projectPath);
        } catch (Exception e) {
            throw new AssertionError("Failed to create temp project directory", e);
        }
        Project project = projectRepository.saveAndFlush(Project.builder()
                .name("cold-start-" + System.nanoTime()).type(ProjectType.CODE)
                .path(projectPath.toString()).status(ProjectStatus.STOPPED).build());
        cleanup.add(() -> projectRepository.deleteById(project.getId()));
        Workflow saved = workflowRepository.saveAndFlush(Workflow.builder()
                .name("cold start catch-up " + System.nanoTime()).project(project)
                .blocks(new ArrayList<>(List.of(BlockDefinition.builder().type("shell")
                        .config(Map.of("command", command, "timeout_seconds", 10)).build())))
                .activatedSkills(new ArrayList<>()).defaultContext(Map.of()).metadata(Map.of())
                .build());
        cleanup.add(() -> workflowRepository.deleteById(saved.getId()));
        entityManager.clear();
        return workflowRepository.findByIdWithSkills(saved.getId()).orElseThrow();
    }

    private WorkflowRun waitForTerminal(WorkflowRun started) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        RunStatus last = started.getStatus();
        while (Instant.now().isBefore(deadline)) {
            entityManager.clear();
            WorkflowRun current = workflowRunRepository.findById(started.getId()).orElseThrow();
            last = current.getStatus();
            if (TERMINAL.contains(last)) {
                return current;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for run " + started.getId(), e);
            }
        }
        throw new AssertionError("Run " + started.getId() + " did not finish in time; last status " + last);
    }
}
