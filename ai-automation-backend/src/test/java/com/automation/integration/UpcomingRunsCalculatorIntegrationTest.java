package com.automation.integration;

import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.scheduler.UpcomingRunsCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remaining-today projection navigates {@code schedule.workflow.project} lazily. OSIV is
 * off, so this only works through the {@code @Transactional(readOnly = true)} proxy of
 * {@link UpcomingRunsCalculator}. Deliberately <b>not</b> {@code @Transactional}: a
 * transactional test would keep its own session open and mask a lost annotation
 * (Audit-TODO 05dbd0e9).
 */
@SpringBootTest
@ActiveProfiles("integration")
@Import(UpcomingRunsCalculatorIntegrationTest.FixedClock.class)
class UpcomingRunsCalculatorIntegrationTest {

    static final Instant FIXED_NOW = Instant.parse("2026-04-26T12:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        }
    }

    @Autowired private UpcomingRunsCalculator calculator;
    @Autowired private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;

    private final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        cleanup.forEach(r -> {
            try { r.run(); } catch (Exception ignored) { /* already gone */ }
        });
        cleanup.clear();
    }

    @Test
    void getRemainingToday_outsideTransaction_resolvesLazyProjectName() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("sched-it-" + nano).type(ProjectType.CODE)
                .path("/tmp/sched-it-" + nano).status(ProjectStatus.STOPPED).build());
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("sched-wf-" + nano).project(project).blocks(new ArrayList<>()).build());
        ScheduledWorkflow schedule = scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                .workflow(workflow)
                .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression("")
                .scheduleType(ScheduledWorkflow.ScheduleType.ONCE)
                .scheduledAt(FIXED_NOW.plusSeconds(3600))
                .nextRunAt(FIXED_NOW.plusSeconds(3600))
                .enabled(true)
                .inputContext(Map.of())
                .build());
        cleanup.add(() -> scheduledWorkflowRepository.deleteById(schedule.getId()));
        cleanup.add(() -> workflowRepository.deleteById(workflow.getId()));
        cleanup.add(() -> projectRepository.deleteById(project.getId()));

        // Called outside any transaction — like ScheduleController does.
        UpcomingScheduleResponse response = calculator.getRemainingToday();

        var run = response.schedules().stream()
                .filter(r -> r.scheduleId().equals(schedule.getId()))
                .findFirst().orElseThrow();
        assertThat(run.workflowName()).isEqualTo(workflow.getName());
        assertThat(run.projectName()).isEqualTo(project.getName());
    }
}
