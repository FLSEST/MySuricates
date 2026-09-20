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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Was passiert, wenn ein Zeitplan auf einen soft-geloeschten Workflow zeigt?
 *
 * <p>{@code Workflow} traegt {@code @SQLRestriction("deleted_at IS NULL")}, die Assoziation
 * {@code ScheduledWorkflow.workflow} ist {@code EAGER} und hatte lange kein {@code @NotFound}.
 * Hibernate hat die Assoziation dann Zeile fuer Zeile nachgeladen, die Restriction hat die Zeile
 * versteckt und der Ladevorgang ist mit {@code FetchNotFoundException} abgebrochen — die ganze
 * Liste, wegen eines einzigen verwaisten Zeitplans (TODO c9ec9844, gemessen 2026-09-19).
 *
 * <p>{@code ScheduleControllerTest} ist ein {@code @WebMvcTest} mit gemocktem Service und kann das
 * nicht zeigen: Die Frage entsteht erst im echten Persistenzkontext. Deshalb dieser
 * Integrationstest — er haelt das Verhalten fest, damit die Fetch-Strategie der Liste
 * (JOIN FETCH statt Query je Zeile) nicht im Dunkeln geaendert wird.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class ScheduleSoftDeletedWorkflowIntegrationTest {

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
                .description("Schedule " + suffix)
                .inputContext(Map.of())
                .build());

        return workflow;
    }

    /**
     * Das Ist-Verhalten, festgehalten: Ein Zeitplan, dessen Workflow soft-geloescht wurde, darf die
     * Liste nicht zum Absturz bringen. Er bleibt sichtbar — ohne Workflow-Bezug, damit er in der UI
     * ueberhaupt geloescht werden kann.
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

    private static JsonNode scheduleWithDescription(JsonNode all, String description) {
        for (JsonNode node : all) {
            if (description.equals(node.path("description").asText(null))) {
                return node;
            }
        }
        return null;
    }

    /** Auch die Detail-Route eines verwaisten Zeitplans muss antworten statt zu werfen. */
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
