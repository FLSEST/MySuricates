package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code workflowCount} der Projektkarten (TODO 8dabaca8): Die Liste zaehlt mit EINER
 * GROUP-BY-Abfrage, das Einzelprojekt mit einer Zaehlabfrage — beide als JPQL, damit die
 * {@code @SQLRestriction("deleted_at IS NULL")} von {@code Workflow} greift und soft-geloeschte
 * Workflows nicht mitzaehlen. Eine native Query wuerde sie mitzaehlen; dieser Test haelt das fest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class ProjectWorkflowCountIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager entityManager;

    private Project withThree;
    private Project empty;

    @BeforeEach
    void seed() {
        withThree = project("with-three");
        empty = project("empty");
        workflow(withThree, "a");
        workflow(withThree, "b");
        workflow(withThree, "c");
        Workflow deleted = workflow(withThree, "deleted");
        deleted.setDeletedAt(Instant.now());
        workflowRepository.save(deleted);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void listCountsOnlyNonDeletedWorkflows() throws Exception {
        JsonNode all = objectMapper.readTree(mockMvc.perform(get("/api/v1/projects"))
                .andReturn().getResponse().getContentAsString());

        assertThat(countOf(all, withThree.getId())).isEqualTo(3);
        assertThat(countOf(all, empty.getId())).isZero();
    }

    @Test
    void singleProjectCountsOnlyNonDeletedWorkflows() throws Exception {
        JsonNode one = objectMapper.readTree(mockMvc.perform(get("/api/v1/projects/" + withThree.getId()))
                .andReturn().getResponse().getContentAsString());
        JsonNode none = objectMapper.readTree(mockMvc.perform(get("/api/v1/projects/" + empty.getId()))
                .andReturn().getResponse().getContentAsString());

        assertThat(one.get("workflowCount").asLong()).isEqualTo(3);
        assertThat(none.get("workflowCount").asLong()).isZero();
    }

    private Project project(String suffix) {
        return projectRepository.save(Project.builder()
                .name("Workflow Count Project " + suffix)
                .type(ProjectType.CODE)
                .path("/tmp/workflow-count-" + suffix)
                .status(ProjectStatus.STOPPED)
                .build());
    }

    private Workflow workflow(Project project, String suffix) {
        return workflowRepository.save(Workflow.builder()
                .name("Workflow Count " + suffix)
                .description("Seeded for TODO 8dabaca8")
                .project(project)
                .build());
    }

    private static long countOf(JsonNode projects, String id) {
        for (JsonNode p : projects) {
            if (id.equals(p.get("id").asText())) {
                assertThat(p.has("workflowCount")).isTrue();
                return p.get("workflowCount").asLong();
            }
        }
        throw new AssertionError("Project " + id + " missing from /api/v1/projects");
    }
}
