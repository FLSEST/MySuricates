package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Definition sync of the staged setup end to end (TODO 84a78bb7): Dev on the real stack (SQLite,
 * pool-size 1) pushes to a fake Test and a fake Prod — one JDK {@link HttpServer}, told apart by the
 * path prefix of their configured URLs. Each target's key comes from its key file; Prod receives
 * only the sealed workflow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "automation.hardening.sealing=true")
class StagedSyncIntegrationTest {

    /** method, path and X-API-Key of every request the fake targets received. */
    record Call(String method, String path, String apiKey, String body) {
    }

    private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = startFakeTargets();
    private static final Path KEYS = tempDir();
    private static final Path TEST_KEY = KEYS.resolve("test.key");
    private static final Path PROD_KEY = KEYS.resolve("prod.key");

    @DynamicPropertySource
    static void targets(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("automation.staging.targets.test.url", () -> base + "/test");
        registry.add("automation.staging.targets.test.key-file", TEST_KEY::toString);
        // Trailing /api/v1 is accepted as well
        registry.add("automation.staging.targets.prod.url", () -> base + "/prod/api/v1");
        registry.add("automation.staging.targets.prod.key-file", PROD_KEY::toString);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;
    private String sealedId;
    private String sealedName;
    private String unsealedId;
    private String unsealedName;
    private final List<String> extraIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(TEST_KEY, "test-key-from-file\n");
        Files.writeString(PROD_KEY, "prod-key-from-file");
        long n = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("Staged sync " + n).type(ProjectType.CODE)
                .path("/tmp/staged-sync-" + n).status(ProjectStatus.STOPPED).build());
        projectId = project.getId();
        sealedName = "Sealed " + n;
        unsealedName = "Unsealed " + n;
        sealedId = workflowRepository.save(Workflow.builder()
                .name(sealedName).project(project).blocks(new ArrayList<>()).build()).getId();
        unsealedId = workflowRepository.save(Workflow.builder()
                .name(unsealedName).project(project).blocks(new ArrayList<>()).build()).getId();
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", sealedId)).andExpect(status().isOk());
        CALLS.clear();
    }

    @AfterEach
    void tearDown() {
        List<String> ids = new ArrayList<>(extraIds);
        ids.add(sealedId);
        ids.add(unsealedId);
        for (String id : ids) {
            jdbc.update("DELETE FROM workflow_seal_events WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM activity_log WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM workflows WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @AfterAll
    static void stopFakeTargets() {
        SERVER.stop(0);
    }

    @Test
    void listsBothTargetsWithKeyHealthAndSealRule() throws Exception {
        mockMvc.perform(get("/api/v1/staging/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("test"))
                .andExpect(jsonPath("$[0].keyPresent").value(true))
                .andExpect(jsonPath("$[0].reachable").value(true))
                .andExpect(jsonPath("$[0].sealedOnly").value(false))
                .andExpect(jsonPath("$[0].version").value("9.9.9"))
                .andExpect(jsonPath("$[1].name").value("prod"))
                .andExpect(jsonPath("$[1].sealedOnly").value(true));
    }

    @Test
    void testTargetGetsTheSelectedWorkflowWithTheKeyFromItsFile() throws Exception {
        mockMvc.perform(post("/api/v1/staging/targets/test/sync-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowIds\":[\"" + unsealedId + "\"],\"projectIds\":[],\"skillIds\":[],\"chainIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(1))
                .andExpect(jsonPath("$.preSyncBackup").value("pre-sync.db"));

        assertThat(imported("/test")).containsExactly(unsealedName);
        assertThat(CALLS).allSatisfy(c -> {
            assertThat(c.path()).startsWith("/test/api/v1/");
            if (!c.path().endsWith("/system/health") && !c.path().endsWith("/system/info")) {
                assertThat(c.apiKey()).isEqualTo("test-key-from-file");
            }
        });
    }

    @Test
    void prodGetsOnlySealedWorkflowsEvenWhenAskedForEverything() throws Exception {
        mockMvc.perform(post("/api/v1/staging/targets/prod/sync-definitions"))
                .andExpect(status().isOk());

        List<String> imported = imported("/prod");
        assertThat(imported).contains(sealedName).doesNotContain(unsealedName);
        assertThat(CALLS).filteredOn(c -> c.method().equals("POST"))
                .allSatisfy(c -> assertThat(c.apiKey()).isEqualTo("prod-key-from-file"));
    }

    @Test
    void prodSelectionOfAnUnsealedWorkflowSendsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/staging/targets/prod/sync-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowIds\":[\"" + unsealedId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(0));

        assertThat(imported("/prod")).isEmpty();
    }

    @Test
    void prodPreviewListsOnlySealedWorkflows() throws Exception {
        mockMvc.perform(get("/api/v1/staging/targets/prod/sync-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows[*].id").value(hasItem(sealedId)))
                .andExpect(jsonPath("$.workflows[*].id").value(not(hasItem(unsealedId))));

        mockMvc.perform(get("/api/v1/staging/targets/test/sync-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows[*].id").value(hasItem(unsealedId)));
    }

    @Test
    void prodNamesTheUnsealedSubWorkflowThatStaysBehind() throws Exception {
        Workflow caller = workflowRepository.save(Workflow.builder()
                .name("Caller " + System.nanoTime()).project(projectRepository.findById(projectId).orElseThrow())
                .blocks(new ArrayList<>(List.of(com.automation.entity.BlockDefinition.builder().name("call").type("sub-workflow")
                        .config(new java.util.HashMap<>(java.util.Map.of("workflow_id", unsealedId))).build())))
                .build());
        extraIds.add(caller.getId());
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", caller.getId())).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/staging/targets/prod/sync-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[*]").value(hasItem(org.hamcrest.Matchers.containsString("'" + unsealedName + "'"))));
        mockMvc.perform(post("/api/v1/staging/targets/prod/sync-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowIds\":[\"" + caller.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(1))
                .andExpect(jsonPath("$.notes[0]").value(org.hamcrest.Matchers.containsString("'" + unsealedName + "', which is not sealed")));
        assertThat(imported("/prod")).containsExactly(caller.getName());
    }

    @Test
    void missingKeyFileIsRefusedBeforeAnythingIsSent() throws Exception {
        Files.delete(PROD_KEY);

        mockMvc.perform(post("/api/v1/staging/targets/prod/sync-definitions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("SYNC_TARGET_KEY_MISSING"))
                .andExpect(jsonPath("$.params.target").value("prod"));
        assertThat(CALLS).isEmpty();

        mockMvc.perform(get("/api/v1/staging/targets"))
                .andExpect(jsonPath("$[1].keyPresent").value(false));
        // Without its key no X-API-Key goes out at all — this instance's own key never leaves it
        assertThat(CALLS).filteredOn(c -> c.path().startsWith("/prod/")).allSatisfy(c -> assertThat(c.apiKey()).isNull());
    }

    @Test
    void unknownTargetIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/staging/targets/stable/sync-definitions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("SYNC_TARGET_NOT_CONFIGURED"));
    }

    // ── Fake targets ───────────────────────────────────────

    private static List<String> imported(String prefix) {
        return CALLS.stream()
                .filter(c -> c.method().equals("POST") && c.path().equals(prefix + "/api/v1/workflows/import"))
                .map(c -> c.body().replaceAll("(?s)^.*?\"name\":\"([^\"]*)\".*$", "$1"))
                .toList();
    }

    private static HttpServer startFakeTargets() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                CALLS.add(new Call(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("X-API-Key"), body));
                String api = path.replaceFirst("^/(test|prod)/api/v1", "");
                String out = switch (api) {
                    case "/system/health" -> "{\"status\":\"UP\"}";
                    case "/system/info" -> "{\"version\":\"9.9.9\"}";
                    case "/projects", "/workflows", "/skills", "/chains" ->
                            ex.getRequestMethod().equals("GET") ? "[]" : "{\"id\":\"remote-" + CALLS.size() + "\"}";
                    case "/system/backup" -> "{\"filename\":\"pre-sync.db\"}";
                    default -> "{\"id\":\"remote-" + CALLS.size() + "\"}";
                };
                byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("staged-sync-keys");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
