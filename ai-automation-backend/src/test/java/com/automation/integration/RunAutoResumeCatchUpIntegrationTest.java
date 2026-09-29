package com.automation.integration;

import com.automation.config.AutoResumeConfig;
import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog;
import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ActivityLogRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.project.ContainerService;
import com.automation.service.scheduler.CatchUpQueue;
import com.automation.service.scheduler.ScheduleSkipPolicy;
import com.automation.service.scheduler.ScheduledWorkflowExecutor;
import com.automation.service.scheduler.SchedulerMaintenanceMode;
import com.automation.service.scheduler.SchedulerTick;
import com.automation.service.workflow.RunAutoResumer;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.SuspendState;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TODO 070ccc3d: a workflow with auto-resume and a schedule with {@code catchUpOnResume}. After a
 * restart the startup cleanup marks its leftover run INTERRUPTED — not RUNNING/PAUSED, so the SKIP
 * check does not see it — and the auto-resumer waits its delay before continuing it. The first tick
 * after the cleanup catches the missed occurrence up in exactly that window. Before the fix both
 * started: the catch-up run and, a few seconds later, the resumed run. The continuation wins; the
 * catch-up is skipped with a reason in the activity log. Same context configuration as
 * {@code RunAutoResumeIntegrationTest}, so the context is shared.
 */
@SpringBootTest
@ActiveProfiles("integration")
class RunAutoResumeCatchUpIntegrationTest {

    /** Long enough that the tick below certainly falls into the wait, short enough for the test. */
    private static final long RESUME_DELAY_SECONDS = 8;

    @Autowired private WorkflowExecutor workflowExecutor;
    @Autowired private RunControlService runControlService;
    @Autowired private RunAutoResumer resumer;
    @Autowired private AutoResumeConfig config;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private ScheduledWorkflowRepository scheduledRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ScheduledWorkflowExecutor scheduledExecutor;
    @Autowired private ActivityLogService activityLogService;
    @Autowired private SchedulerConfig schedulerConfig;
    @Autowired private SchedulerMaintenanceMode maintenanceMode;
    @Autowired private SuspendState suspendState;
    @Autowired private ScheduleSkipPolicy skipPolicy;
    @Autowired private Clock clock;

    @MockitoBean private ContainerService containerService;

    @TempDir Path tempDir;

    private InterruptedRunFixture fixture;
    private long previousDelay;
    private Project project;

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
        fixture = new InterruptedRunFixture(workflowExecutor, projectRepository, workflowRepository, runRepository,
                stepResultRepository, entityManager, resumer);
        previousDelay = config.getDelaySeconds();
        project = fixture.project(tempDir);
    }

    @AfterEach
    void tearDown() {
        fixture.awaitResumerIdle();
        config.setDelaySeconds(previousDelay);
        scheduledRepository.deleteAll();
        stepResultRepository.deleteAll();
        runRepository.deleteAll();
        workflowRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void catchUpOfTheSameWorkflow_isSkippedWhileTheInterruptedRunWaitsForItsResume() {
        Workflow workflow = fixture.workflow(project, true);
        WorkflowRun interrupted = fixture.inFlight(workflow, RunStatus.RUNNING, r -> { });
        Instant missedAt = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        ScheduledWorkflow schedule = scheduledRepository.saveAndFlush(ScheduledWorkflow.builder()
                .workflow(workflow).cronExpression("0 0 * * * *").enabled(true)
                .nextRunAt(missedAt).catchUpOnResume(true).build());
        config.setDelaySeconds(RESUME_DELAY_SECONDS);
        Instant testStart = Instant.now();

        // ApplicationReadyEvent: the cleanup marks the run INTERRUPTED, the resumer starts waiting.
        runControlService.cleanupZombieRuns();
        assertThat(fixture.reload(interrupted).getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        // ACCEPTING_TRAFFIC opens the scheduler; its first tick is the cold start with the catch-up.
        SchedulerTick tick = new SchedulerTick(scheduledRepository, scheduledExecutor, activityLogService,
                schedulerConfig, maintenanceMode, suspendState, skipPolicy, new CatchUpQueue(activityLogService), clock);
        tick.openAfterStartupCleanup(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        tick.checkAndExecuteScheduled();

        entityManager.clear();
        assertThat(runRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(schedule.getId(), Limit.unlimited()))
                .as("no catch-up run next to the pending resume").isEmpty();
        assertThat(scheduledRepository.findById(schedule.getId()).orElseThrow().getNextRunAt())
                .as("the missed occurrence is dropped, not retried").isAfter(Instant.now());
        List<ActivityLog> skips = activityLogRepository
                .findBySourceAndTimestampAfterOrderByTimestampDesc("ScheduledWorkflowExecutor", testStart).stream()
                .filter(entry -> schedule.getId().equals(entry.getReferenceId())).toList();
        assertThat(skips).singleElement().satisfies(entry ->
                assertThat(entry.getMetadata()).containsEntry("reason", "auto-resume-pending"));

        // The continuation wins: the interrupted run completes in place, as the only run.
        fixture.awaitResumerIdle();
        WorkflowRun resumed = fixture.awaitFinished(interrupted.getId());
        assertThat(resumed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.getTriggerSource()).isEqualTo(TriggerSource.AUTO_RESUME);
        assertThat(runRepository.countByWorkflowId(workflow.getId())).isEqualTo(1);
    }
}
