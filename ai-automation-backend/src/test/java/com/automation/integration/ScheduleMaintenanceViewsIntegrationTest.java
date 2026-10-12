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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In maintenance mode no view shows a next run (TODO 7704b1e9): the tick starts nothing
 * while the switch is on, and nobody knows when it goes off again. List, workflow page,
 * calendar and dashboard projection follow the same rule as for the skip-until pause
 * ({@code ScheduleSkipPolicy.effectiveNextRun}) — against the real database. The switch is set via
 * the REST route and rolled back with the test transaction; the tick is off under the profile
 * "integration".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class ScheduleMaintenanceViewsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ScheduledWorkflowRepository scheduledWorkflowRepository;

    private final Instant now = Instant.now();
    private Workflow workflow;
    /** Due every minute: every view's window holds a run of it — unless maintenance holds it. */
    private ScheduledWorkflow everyMinute;
    /** A one-off two hours ahead. */
    private ScheduledWorkflow once;

    @BeforeEach
    void seed() throws Exception {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("maintenance-views-" + nano).type(ProjectType.CODE)
                .path("/tmp/maintenance-views-" + nano).status(ProjectStatus.STOPPED).build());
        workflow = workflowRepository.save(Workflow.builder()
                .name("Maintenance Views " + nano).project(project).blocks(new ArrayList<>()).build());
        everyMinute = schedule(ScheduledWorkflow.ScheduleType.CRON, "0 * * * * *",
                now.plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES));
        once = schedule(ScheduledWorkflow.ScheduleType.ONCE, "", now.plus(2, ChronoUnit.HOURS));
        setMaintenance(true);
    }

    private ScheduledWorkflow schedule(ScheduledWorkflow.ScheduleType type, String cron, Instant nextRunAt) {
        return scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                .workflow(workflow).targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression(cron).scheduleType(type)
                .scheduledAt(type == ScheduledWorkflow.ScheduleType.ONCE ? nextRunAt : null)
                .nextRunAt(nextRunAt).enabled(true).inputContext(Map.of()).build());
    }

    private void setMaintenance(boolean enabled) throws Exception {
        mockMvc.perform(post("/api/v1/schedules/maintenance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": " + enabled + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode getJson(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode byId(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.get("id").asText())) return node;
        }
        throw new AssertionError("schedule " + id + " missing in " + array);
    }

    private List<String> ours(JsonNode array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : array) {
            String id = node.get("id").asText();
            if (id.equals(everyMinute.getId()) || id.equals(once.getId())) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Test
    void listAndWorkflowPage_showNoNextRun_andSayWhy() throws Exception {
        for (String url : List.of("/api/v1/schedules", "/api/v1/schedules/workflow/" + workflow.getId())) {
            JsonNode list = getJson(url);

            for (ScheduledWorkflow schedule : List.of(everyMinute, once)) {
                JsonNode node = byId(list, schedule.getId());
                assertThat(node.get("nextRunAt").isNull()).as("%s: %s shows no next run", url, schedule.getId()).isTrue();
                assertThat(node.get("maintenance")).as(url).isNotNull();
                assertThat(node.get("maintenance").asBoolean()).as(url).isTrue();
                assertThat(node.get("pausedUntil").isNull()).as("%s: no skip-until pause here", url).isTrue();
            }
        }
        assertThat(scheduledWorkflowRepository.findById(everyMinute.getId()).orElseThrow().getNextRunAt())
                .as("a view never writes").isEqualTo(everyMinute.getNextRunAt());
    }

    @Test
    void calendar_placesNoUpcomingRun() throws Exception {
        JsonNode calendar = getJson("/api/v1/schedules/calendar?from=" + now.minus(1, ChronoUnit.DAYS)
                + "&to=" + now.plus(2, ChronoUnit.DAYS));

        assertThat(ours(calendar.get("upcoming"))).isEmpty();
    }

    @Test
    void dashboard_listsNoRun_andSaysMaintenance() throws Exception {
        for (String url : List.of("/api/v1/schedules/remaining-today", "/api/v1/schedules/remaining-this-week")) {
            JsonNode upcoming = getJson(url);

            assertThat(upcoming.get("maintenance")).as(url).isNotNull();
            assertThat(upcoming.get("maintenance").asBoolean()).as(url).isTrue();
            assertThat(upcoming.get("count").asInt()).as(url).isZero();
            assertThat(upcoming.get("schedules")).as(url).isEmpty();
        }
        assertThat(getJson("/api/v1/schedules/maintenance").get("enabled").asBoolean()).isTrue();
    }

    @Test
    void switchedOff_theStoredNextRunIsShownAgain() throws Exception {
        setMaintenance(false);

        JsonNode node = byId(getJson("/api/v1/schedules"), everyMinute.getId());

        assertThat(Instant.parse(node.get("nextRunAt").asText())).isEqualTo(everyMinute.getNextRunAt());
        assertThat(node.get("maintenance").asBoolean()).isFalse();
    }
}
