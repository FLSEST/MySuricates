package com.automation.service.scheduler;

import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The advance of a schedule after its run, against the database (TODO 0d2704db): an update saved
 * through {@link SchedulerService} while the run starts is still there afterwards. Before, the advance
 * computed the next run from the cron expression read before the start and merged it back over the
 * update. The run itself is a mock – its start is the moment the user's update lands.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ScheduleAdvanceIntegrationTest {

    @Autowired private ScheduledWorkflowRepository scheduledRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private ChainRunRepository chainRunRepository;
    @Autowired private ActivityLogService activityLogService;
    @Autowired private Clock clock;
    @Autowired private ModuleRegistry modules;
    @Autowired private ScheduleConflictCheck conflictCheck;
    @Autowired private ScheduleSkipPolicy skipPolicy;
    @Autowired private ScheduleAdvancer advancer;
    @Autowired private SchedulerService schedulerService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;

    private final WorkflowExecutor workflowExecutor = mock(WorkflowExecutor.class);
    private final List<Runnable> cleanup = new ArrayList<>();
    private ScheduledWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ScheduledWorkflowExecutor(scheduledRepository, workflowRunRepository, chainRunRepository,
                workflowExecutor, mock(ChainExecutor.class), activityLogService, clock, modules, conflictCheck,
                skipPolicy, advancer);
    }

    @AfterEach
    void tearDown() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            cleanup.get(i).run();
        }
    }

    @Test
    void cronChangedWhileTheRunStarts_theNextRunOfTheNewCronStays() {
        ScheduledWorkflow schedule = dueHourlySchedule();
        AtomicReference<Instant> nextRunOfTheUpdate = new AtomicReference<>();
        whenTheRunStarts(schedule, () -> nextRunOfTheUpdate.set(schedulerService.update(schedule.getId(),
                new UpdateScheduleRequest("0 30 * * * *", null, null, null, null, null, null, null)).getNextRunAt()));

        executor.executeSingleSchedule(schedule.getId());

        ScheduledWorkflow stored = scheduledRepository.findById(schedule.getId()).orElseThrow();
        assertThat(stored.getCronExpression()).isEqualTo("0 30 * * * *");
        assertThat(nextRunOfTheUpdate.get()).isNotNull();
        // Before: the next full hour – the next run of the cron read before the start.
        assertThat(stored.getNextRunAt()).isEqualTo(nextRunOfTheUpdate.get());
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getRunCount()).as("the run is counted").isEqualTo(1);
        assertThat(stored.getLastRunAt()).isNotNull();
    }

    @Test
    void disabledWhileTheRunStarts_staysDisabledWithoutANextRun() {
        ScheduledWorkflow schedule = dueHourlySchedule();
        whenTheRunStarts(schedule, () -> schedulerService.disable(schedule.getId()));

        executor.executeSingleSchedule(schedule.getId());

        ScheduledWorkflow stored = scheduledRepository.findById(schedule.getId()).orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getNextRunAt()).as("disable cleared it; the advance must not set it again").isNull();
        assertThat(stored.getRunCount()).isEqualTo(1);
        assertThat(stored.getLastRunAt()).isNotNull();
    }

    @Test
    void unchangedSchedule_movesToTheNextOccurrence() {
        ScheduledWorkflow schedule = dueHourlySchedule();
        whenTheRunStarts(schedule, () -> { });

        executor.executeSingleSchedule(schedule.getId());

        ScheduledWorkflow stored = scheduledRepository.findById(schedule.getId()).orElseThrow();
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getNextRunAt()).isAfter(Instant.now(clock));
        assertThat(stored.getRunCount()).isEqualTo(1);
    }

    private void whenTheRunStarts(ScheduledWorkflow schedule, Runnable userChange) {
        when(workflowExecutor.execute(any(Workflow.class), any(), eq(schedule.getId()), isNull(),
                eq(TriggerSource.SCHEDULE))).thenAnswer(inv -> {
                    userChange.run();
                    return null;
                });
    }

    /** An enabled hourly schedule whose occurrence a minute ago is due. */
    private ScheduledWorkflow dueHourlySchedule() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("schedule-advance-" + nano).type(ProjectType.CODE)
                .path("/tmp/schedule-advance-" + nano).status(ProjectStatus.STOPPED).build());
        cleanup.add(() -> projectRepository.deleteById(project.getId()));
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("schedule-advance-" + nano).project(project)
                .activatedSkills(new ArrayList<>()).defaultContext(Map.of()).metadata(Map.of())
                .build());
        cleanup.add(() -> workflowRepository.deleteById(workflow.getId()));
        ScheduledWorkflow schedule = scheduledRepository.save(ScheduledWorkflow.builder()
                .workflow(workflow).targetType(TargetType.WORKFLOW)
                .cronExpression("0 0 * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(Instant.now(clock).minusSeconds(60).truncatedTo(ChronoUnit.SECONDS))
                .inputContext(Map.of()).build());
        cleanup.add(() -> scheduledRepository.deleteById(schedule.getId()));
        return schedule;
    }
}
