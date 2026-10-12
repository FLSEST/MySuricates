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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What happens when a schedule points to a soft-deleted workflow?
 *
 * <p>{@code Workflow} carries {@code @SQLRestriction("deleted_at IS NULL")}, the association
 * {@code ScheduledWorkflow.workflow} is {@code EAGER} and for a long time had no {@code @NotFound}.
 * Hibernate then reloaded the association row by row, the restriction hid the row
 * and loading aborted with {@code FetchNotFoundException} — the whole
 * list, because of a single orphaned schedule (TODO c9ec9844, measured 2026-09-19).
 *
 * <p>{@code ScheduleControllerTest} is a {@code @WebMvcTest} with a mocked service and cannot show
 * this: the question only arises in the real persistence context. Hence this
 * integration test — it pins the behavior so that the fetch strategy of the list
 * (JOIN FETCH instead of a query per row) is not changed in the dark.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class ScheduleSoftDeletedWorkflowIntegrationTest {

    /** Next occurrence of the seeded schedules — an active schedule always has one in operation. */
    private static final Instant NEXT_RUN = Instant.parse("2026-09-07T06:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager entityManager;

    private Workflow seedWorkflowWithSchedule(String suffix) {
        Project project = projectRepository.save(Project.builder()
                .name("Orphan Schedule Project " + suffix)
                .type(ProjectType.CODE)
                .path("/tmp/orphan-schedule-" + suffix)
                .status(ProjectStatus.STOPPED)
                .build());

        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("Orphan Schedule Workflow " + suffix)
                .description("Seeded for the soft-delete question of TODO c9ec9844")
                .project(project)
                .build());

        scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                .workflow(workflow)
                .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression("0 0 6 * * MON")
                .scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(NEXT_RUN)
                .description("Schedule " + suffix)
                .inputContext(Map.of())
                .build());

        return workflow;
    }

    /**
     * The actual behavior, pinned: a schedule whose workflow was soft-deleted must not crash the
     * list. It stays visible — without a workflow reference, so that it can be deleted in the UI
     * at all.
     */
    @Test
    void listingSurvivesAScheduleWhoseWorkflowWasSoftDeleted() throws Exception {
        Workflow doomed = seedWorkflowWithSchedule("doomed");
        Workflow alive = seedWorkflowWithSchedule("alive");

        mockMvc.perform(delete("/api/v1/workflows/" + doomed.getId()))
                .andReturn();

        entityManager.flush();
        entityManager.clear();

        MvcResult result;
        try {
            result = mockMvc.perform(get("/api/v1/schedules")).andReturn();
        } catch (Exception e) {
            throw new AssertionError(
                    "GET /api/v1/schedules brach ab, weil ein Zeitplan auf einen soft-geloeschten "
                            + "Workflow zeigt: " + rootCause(e), e);
        }

        assertThat(result.getResponse().getStatus())
                .as("Ein verwaister Zeitplan darf die ganze Liste nicht unbrauchbar machen")
                .isEqualTo(200);

        JsonNode all = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode orphan = scheduleWithDescription(all, "Schedule doomed");
        assertThat(orphan)
                .as("Der Zeitplan des geloeschten Workflows ist weiterhin gelistet")
                .isNotNull();
        assertThat(orphan.get("workflowId").isNull())
                .as("Ist-Verhalten: Die Assoziation loest auf null auf, sie wirft nicht")
                .isTrue();
        assertThat(orphan.get("workflowName").isNull()).isTrue();
        assertThat(orphan.get("projectName").isNull()).isTrue();

        JsonNode intact = scheduleWithDescription(all, "Schedule alive");
        assertThat(intact).isNotNull();
        assertThat(intact.get("workflowName").asText())
                .as("Der Nachbar-Zeitplan bleibt vollstaendig")
                .isEqualTo("Orphan Schedule Workflow alive");
        assertThat(intact.get("projectName").asText())
                .isEqualTo("Orphan Schedule Project alive");
    }

    /**
     * The calendar reads its entries via {@code findSchedulesInRange} — the same question as for
     * the list. Since TODO 168e3d7f the query fetches the targets along via {@code LEFT JOIN FETCH} like
     * {@code findAllWithTargets()}, instead of reloading them row by row. The behavior for the
     * orphaned schedule stays the same as before (checked against the old query): the
     * entry appears without a target, and the calendar responds.
     */
    @Test
    void calendarSurvivesAScheduleWhoseWorkflowWasSoftDeleted() throws Exception {
        Workflow doomed = seedWorkflowWithSchedule("calendar-doomed");
        seedWorkflowWithSchedule("calendar-alive");

        mockMvc.perform(delete("/api/v1/workflows/" + doomed.getId())).andReturn();

        entityManager.flush();
        entityManager.clear();

        MvcResult result;
        try {
            result = mockMvc.perform(get("/api/v1/schedules/calendar")
                    .param("from", "2026-09-07T00:00:00Z")
                    .param("to", "2026-09-08T00:00:00Z")).andReturn();
        } catch (Exception e) {
            throw new AssertionError(
                    "GET /api/v1/schedules/calendar brach ab, weil ein Zeitplan auf einen "
                            + "soft-geloeschten Workflow zeigt: " + rootCause(e), e);
        }

        assertThat(result.getResponse().getStatus())
                .as("Ein verwaister Zeitplan darf den Kalender nicht unbrauchbar machen")
                .isEqualTo(200);

        JsonNode upcoming = objectMapper.readTree(result.getResponse().getContentAsString()).get("upcoming");

        JsonNode orphan = scheduleWithDescription(upcoming, "Schedule calendar-doomed");
        assertThat(orphan)
                .as("Ist-Verhalten wie in der Liste: Der Termin bleibt, ohne Ziel")
                .isNotNull();
        assertThat(orphan.get("workflowId").isNull()).isTrue();

        JsonNode intact = scheduleWithDescription(upcoming, "Schedule calendar-alive");
        assertThat(intact).isNotNull();
        assertThat(intact.get("workflowName").asText())
                .as("Der Nachbar-Termin bleibt vollstaendig")
                .isEqualTo("Orphan Schedule Workflow calendar-alive");
        assertThat(intact.get("projectName").asText())
                .isEqualTo("Orphan Schedule Project calendar-alive");
    }

    private static JsonNode scheduleWithDescription(JsonNode all, String description) {
        for (JsonNode node : all) {
            if (description.equals(node.path("description").asText(null))) {
                return node;
            }
        }
        return null;
    }

    /** The detail route of an orphaned schedule must also respond instead of throwing. */
    @Test
    void detailRouteSurvivesASoftDeletedWorkflow() throws Exception {
        Workflow doomed = seedWorkflowWithSchedule("detail");
        String scheduleId = scheduledWorkflowRepository.findByWorkflowId(doomed.getId())
                .get(0).getId();

        mockMvc.perform(delete("/api/v1/workflows/" + doomed.getId())).andReturn();

        entityManager.flush();
        entityManager.clear();

        MvcResult result;
        try {
            result = mockMvc.perform(get("/api/v1/schedules/" + scheduleId)).andReturn();
        } catch (Exception e) {
            throw new AssertionError(
                    "GET /api/v1/schedules/{id} brach ab: " + rootCause(e), e);
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getName() + ": " + cause.getMessage();
    }
}
