package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * With {@code skip-until} set, no view shows a fire time inside the pause window as the next
 * run (TODO a776fd2a) — list, workflow page, calendar, dashboard projection and pause status,
 * against the real database. The tick only winds a fire time past the pause when it becomes
 * due; until then the read paths recompute. Scheduling is off under the profile "integration".
 */
@SpringBootTest(properties = "automation.scheduler.skip-until=2026-04-28T12:00:00Z")
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(ScheduleSkipUntilViewsIntegrationTest.FixedClock.class)
@Transactional
class ScheduleSkipUntilViewsIntegrationTest {

    /** A Monday: the week of the dashboard projection reaches past the end of the pause. */
    static final Instant NOW = Instant.parse("2026-04-27T12:00:00Z");
    static final Instant PAUSE_END = Instant.parse("2026-04-28T12:00:00Z");
    private static final String DAILY_8 = "0 0 8 * * *";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneId.systemDefault());
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ScheduledWorkflowRepository scheduledWorkflowRepository;

    private Workflow workflow;
    /** Stored next run inside the pause. */
    private ScheduledWorkflow heldDaily;
    /** One-off inside the pause: the tick will drop it. */
    private ScheduledWorkflow heldOnce;
    /** Stored next run after the pause: untouched. */
    private ScheduledWorkflow laterDaily;

    @BeforeEach
    void seed() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("skip-until-views-" + nano).type(ProjectType.CODE)
                .path("/tmp/skip-until-views-" + nano).status(ProjectStatus.STOPPED).build());
        workflow = workflowRepository.save(Workflow.builder()
                .name("Skip Until Views " + nano).project(project).blocks(new ArrayList<>()).build());
        heldDaily = schedule(ScheduledWorkflow.ScheduleType.CRON, DAILY_8, Instant.parse("2026-04-28T06:00:00Z"));
        heldOnce = schedule(ScheduledWorkflow.ScheduleType.ONCE, "", Instant.parse("2026-04-27T18:00:00Z"));
        laterDaily = schedule(ScheduledWorkflow.ScheduleType.CRON, DAILY_8, Instant.parse("2026-05-01T06:00:00Z"));
    }

    private ScheduledWorkflow schedule(ScheduledWorkflow.ScheduleType type, String cron, Instant nextRunAt) {
        return scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                .workflow(workflow).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression(cron).scheduleType(type)
                .scheduledAt(type == ScheduledWorkflow.ScheduleType.ONCE ? nextRunAt : null)
                .nextRunAt(nextRunAt).enabled(true).inputContext(Map.of()).build());
    }

    /** Where the tick will put the held daily schedule: its first occurrence at or after the end. */
    private static Instant firstDailyAfterPause() {
        return CronExpression.parse(DAILY_8)
                .next(LocalDateTime.ofInstant(PAUSE_END, ZoneId.systemDefault()).minusNanos(1))
                .atZone(ZoneId.systemDefault()).toInstant();
    }

    private JsonNode getJson(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode byId(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.get("id").asText())) return node;
        }
        return null;
    }

    private static Instant instant(JsonNode node, String field) {
        return node.get(field).isNull() ? null : Instant.parse(node.get(field).asText());
    }

    private List<String> ours(JsonNode array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : array) {
            String id = node.get("id").asText();
            if (id.equals(heldDaily.getId()) || id.equals(heldOnce.getId()) || id.equals(laterDaily.getId())) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Test
    void listAndWorkflowPage_showTheRunThatReallyComes() throws Exception {
        for (String url : List.of("/api/v1/schedules", "/api/v1/schedules/workflow/" + workflow.getId())) {
            JsonNode list = getJson(url);

            JsonNode held = byId(list, heldDaily.getId());
            assertThat(instant(held, "nextRunAt")).as(url).isEqualTo(firstDailyAfterPause());
            assertThat(instant(held, "pausedUntil")).as(url).isEqualTo(PAUSE_END);

            JsonNode once = byId(list, heldOnce.getId());
            assertThat(instant(once, "nextRunAt")).as("%s: a one-off inside the pause expires", url).isNull();
            assertThat(instant(once, "pausedUntil")).as(url).isEqualTo(PAUSE_END);

            JsonNode later = byId(list, laterDaily.getId());
            assertThat(instant(later, "nextRunAt")).as(url).isEqualTo(Instant.parse("2026-05-01T06:00:00Z"));
            assertThat(instant(later, "pausedUntil")).as(url).isNull();
        }
        assertThat(scheduledWorkflowRepository.findById(heldDaily.getId()).orElseThrow().getNextRunAt())
                .as("a view never writes").isEqualTo(Instant.parse("2026-04-28T06:00:00Z"));
    }

    @Test
    void calendar_placesNothingInsideThePause() throws Exception {
        JsonNode during = getJson("/api/v1/schedules/calendar?from=2026-04-27T00:00:00Z&to=2026-04-28T11:59:59Z");
        JsonNode after = getJson("/api/v1/schedules/calendar?from=2026-04-28T12:00:00Z&to=2026-05-02T00:00:00Z");

        assertThat(ours(during.get("upcoming"))).isEmpty();
        assertThat(ours(after.get("upcoming"))).containsExactlyInAnyOrder(heldDaily.getId(), laterDaily.getId());
        assertThat(instant(byId(after.get("upcoming"), heldDaily.getId()), "nextRunAt")).isEqualTo(firstDailyAfterPause());
    }

    @Test
    void dashboardAndPauseStatus_nameTheEndOfThePause() throws Exception {
        JsonNode week = getJson("/api/v1/schedules/remaining-this-week");
        JsonNode pause = getJson("/api/v1/schedules/maintenance");

        assertThat(instant(week, "pausedUntil")).isEqualTo(PAUSE_END);
        List<String> scheduleIds = new ArrayList<>();
        for (JsonNode run : week.get("schedules")) {
            scheduleIds.add(run.get("scheduleId").asText());
            assertThat(Instant.parse(run.get("scheduledAt").asText()))
                    .as("no upcoming run inside the pause").isAfterOrEqualTo(PAUSE_END);
        }
        assertThat(scheduleIds).as("the held daily schedule runs again after the pause, in this week")
                .contains(heldDaily.getId())
                .as("the one-off inside the pause expires").doesNotContain(heldOnce.getId());
        assertThat(pause.get("enabled").asBoolean()).isFalse();
        assertThat(instant(pause, "pausedUntil")).isEqualTo(PAUSE_END);
    }
}
