package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Blueprint stage 2 (TODO 093d2f11) against the seeded blueprints and the real schema: the
 * sub-workflow package creates parent and part and wires the part's id, the learning chain installs
 * two blueprints in a row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class BlueprintPackageInstallIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;

    private Project project;

    @BeforeEach
    void setUp() {
        project = projectRepository.save(Project.builder()
                .name("Blueprint-Package-" + System.nanoTime()).type(ProjectType.CODE)
                .path("/tmp/blueprint-package-" + System.nanoTime()).status(ProjectStatus.STOPPED).build());
    }

    @Test
    void subWorkflowPackage_createsParentAndPart_andWiresThePartId() throws Exception {
        JsonNode answer = install("/api/v1/workflow-templates/blueprint-sub-workflow/install",
                Map.of("projectId", project.getId(), "locale", "de"));

        String parentId = answer.path("workflowId").asText();
        String partId = answer.path("partWorkflowIds").path(0).asText();
        assertThat(answer.path("partWorkflowIds")).hasSize(1);

        Workflow part = workflowRepository.findById(partId).orElseThrow();
        assertThat(part.getName()).isEqualTo("Teil-Workflow: Eingabe prüfen");
        assertThat(part.getTemplateId()).isEqualTo("blueprint-sub-workflow-part");
        assertThat(part.getProject().getId()).isEqualTo(project.getId());

        Workflow parent = workflowRepository.findById(parentId).orElseThrow();
        assertThat(parent.getName()).isEqualTo("Teil-Workflow aufrufen (Paket)");
        assertThat(parent.getBlocks()).filteredOn(b -> "sub-workflow".equals(b.getType()))
                .singleElement()
                .satisfies(b -> assertThat(b.getConfig()).containsEntry("workflow_id", partId));
    }

    @Test
    void learningChain_installsTwoBlueprintsInARow() throws Exception {
        JsonNode answer = install("/api/v1/chain-templates/blueprint-chain/install",
                Map.of("projectId", project.getId(), "locale", "en"));

        assertThat(answer.path("workflowIds")).hasSize(2);
        Workflow second = workflowRepository.findById(answer.path("workflowIds").path(1).asText()).orElseThrow();
        assertThat(second.getTemplateId()).isEqualTo("blueprint-chain-step");
        assertThat(second.getBlocks().getFirst().getConfig().get("command").toString())
                .contains("{{ previous_chain_output }}");
    }

    private JsonNode install(String path, Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }
}
