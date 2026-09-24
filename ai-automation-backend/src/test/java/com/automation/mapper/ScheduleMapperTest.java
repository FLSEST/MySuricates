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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleMapperTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Mock private ActivityLogService activityLogService;
    @Mock private SchedulerMaintenanceMode maintenanceMode;

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
     * Mit aktiver skip-until-Pause zeigt kein Zeitplan einen Termin in der Pause als naechsten Lauf
     * (TODO a776fd2a): der Tick ueberspringt ihn erst, wenn er faellig wird.
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
            when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-4")).thenReturn(List.of());
            when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-4")).thenReturn(List.of());

            ScheduledWorkflowResponse response = mapper.toResponse(stale);

            assertThat(response.nextRunAt()).isEqualTo(local(24, 8));
            assertThat(response.pausedUntil()).isNull();
        }
    }

    /**
     * Im Wartungsmodus zeigt kein Zeitplan einen naechsten Lauf (TODO 7704b1e9): Der Tick startet
     * nichts, solange der Schalter an ist, und wann er ausgeht, weiss niemand.
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
            when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1"))
                    .thenReturn(List.of());
            when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1"))
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

            WorkflowRun run = WorkflowRun.builder().id("r-1").status(RunStatus.FAILED).build();
            when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1"))
                    .thenReturn(List.of(run));

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
            when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1"))
                    .thenReturn(List.of());
            when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1"))
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
            when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-2"))
                    .thenReturn(List.of());
            when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-2"))
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
            when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-2"))
                    .thenReturn(List.of());
            ChainRun chainRun = ChainRun.builder().id("cr-1").status(RunStatus.COMPLETED).build();
            when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-2"))
                    .thenReturn(List.of(chainRun));

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
        // Die Liste loest den Status seit TODO 172a6a67 gebuendelt auf — zwei Abfragen fuer alle
        // Zeitplaene statt zwei je Zeitplan. Deshalb hier die Batch-Methoden stubben.
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
        when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1")).thenReturn(List.of());
        when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1")).thenReturn(List.of());

        ScheduledWorkflowResponse response = mapper.toResponse(schedule);

        assertThat(response.conflictBehavior()).isEqualTo("SKIP");
    }

    /**
     * Die Listen-Variante loest den letzten Run-Status gebuendelt auf (TODO 172a6a67).
     *
     * <p>Vorher rief {@code toResponse} je Zeitplan zwei Repository-Methoden und lud dafuer
     * dessen komplette Run-Liste, um nur den ersten Status zu behalten: 60 Abfragen bei 30
     * Zeitplaenen, gemessen als 91 von 33 Statements auf {@code GET /api/v1/schedules}.
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
            // Die Einzelauflösung darf in der Liste nicht mehr vorkommen.
            verify(workflowRunRepository, never()).findByTriggeredByScheduleIdOrderByStartedAtDesc(anyString());
            verify(chainRunRepository, never()).findByTriggeredByScheduleIdOrderByStartedAtDesc(anyString());
        }

        /** Die Abfrage liefert absteigend nach Startzeit — der erste Treffer je Zeitplan gewinnt. */
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

        /** Ohne Workflow-Run zaehlt der Ketten-Run — dieselbe Rangfolge wie in der Einzelauflösung. */
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

        /** Ein Workflow-Run hat Vorrang, auch wenn ein Ketten-Run juenger ist. */
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

        /** Leere Liste fragt gar nicht — sonst liefe ein IN () gegen die Datenbank. */
        @Test
        void leereListeFragtNicht() {
            assertThat(mapper.toResponseList(List.of())).isEmpty();
            verifyNoInteractions(workflowRunRepository, chainRunRepository);
        }

        /** Die Detail-Route behaelt die Einzelauflösung — dort sind zwei Abfragen zwei Abfragen. */
        @Test
        void einzelneAntwortLoestWeiterEinzelnAuf() {
            when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s1"))
                    .thenReturn(List.of(WorkflowRun.builder().status(RunStatus.COMPLETED).build()));

            assertThat(mapper.toResponse(schedule("s1")).lastRunStatus())
                    .isEqualTo(RunStatus.COMPLETED);
        }
    }

}
