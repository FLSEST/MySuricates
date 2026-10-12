package com.automation.integration;

import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowVersionRepository;
import com.automation.service.chain.ChainService;
import com.automation.service.scheduler.SchedulerService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Project bundle round trip against the real test database: export → preview → import, with path
 * mapping, sub-workflow references, chains, disabled schedules and a rollback. Not {@code @Transactional}
 * on purpose — the import's own transaction must commit or roll back for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class ProjectBundleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository versionRepository;
    @Autowired private WorkflowChainRepository chainRepository;
    @Autowired private ScheduledWorkflowRepository scheduleRepository;
    @Autowired private WorkflowService workflowService;
    @Autowired private ChainService chainService;
    @Autowired private SchedulerService schedulerService;
    @Autowired private JdbcTemplate jdbc;

    @TempDir Path sourceDir;
    @TempDir Path targetDir;

    private final List<String> projectIds = new ArrayList<>();
    private String venv;
    private Project source;
    private Workflow child;
    private Workflow tests;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        venv = "/tmp/bundle-it-venv-" + nano;
        source = project("bundle-src-" + nano, sourceDir.toString());
        child = create(source, "Child", block("shell", Map.of("command", "echo child")));
        tests = create(source, "Tests", block("shell", Map.of("command",
                "cd " + sourceDir + "/src && " + venv + "/bin/python -m pytest; ls " + venv)));
        workflowService.update(tests.getId(), new com.automation.dto.request.UpdateWorkflowRequest(null, null, null,
                null, null, null, null, Map.of("workspace", Map.of("source_path", sourceDir.toString())), null),
                ChangeSource.USER);
        Map<String, Object> nested = Map.of("type", "sub-workflow", "config", Map.of("workflowId", tests.getId()));
        create(source, "Parent", block("sub-workflow", Map.of("workflow_id", child.getId())),
                block("foreach", Map.of("items", List.of("a"), "blocks", List.of(nested))));
        WorkflowChain chain = chainService.create(new CreateChainRequest("Nightly " + nano, null, null, List.of(
                new ChainEntryRequest(tests.getId(), "first", 0, true, Map.of("dir", sourceDir + "/out")),
                new ChainEntryRequest(child.getId(), "second", 1, false, Map.of())), true));
        schedulerService.create(tests.getId(), null, "WORKFLOW", "0 0 6 * * *", "morning", Map.of(), "CRON",
                null, null, null, null, null, null);
        schedulerService.create(null, chain.getId(), "CHAIN", "0 0 2 * * *", null, Map.of(), "CRON",
                null, null, null, 5, null, true);
    }

    @AfterEach
    void tearDown() {
        for (String projectId : projectIds) {
            List<String> workflowIds = jdbc.queryForList("SELECT id FROM workflows WHERE project_id = ?", String.class, projectId);
            for (String id : workflowIds) {
                jdbc.update("DELETE FROM scheduled_workflows WHERE workflow_id = ?", id);
                jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", id);
                jdbc.update("DELETE FROM activity_log WHERE workflow_id = ?", id);
                jdbc.update("DELETE FROM workflow_skills WHERE workflow_id = ?", id);
            }
            chainRepository.findAll().stream()
                    .filter(c -> c.getEntries().stream().anyMatch(e -> workflowIds.contains(e.getWorkflowId())))
                    .forEach(c -> {
                        jdbc.update("DELETE FROM scheduled_workflows WHERE chain_id = ?", c.getId());
                        jdbc.update("DELETE FROM workflow_chains WHERE id = ?", c.getId());
                    });
            jdbc.update("DELETE FROM workflows WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
        }
    }

    @Test
    void roundTripIntoANewProject_withPathMapping() throws Exception {
        ResultActions exported = mockMvc.perform(get("/api/v1/projects/{id}/export", source.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + source.getName() + ".mysuricates-bundle.json\""));
        JsonNode bundle = objectMapper.readTree(exported.andReturn().getResponse().getContentAsString());
        writeExampleIfRequested(bundle);
        assertThat(bundle.toString()).doesNotContain(child.getId(), tests.getId(), source.getId());
        String sourceToken = token(bundle, sourceDir.toString());
        String venvToken = token(bundle, venv);

        JsonNode preview = json(perform(post("/api/v1/projects/import/preview"), Map.of("bundle", bundle)).andExpect(status().isOk()));
        assertThat(preview.at("/workflows").size()).isEqualTo(3);
        assertThat(preview.at("/schedules/0/willBeDisabled").asBoolean()).isTrue();

        String name = "bundle-dst-" + System.nanoTime();
        JsonNode result = json(perform(post("/api/v1/projects/import"), Map.of("bundle", bundle,
                "project", Map.of("mode", "new", "name", name, "path", targetDir.toString()),
                "pathMappings", Map.of(sourceToken, targetDir.toString(), venvToken, "/opt/venvs/qa/"),
                "onConflict", "skip")).andExpect(status().isCreated()));
        String newProjectId = result.get("projectId").asText();
        projectIds.add(newProjectId);

        assertThat(result.at("/created/workflows").asInt()).isEqualTo(3);
        assertThat(result.at("/created/chains").asInt()).isEqualTo(1);
        assertThat(result.at("/created/schedules").asInt()).isEqualTo(2);
        Project imported = projectRepository.findById(newProjectId).orElseThrow();
        assertThat(imported.getName()).isEqualTo(name);
        assertThat(imported.getPath()).isEqualTo(targetDir.toString());

        Map<String, Workflow> byName = new HashMap<>();
        workflowRepository.findByProjectId(newProjectId).forEach(w -> byName.put(w.getName(), w));
        assertThat(byName).containsOnlyKeys("Child", "Parent", "Tests");
        Workflow newChild = byName.get("Child");
        Workflow newTests = byName.get("Tests");
        Workflow newParent = byName.get("Parent");
        assertThat(newChild.getId()).isNotEqualTo(child.getId());
        assertThat(newParent.getBlocks().get(0).getConfig()).containsEntry("workflow_id", newChild.getId());
        assertThat(newParent.getBlocks().get(1).getConfig().toString()).contains("workflowId=" + newTests.getId());
        assertThat(newTests.getBlocks().get(0).getConfig()).containsEntry("command",
                "cd {{ project_path }}/src && /opt/venvs/qa/bin/python -m pytest; ls /opt/venvs/qa");
        assertThat(newTests.getMetadata().toString()).contains("source_path=" + targetDir);
        for (Workflow w : byName.values()) {
            assertThat(w.isSealed()).isFalse();
            assertThat(versionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(w.getId()).orElseThrow()
                    .getChangeSource()).isEqualTo(ChangeSource.IMPORT.key());
        }
        assertThat(versionRepository.findByWorkflowIdOrderByVersionNumberDesc(newParent.getId(), Pageable.ofSize(5))
                .getContent()).hasSize(2); // created, then references resolved

        WorkflowChain newChain = chainRepository.findAll().stream()
                .filter(c -> c.getEntries().stream().anyMatch(e -> e.getWorkflowId().equals(newTests.getId())))
                .findFirst().orElseThrow();
        assertThat(newChain.getEntries()).extracting(e -> e.getWorkflowId()).containsExactly(newTests.getId(), newChild.getId());
        assertThat(newChain.getEntries().get(0).getInputContext()).containsEntry("dir", targetDir + "/out");
        List<ScheduledWorkflow> schedules = new ArrayList<>(scheduleRepository.findByWorkflowId(newTests.getId()));
        schedules.addAll(scheduleRepository.findByChainId(newChain.getId()));
        assertThat(schedules).hasSize(2).allSatisfy(s -> {
            assertThat(s.isEnabled()).isFalse();
            assertThat(s.getNextRunAt()).isNull();
        });
        assertThat(schedules).anySatisfy(s -> assertThat(s.getMaxRuns()).isEqualTo(5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_log WHERE reference_id = ? AND message LIKE ?",
                Integer.class, newProjectId, "Project imported from bundle%")).isEqualTo(1);
    }

    @Test
    void missingPathMappingIs400_andNothingIsCreated() throws Exception {
        JsonNode bundle = export();
        String name = "bundle-missing-" + System.nanoTime();

        JsonNode error = json(perform(post("/api/v1/projects/import"), Map.of("bundle", bundle,
                "project", Map.of("mode", "new", "name", name), "pathMappings", Map.of()))
                .andExpect(status().isBadRequest()));

        assertThat(error.get("error").asText()).contains("Missing path mapping").contains(venv);
        assertThat(projectRepository.findFirstByName(name)).isEmpty();
    }

    @Test
    void intoAnExistingProject_skipReusesTheWorkflow_renameCreatesACopy() throws Exception {
        JsonNode bundle = export();
        Project target = project("bundle-existing-" + System.nanoTime(), targetDir.toString());
        Workflow existingChild = create(target, "Child", block("shell", Map.of("command", "echo mine")));
        Map<String, Object> mappings = mappings(bundle);

        JsonNode skipped = json(perform(post("/api/v1/projects/import"), Map.of("bundle", bundle,
                "project", Map.of("mode", "existing", "projectId", target.getId()),
                "pathMappings", mappings, "onConflict", "skip")).andExpect(status().isCreated()));
        assertThat(skipped.at("/created/workflows").asInt()).isEqualTo(2);
        assertThat(skipped.at("/skipped/0/key").asText()).isEqualTo("child");
        assertThat(skipped.at("/skipped/0/reason").asText()).isEqualTo("name exists");
        Workflow parent = workflowRepository.findByNameAndProjectId("Parent", target.getId()).orElseThrow();
        assertThat(parent.getBlocks().get(0).getConfig()).containsEntry("workflow_id", existingChild.getId());

        JsonNode renamed = json(perform(post("/api/v1/projects/import"), Map.of("bundle", bundle,
                "project", Map.of("mode", "existing", "projectId", target.getId()),
                "pathMappings", mappings, "onConflict", "rename")).andExpect(status().isCreated()));
        assertThat(renamed.at("/created/workflows").asInt()).isEqualTo(3);
        assertThat(renamed.at("/renamed").findValuesAsText("to"))
                .containsExactlyInAnyOrder("Child (Import)", "Parent (Import)", "Tests (Import)");
        Workflow copy = workflowRepository.findByNameAndProjectId("Child (Import)", target.getId()).orElseThrow();
        Workflow parentCopy = workflowRepository.findByNameAndProjectId("Parent (Import)", target.getId()).orElseThrow();
        assertThat(parentCopy.getBlocks().get(0).getConfig()).containsEntry("workflow_id", copy.getId());
    }

    @Test
    void failureMidImportRollsEverythingBack() throws Exception {
        JsonNode bundle = export();
        // Workflows come sorted by name: Child and Parent are written before Tests breaks.
        ObjectNode brokenBlock = (ObjectNode) bundle.at("/workflows/2/definition/blocks/0");
        assertThat(bundle.at("/workflows/2/key").asText()).isEqualTo("tests");
        brokenBlock.put("type", "no-such-block-type");
        String name = "bundle-rollback-" + System.nanoTime();
        long workflowsBefore = workflowRepository.count();

        json(perform(post("/api/v1/projects/import"), Map.of("bundle", bundle,
                "project", Map.of("mode", "new", "name", name), "pathMappings", mappings(bundle)))
                .andExpect(status().is4xxClientError()));

        assertThat(projectRepository.findFirstByName(name)).isEmpty();
        assertThat(workflowRepository.count()).isEqualTo(workflowsBefore);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Project project(String name, String path) {
        Project p = projectRepository.save(Project.builder().name(name).type(ProjectType.CODE).path(path)
                .status(ProjectStatus.STOPPED).build());
        projectIds.add(p.getId());
        return p;
    }

    private Workflow create(Project project, String name, BlockDefinition... blocks) {
        return workflowService.create(new CreateWorkflowRequest(project.getId(), name, null, null, List.of(blocks),
                List.of(), null, null, null, null, null, null, null), ChangeSource.USER);
    }

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).config(new HashMap<>(config)).build();
    }

    private JsonNode export() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/v1/projects/{id}/export", source.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String token(JsonNode bundle, String original) {
        for (JsonNode p : bundle.get("paths")) {
            if (p.get("original").asText().equals(original)) {
                return p.get("token").asText();
            }
        }
        throw new AssertionError("no token for " + original + " in " + bundle.get("paths"));
    }

    private Map<String, Object> mappings(JsonNode bundle) {
        Map<String, Object> m = new HashMap<>();
        bundle.get("paths").forEach(p -> m.put(p.get("token").asText(), targetDir.toString()));
        return m;
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Opt-in (-Dbundle.example.out=file): keeps an exported bundle for the docs. */
    private void writeExampleIfRequested(JsonNode bundle) throws Exception {
        String out = System.getProperty("bundle.example.out");
        if (out != null) {
            Files.writeString(Path.of(out), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle));
        }
    }
}
