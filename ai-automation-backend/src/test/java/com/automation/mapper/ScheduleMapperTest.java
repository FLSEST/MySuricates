package com.automation.mapper;

import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleMapperTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ChainRunRepository chainRunRepository;

    private ScheduleMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ScheduleMapper(workflowRunRepository, chainRunRepository);
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
        when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1")).thenReturn(List.of());
        when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-2")).thenReturn(List.of());
        when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-1")).thenReturn(List.of());
        when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("s-2")).thenReturn(List.of());

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
}
