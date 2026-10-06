package com.automation.mapper;

import com.automation.config.SchedulerConfig;
import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.scheduler.ScheduleSkipPolicy;
import com.automation.service.scheduler.SchedulerMaintenanceMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleMapperTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Mock private ActivityLogService activityLogService;
    @Mock private SchedulerMaintenanceMode maintenanceMode;

    /** The single resolution queries only the newest row (TODO 86286572). */
    private static final Pageable NEWEST = PageRequest.of(0, 1);

    private static final Instant NOW = LocalDateTime.of(2026, 9, 24, 10, 0).atZone(ZoneId.systemDefault()).toInstant();

    /** Real config: without skip-until the mapper passes the stored next run through. */
    private final SchedulerConfig schedulerConfig = new SchedulerConfig();

    private ScheduleMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ScheduleMapper(workflowRunRepository, chainRunRepository,
                new ScheduleSkipPolicy(schedulerConfig, scheduledWorkflowRepository, activityLogService, maintenanceMode),
                Clock.fixed(NOW, ZoneId.systemDefault()));
    }

    private static Instant local(int day, int hour) {
        return LocalDateTime.of(2026, 9, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * With an active skip-until pause no schedule shows a fire time inside the pause as its next run
     * (TODO a776fd2a): the tick only skips it when it becomes due.
     */
    @Nested
    class SkipUntilPause {

        private ScheduledWorkflow daily(String id, Instant storedNextRun) {
            return ScheduledWorkflow.builder().id(id).workflow(Workflow.builder().id("wf-" + id).name("Daily").build())
                    .targetType(ScheduledWorkflow.TargetType.WORKFLOW).cronExpression("0 0 8 * * *")
                    .scheduleType(ScheduledWorkflow.ScheduleType.CRON).enabled(true).nextRunAt(storedNextRun).build();
        }

        @Test
        void storedRunInsideThePause_showsTheFirstRunAfterIt_andTheEnd() {
            schedulerConfig.setSkipUntil(local(26, 12).toString());

            ScheduledWorkflowResponse response = mapper.toResponseList(List.of(daily("s-1", local(25, 8)))).get(0);

            assertThat(response.nextRunAt()).isEqualTo(local(27, 8));
            assertThat(response.pausedUntil()).isEqualTo(local(26, 12));
        }

        @Test
        void storedRunAfterThePause_staysAsItIs() {
            schedulerConfig.setSkipUntil(local(26, 12).toString());

            ScheduledWorkflowResponse response = mapper.toResponseList(List.of(daily("s-2", local(27, 8)))).get(0);

            assertThat(response.nextRunAt()).isEqualTo(local(27, 8));
            assertThat(response.pausedUntil()).isNull();
        }

        @Test
        void oneOffInsideThePause_hasNoNextRun() {
            schedulerConfig.setSkipUntil(local(26, 12).toString());
            ScheduledWorkflow once = ScheduledWorkflow.builder().id("s-3")
                    .workflow(Workflow.builder().id("wf-3").name("Once").build())
                    .targetType(ScheduledWorkflow.TargetType.WORKFLOW).cronExpression("")
                    .scheduleType(ScheduledWorkflow.ScheduleType.ONCE).scheduledAt(local(25, 9))
                    .nextRunAt(local(25, 9)).enabled(true).build();

            ScheduledWorkflowResponse response = mapper.toResponseList(List.of(once)).get(0);

            assertThat(response.nextRunAt()).isNull();
            assertThat(response.pausedUntil()).isEqualTo(local(26, 12));
            assertThat(response.scheduledAt()).isEqualTo(local(25, 9));
        }

        @Test
        void pauseThatAlreadyEnded_changesNothing() {
            schedulerConfig.setSkipUntil(local(20, 12).toString());
            ScheduledWorkflow stale = daily("s-4", local(24, 8));
            when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s-4", NEWEST)).thenReturn(List.of());
            when(chainRunRepository.findStatusesByScheduleIdNewestFirst("s-4", NEWEST)).thenReturn(List.of());

            ScheduledWorkflowResponse response = mapper.toResponse(stale);

            assertThat(response.nextRunAt()).isEqualTo(local(24, 8));
            assertThat(response.pausedUntil()).isNull();
        }
    }

    /**
     * In maintenance mode no schedule shows a next run (TODO 7704b1e9): the tick starts
     * nothing while the switch is on, and nobody knows when it goes off.
     */
    @Nested
    class MaintenanceSwitch {

        private ScheduledWorkflow daily(String id, Instant storedNextRun, boolean enabled) {
            return ScheduledWorkflow.builder().id(id).workflow(Workflow.builder().id("wf-" + id).name("Daily").build())
                    .targetType(ScheduledWorkflow.TargetType.WORKFLOW).cronExpression("0 0 8 * * *")
                    .scheduleType(ScheduledWorkflow.ScheduleType.CRON).enabled(enabled).nextRunAt(storedNextRun).build();
        }

        @Test
        void heldSchedule_showsNoNextRun_andSaysWhy() {
            when(maintenanceMode.isEnabled()).thenReturn(true);
            ScheduledWorkflow held = daily("s-1", local(25, 8), true);

            ScheduledWorkflowResponse response = mapper.toResponseList(List.of(held)).get(0);

            assertThat(response.nextRunAt()).isNull();
            assertThat(response.maintenance()).isTrue();
            assertThat(response.pausedUntil()).isNull();
            assertThat(held.getNextRunAt()).as("a view never writes").isEqualTo(local(25, 8));
        }

        @Test
        void scheduleWithoutNextRun_isNotHeld() {
            when(maintenanceMode.isEnabled()).thenReturn(true);

            ScheduledWorkflowResponse response = mapper.toResponseList(List.of(daily("s-2", null, false))).get(0);

            assertThat(response.nextRunAt()).isNull();
            assertThat(response.maintenance()).isFalse();
        }

        @Test
        void switchOff_showsTheStoredNextRun() {
            ScheduledWorkflowResponse response = mapper.toResponseList(List.of(daily("s-3", local(25, 8), true))).get(0);

            assertThat(response.nextRunAt()).isEqualTo(local(25, 8));
            assertThat(response.maintenance()).isFalse();
        }
    }

    @Nested
    class WorkflowTarget {

        @Test
        void mapsWorkflowFields() {
            Project project = new Project();
            project.setName("Project A");
            Workflow wf = Workflow.builder().id("wf-1").name("WF A").project(project).build();
            ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                    .id("s-1").workflow(wf).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                    .cronExpression("0 0 * * *").scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .enabled(true).runCount(5).build();
            when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s-1", NEWEST))
                    .thenReturn(List.of());
            when(chainRunRepository.findStatusesByScheduleIdNewestFirst("s-1", NEWEST))
                    .thenReturn(List.of());

            ScheduledWorkflowResponse response = mapper.toResponse(schedule);

            assertThat(response.workflowId()).isEqualTo("wf-1");
            assertThat(response.workflowName()).isEqualTo("WF A");
            assertThat(response.projectName()).isEqualTo("Project A");
            assertThat(response.chainId()).isNull();
            assertThat(response.targetType()).isEqualTo("WORKFLOW");
            assertThat(response.enabled()).isTrue();
        }

        @Test
        void resolvesLastRunStatus_fromWorkflowRuns() {
            Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
            ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                    .id("s-1").workflow(wf).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                    .cronExpression("0 0 * * *").scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .build();

            when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s-1", NEWEST))
                    .thenReturn(List.of(RunStatus.FAILED));

            ScheduledWorkflowResponse response = mapper.toResponse(schedule);

            assertThat(response.lastRunStatus()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        void noRuns_lastRunStatusNull() {
            Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
            ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                    .id("s-1").workflow(wf).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                    .cronExpression("0 0 * * *").scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .build();
            when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s-1", NEWEST))
                    .thenReturn(List.of());
            when(chainRunRepository.findStatusesByScheduleIdNewestFirst("s-1", NEWEST))
                    .thenReturn(List.of());

            ScheduledWorkflowResponse response = mapper.toResponse(schedule);

            assertThat(response.lastRunStatus()).isNull();
        }
    }

    @Nested
    class ChainTarget {

        @Test
        void mapsChainFields() {
            WorkflowChain chain = WorkflowChain.builder().id("ch-1").name("Chain A").build();
            ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                    .id("s-2").chain(chain).targetType(ScheduledWorkflow.TargetType.CHAIN)
                    .cronExpression("0 0 * * *").scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .build();
            when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s-2", NEWEST))
                    .thenReturn(List.of());
            when(chainRunRepository.findStatusesByScheduleIdNewestFirst("s-2", NEWEST))
                    .thenReturn(List.of());

            ScheduledWorkflowResponse response = mapper.toResponse(schedule);

            assertThat(response.chainId()).isEqualTo("ch-1");
            assertThat(response.chainName()).isEqualTo("Chain A");
            assertThat(response.workflowId()).isNull();
            assertThat(response.targetType()).isEqualTo("CHAIN");
        }

        @Test
        void resolvesLastRunStatus_fromChainRuns() {
            WorkflowChain chain = WorkflowChain.builder().id("ch-1").name("Chain").build();
            ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                    .id("s-2").chain(chain).targetType(ScheduledWorkflow.TargetType.CHAIN)
                    .cronExpression("0 0 * * *").scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .build();
            when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s-2", NEWEST))
                    .thenReturn(List.of());
            when(chainRunRepository.findStatusesByScheduleIdNewestFirst("s-2", NEWEST))
                    .thenReturn(List.of(RunStatus.COMPLETED));

            ScheduledWorkflowResponse response = mapper.toResponse(schedule);

            assertThat(response.lastRunStatus()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
        ScheduledWorkflow s1 = ScheduledWorkflow.builder()
                .id("s-1").workflow(wf).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression("* *").scheduleType(ScheduledWorkflow.ScheduleType.CRON).build();
        ScheduledWorkflow s2 = ScheduledWorkflow.builder()
                .id("s-2").workflow(wf).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression("* *").scheduleType(ScheduledWorkflow.ScheduleType.CRON).build();
        // Since TODO 172a6a67 the list resolves the status in bulk — two queries for all
        // schedules instead of two per schedule. Hence stub the batch methods here.
        when(workflowRunRepository.findStatusesByScheduleIds(List.of("s-1", "s-2"))).thenReturn(List.of());
        when(chainRunRepository.findStatusesByScheduleIds(List.of("s-1", "s-2"))).thenReturn(List.of());

        List<ScheduledWorkflowResponse> result = mapper.toResponseList(List.of(s1, s2));

        assertThat(result).hasSize(2);
    }

    @Test
    void conflictBehavior_null_defaultsToSkip() {
        Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
        ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                .id("s-1").workflow(wf).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression("0 0 * * *").scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                .conflictBehavior(null).build();
        when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s-1", NEWEST)).thenReturn(List.of());
        when(chainRunRepository.findStatusesByScheduleIdNewestFirst("s-1", NEWEST)).thenReturn(List.of());

        ScheduledWorkflowResponse response = mapper.toResponse(schedule);

        assertThat(response.conflictBehavior()).isEqualTo("SKIP");
    }

    /**
     * The list variant resolves the last run status in bulk (TODO 172a6a67).
     *
     * <p>Before, {@code toResponse} called two repository methods per schedule and loaded
     * its complete run list for that, only to keep the first status: 60 queries for 30
     * schedules, measured as 91 of 33 statements on {@code GET /api/v1/schedules}.
     */
    @Nested
    class ListeMitGebuendelterStatusAufloesung {

        private ScheduledWorkflow schedule(String id) {
            return ScheduledWorkflow.builder()
                    .id(id)
                    .workflow(Workflow.builder().id("wf-" + id).name("W " + id).build())
                    .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                    .cronExpression("0 0 6 * * MON")
                    .scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .build();
        }

        @Test
        void zweiAbfragenFuerAlleZeitplaene_nichtZweiJeZeitplan() {
            when(workflowRunRepository.findStatusesByScheduleIds(List.of("s1", "s2", "s3")))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{"s1", RunStatus.COMPLETED, Instant.parse("2026-09-02T10:00:00Z")},
                            new Object[]{"s2", RunStatus.FAILED, Instant.parse("2026-09-02T09:00:00Z")}));
            when(chainRunRepository.findStatusesByScheduleIds(List.of("s1", "s2", "s3")))
                    .thenReturn(List.of());

            List<ScheduledWorkflowResponse> responses = mapper.toResponseList(
                    List.of(schedule("s1"), schedule("s2"), schedule("s3")));

            assertThat(responses).extracting(ScheduledWorkflowResponse::lastRunStatus)
                    .containsExactly(RunStatus.COMPLETED, RunStatus.FAILED, null);
            // The single resolution must no longer occur in the list.
            verify(workflowRunRepository, never()).findStatusesByScheduleIdNewestFirst(anyString(), any());
            verify(chainRunRepository, never()).findStatusesByScheduleIdNewestFirst(anyString(), any());
        }

        /** The query returns descending by start time — the first hit per schedule wins. */
        @Test
        void derJuengsteRunGewinnt() {
            when(workflowRunRepository.findStatusesByScheduleIds(List.of("s1")))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{"s1", RunStatus.RUNNING, Instant.parse("2026-09-03T10:00:00Z")},
                            new Object[]{"s1", RunStatus.FAILED, Instant.parse("2026-09-01T10:00:00Z")}));
            when(chainRunRepository.findStatusesByScheduleIds(List.of("s1"))).thenReturn(List.of());

            assertThat(mapper.toResponseList(List.of(schedule("s1"))))
                    .singleElement()
                    .extracting(ScheduledWorkflowResponse::lastRunStatus)
                    .isEqualTo(RunStatus.RUNNING);
        }

        /** Without a workflow run the chain run counts — same ranking as in the single resolution. */
        @Test
        void ohneWorkflowRunZaehltDerKettenRun() {
            when(workflowRunRepository.findStatusesByScheduleIds(List.of("s1"))).thenReturn(List.of());
            when(chainRunRepository.findStatusesByScheduleIds(List.of("s1")))
                    .thenReturn(List.<Object[]>of(new Object[]{"s1", RunStatus.COMPLETED,
                            Instant.parse("2026-09-02T10:00:00Z")}));

            assertThat(mapper.toResponseList(List.of(schedule("s1"))))
                    .singleElement()
                    .extracting(ScheduledWorkflowResponse::lastRunStatus)
                    .isEqualTo(RunStatus.COMPLETED);
        }

        /** A workflow run takes precedence, even if a chain run is newer. */
        @Test
        void workflowRunHatVorrangVorKettenRun() {
            when(workflowRunRepository.findStatusesByScheduleIds(List.of("s1")))
                    .thenReturn(List.<Object[]>of(new Object[]{"s1", RunStatus.FAILED,
                            Instant.parse("2026-09-01T10:00:00Z")}));
            when(chainRunRepository.findStatusesByScheduleIds(List.of("s1")))
                    .thenReturn(List.<Object[]>of(new Object[]{"s1", RunStatus.COMPLETED,
                            Instant.parse("2026-09-05T10:00:00Z")}));

            assertThat(mapper.toResponseList(List.of(schedule("s1"))))
                    .singleElement()
                    .extracting(ScheduledWorkflowResponse::lastRunStatus)
                    .isEqualTo(RunStatus.FAILED);
        }

        /** An empty list does not query at all — otherwise an IN () would run against the database. */
        @Test
        void leereListeFragtNicht() {
            assertThat(mapper.toResponseList(List.of())).isEmpty();
            verifyNoInteractions(workflowRunRepository, chainRunRepository);
        }

        /**
         * The detail route keeps the single resolution — but now reads only the newest status
         * (one row, {@code PageRequest.of(0, 1)}), no run list as entities (TODO 86286572).
         * If the schedule has a workflow run, chain runs are not even queried.
         */
        @Test
        void einzelneAntwortLiestNurDenNeuestenStatus() {
            when(workflowRunRepository.findStatusesByScheduleIdNewestFirst("s1", NEWEST))
                    .thenReturn(List.of(RunStatus.COMPLETED));

            assertThat(mapper.toResponse(schedule("s1")).lastRunStatus())
                    .isEqualTo(RunStatus.COMPLETED);
            verify(workflowRunRepository).findStatusesByScheduleIdNewestFirst("s1", PageRequest.of(0, 1));
            verifyNoMoreInteractions(workflowRunRepository);
            verifyNoInteractions(chainRunRepository);
        }
    }

}
