package com.automation.service.pool;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.automation.config.WorkflowPoolConfig;
import com.automation.dto.request.ExportToPoolRequest;
import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.response.ExportToPoolResponse;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowPoolServiceTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private ProjectRepository projectRepository;

    private WorkflowPoolConfig config;
    private ObjectMapper objectMapper;
    private WorkflowPoolService poolService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config = new WorkflowPoolConfig();
        objectMapper = new ObjectMapper();
        config.setDirectory(tempDir.toString());
        poolService = new WorkflowPoolService(config, objectMapper, workflowService, projectRepository,
                new PoolExportWriter(objectMapper));
    }

    @Test
    void listAll_emptyDirectory_returnsEmptyList() {
        List<PoolWorkflowEntry> result = poolService.listAll();
        assertThat(result).isEmpty();
    }

    @Test
    void listAll_nonExistentDirectory_returnsEmptyList() {
        config.setDirectory(tempDir.resolve("nonexistent").toString());
        poolService = new WorkflowPoolService(config, objectMapper, workflowService, projectRepository,
                new PoolExportWriter(objectMapper));

        List<PoolWorkflowEntry> result = poolService.listAll();
        assertThat(result).isEmpty();
    }

    @Test
    void listAll_disabledPool_returnsEmptyList() throws Exception {
        writePoolFile("test.json", """
                {"name": {"en": "Test"}, "blocks": [{"type": "simple", "config": {"command": "test"}}]}
                """);
        config.setEnabled(false);
        poolService = new WorkflowPoolService(config, objectMapper, workflowService, projectRepository,
                new PoolExportWriter(objectMapper));

        List<PoolWorkflowEntry> result = poolService.listAll();
        assertThat(result).isEmpty();
    }

    @Test
    void listAll_validFiles_returnsParsedEntries() throws Exception {
        writePoolFile("code-review.json", """
                {
                  "name": {"en": "Code Review", "de": "Code-Review"},
                  "description": {"en": "Review code"},
                  "icon": "🔍",
                  "category": "CODE",
                  "tags": ["review"],
                  "blocks": [{"type": "simple", "config": {"command": "review"}}],
                  "instructions": "Be thorough",
                  "model": "claude-sonnet-4-6",
                  "version": "1.0.0",
                  "author": "Test"
                }
                """);

        List<PoolWorkflowEntry> result = poolService.listAll();

        assertThat(result).hasSize(1);
        PoolWorkflowEntry entry = result.get(0);
        assertThat(entry.id()).isEqualTo("code-review");
        assertThat(entry.name()).containsEntry("en", "Code Review");
        assertThat(entry.name()).containsEntry("de", "Code-Review");
        assertThat(entry.icon()).isEqualTo("🔍");
        assertThat(entry.category()).isEqualTo("CODE");
        assertThat(entry.tags()).containsExactly("review");
        assertThat(entry.blocks()).hasSize(1);
        assertThat(entry.instructions()).isEqualTo("Be thorough");
        assertThat(entry.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(entry.version()).isEqualTo("1.0.0");
        assertThat(entry.author()).isEqualTo("Test");
        assertThat(entry.filename()).isEqualTo("code-review.json");
    }

    @Test
    void listAll_skipsInvalidFiles() throws Exception {
        writePoolFile("valid.json", """
                {"name": {"en": "Valid"}, "blocks": [{"type": "simple", "config": {}}]}
                """);
        writePoolFile("invalid.json", "not json at all");
        writePoolFile("missing-name.json", """
                {"blocks": [{"type": "simple", "config": {}}]}
                """);
        writePoolFile("missing-blocks.json", """
                {"name": {"en": "No blocks"}}
                """);

        List<PoolWorkflowEntry> result = poolService.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("valid");
    }

    /** A plain workflow export in the pool (string name) is skipped, the rest still lists. */
    @Test
    void listAll_skipsFileWithWrongFieldShape_keepsTheOthers() throws Exception {
        writePoolFile("valid.json", """
                {"name": {"en": "Valid"}, "blocks": [{"type": "simple", "config": {}}]}
                """);
        writePoolFile("export.json", """
                {"name": "Tech-Radar", "description": "plain", "blocks": [{"type": "shell", "config": {}}]}
                """);

        assertThat(poolService.listAll()).extracting(PoolWorkflowEntry::id).containsExactly("valid");
    }

    /** A content error is one WARN line naming file and fields; a stack trace would only bury it. */
    @Test
    void listAll_wrongFieldShape_logsOneLineWithFileAndFields_withoutStackTrace() throws Exception {
        writePoolFile("export.json", """
                {"name": "Tech-Radar", "description": "plain", "blocks": [{"type": "shell", "config": {}}]}
                """);

        List<ILoggingEvent> events = captureLogs(() -> poolService.listAll());

        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .startsWith("Skipping pool workflow file: Pool file export.json: field 'name' must be an object")
                .contains("field 'description' must be an object")
                .doesNotContain("\n");
        assertThat(event.getThrowableProxy()).as("stack trace").isNull();
    }

    /** Broken JSON is not a known shape problem: it keeps its exception for the reader. */
    @Test
    void listAll_brokenJson_logsTheExceptionWithIt() throws Exception {
        writePoolFile("broken.json", "{\"name\": ");

        List<ILoggingEvent> events = captureLogs(() -> poolService.listAll());

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Failed to parse pool workflow file: broken.json");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void listAll_ignoresNonJsonFiles() throws Exception {
        writePoolFile("valid.json", """
                {"name": {"en": "Valid"}, "blocks": [{"type": "simple", "config": {}}]}
                """);
        Files.writeString(tempDir.resolve("readme.txt"), "not a pool file");

        List<PoolWorkflowEntry> result = poolService.listAll();
        assertThat(result).hasSize(1);
    }

    @Test
    void listAll_defaultsForOptionalFields() throws Exception {
        writePoolFile("minimal.json", """
                {"name": {"en": "Minimal"}, "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
                """);

        List<PoolWorkflowEntry> result = poolService.listAll();

        assertThat(result).hasSize(1);
        PoolWorkflowEntry entry = result.get(0);
        assertThat(entry.icon()).isEqualTo("📋");
        assertThat(entry.category()).isNull();
        assertThat(entry.tags()).isEmpty();
        assertThat(entry.instructions()).isNull();
        assertThat(entry.model()).isNull();
        assertThat(entry.version()).isEqualTo("1.0.0");
        assertThat(entry.author()).isNull();
        assertThat(entry.defaultContext()).isNull();
    }

    @Test
    void findById_existingEntry_returnsEntry() throws Exception {
        writePoolFile("my-workflow.json", """
                {"name": {"en": "My Workflow"}, "blocks": [{"type": "simple", "config": {}}]}
                """);

        PoolWorkflowEntry result = poolService.findById("my-workflow");
        assertThat(result.id()).isEqualTo("my-workflow");
        assertThat(result.name()).containsEntry("en", "My Workflow");
    }

    @Test
    void findById_nonExistent_throwsResourceNotFoundException() {
        assertThatThrownBy(() -> poolService.findById("nonexistent"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void install_createsWorkflowViaService() throws Exception {
        writePoolFile("test-flow.json", """
                {
                  "name": {"en": "Test Flow", "de": "Test-Ablauf"},
                  "description": {"en": "A test"},
                  "icon": "🧪",
                  "blocks": [{"type": "simple", "config": {"command": "test"}}],
                  "instructions": "Test instructions",
                  "model": "claude-sonnet-4-6",
                  "defaultContext": {"key": "value"}
                }
                """);

        Workflow mockWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Flow")
                .build();
        when(workflowService.create(any(), eq(ChangeSource.POOL_INSTALL))).thenReturn(mockWorkflow);

        InstallPoolWorkflowRequest request = new InstallPoolWorkflowRequest("proj-1", null, null);
        WorkflowPoolService.InstallResult result = poolService.install("test-flow", request);

        assertThat(result.workflow().getId()).isEqualTo("wf-1");
        verify(workflowService).create(any(), eq(ChangeSource.POOL_INSTALL));
    }

    @Test
    void install_withNameOverride_usesOverrideName() throws Exception {
        writePoolFile("test-flow.json", """
                {"name": {"en": "Original"}, "blocks": [{"type": "simple", "config": {}}]}
                """);

        Workflow mockWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Custom Name")
                .build();
        when(workflowService.create(any(), eq(ChangeSource.POOL_INSTALL))).thenReturn(mockWorkflow);

        InstallPoolWorkflowRequest request = new InstallPoolWorkflowRequest("proj-1", "Custom Name", null);
        poolService.install("test-flow", request);

        verify(workflowService).create(argThat(req -> req.name().equals("Custom Name")), eq(ChangeSource.POOL_INSTALL));
    }

    @Test
    void install_nonExistentPool_throwsResourceNotFoundException() {
        InstallPoolWorkflowRequest request = new InstallPoolWorkflowRequest("proj-1", null, null);
        assertThatThrownBy(() -> poolService.install("nonexistent", request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── scope (Pool-Metadatum) ──────────────────────────────────────────────

    @Test
    void listAll_readsScopeFromFile() throws Exception {
        writePoolFile("repo-bound.json", """
                {"name": {"en": "Repo bound"}, "scope": "repo",
                 "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
                """);

        assertThat(poolService.listAll().get(0).scope()).isEqualTo("repo");
    }

    @Test
    void listAll_scopeIsNullWhenNotDeclared() throws Exception {
        writePoolFile("undeclared.json", """
                {"name": {"en": "Undeclared"}, "blocks": [{"type": "shell", "config": {}}]}
                """);

        assertThat(poolService.listAll().get(0).scope()).isNull();
    }

    // ── export ──────────────────────────────────────────────────────────────

    @Test
    void exportToPool_writesScopeFromParameter() throws Exception {
        stubWorkflow(Workflow.builder().id("wf-1").name("Build Gate").build());

        poolService.exportToPool("wf-1", null, "repo");

        assertThat(exported("build-gate.json")).containsEntry("scope", "repo");
    }

    @Test
    void exportToPool_defaultsScopeToPortable() throws Exception {
        stubWorkflow(Workflow.builder().id("wf-1").name("Build Gate").build());

        poolService.exportToPool("wf-1", null, null);

        assertThat(exported("build-gate.json")).containsEntry("scope", "portable");
    }

    @Test
    void exportToPool_rejectsUnknownScope() {
        assertThatThrownBy(() -> poolService.exportToPool("wf-1", null, "private"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exportToPool_omitsProjectIdAndInstanceUuids() throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", "11111111-2222-3333-4444-555555555555");
        metadata.put("sourceRunId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        metadata.put("gateCategory", "quality");
        Map<String, Object> defaultContext = new LinkedHashMap<>();
        defaultContext.put("project_path", "{{ project_path }}");
        defaultContext.put("workflowId", "99999999-8888-7777-6666-555555555555");
        stubWorkflow(Workflow.builder()
                .id("wf-1").name("Build Gate")
                .metadata(metadata)
                .defaultContext(defaultContext)
                .blocks(List.of(BlockDefinition.builder()
                        .type("shell")
                        .config(new LinkedHashMap<>(Map.of(
                                "command", "echo run 12345678-1234-1234-1234-123456789abc")))
                        .build()))
                .build());

        poolService.exportToPool("wf-1", null, "portable");

        String raw = Files.readString(tempDir.resolve("build-gate.json"));
        assertThat(raw).doesNotContain("projectId");
        assertThat(raw).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        Map<String, Object> json = exported("build-gate.json");
        assertThat(json).doesNotContainKey("projectId");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) json.get("metadata");
        assertThat(meta).containsEntry("gateCategory", "quality").doesNotContainKey("sourceRunId");
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) json.get("defaultContext");
        assertThat(ctx).containsEntry("project_path", "{{ project_path }}").doesNotContainKey("workflowId");
    }

    @Test
    void exportToPool_reportsSanitizedPathsInTheResponse() throws Exception {
        // sub-workflow trägt die Referenz auf einen anderen Workflow in der Config. Sie ist
        // instanzgebunden und fällt weg — der Aufrufer muss erfahren, dass der Eintrag so
        // nicht installierbar ist.
        stubWorkflow(Workflow.builder()
                .id("wf-1").name("Build Gate")
                .blocks(List.of(BlockDefinition.builder()
                        .type("sub-workflow")
                        .config(new LinkedHashMap<>(Map.of(
                                "workflowId", "77777777-6666-5555-4444-333333333333")))
                        .build()))
                .build());

        ExportToPoolResponse response = poolService.exportToPool("wf-1", null, "portable");

        assertThat(response.sanitizedPaths()).containsExactly("blocks[0].config.workflowId");
    }

    @Test
    void exportToPool_reportsNothingForACleanWorkflow() throws Exception {
        stubWorkflow(Workflow.builder().id("wf-1").name("Build Gate").build());

        assertThat(poolService.exportToPool("wf-1", null, "portable").sanitizedPaths()).isEmpty();
    }

    @Test
    void exportToPool_keepsFunctionalFields() throws Exception {
        stubWorkflow(Workflow.builder()
                .id("wf-1").name("Build Gate").description("Runs the build")
                .icon("🚦").version("2.1.0").instructions("Be brief").model("sonnet")
                .blocks(List.of(BlockDefinition.builder().type("shell")
                        .config(new LinkedHashMap<>(Map.of("command", "ls"))).build()))
                .build());

        poolService.exportToPool("wf-1",
                new ExportToPoolRequest(null, null, null, null, "QUALITY", List.of("ci"), "MySuricates"),
                "repo");

        Map<String, Object> json = exported("build-gate.json");
        assertThat(json).containsEntry("category", "QUALITY")
                .containsEntry("author", "MySuricates")
                .containsEntry("version", "2.1.0")
                .containsEntry("instructions", "Be brief")
                .containsEntry("model", "sonnet")
                .containsEntry("tags", List.of("ci"));
    }

    /**
     * TODO 96640e6f: getrackte Pool-Dateien nutzen {@code effort} — ohne das Feld im Export ginge
     * die Einstellung beim Roundtrip Export → Install verloren.
     */
    @Test
    void exportToPool_writesEffort() throws Exception {
        stubWorkflow(Workflow.builder().id("wf-1").name("Build Gate").effort("high").build());

        poolService.exportToPool("wf-1", null, "repo");

        assertThat(exported("build-gate.json")).containsEntry("effort", "high");
    }

    @Test
    void exportToPool_withoutEffort_omitsTheField() throws Exception {
        stubWorkflow(Workflow.builder().id("wf-1").name("Build Gate").build());

        poolService.exportToPool("wf-1", null, "repo");

        assertThat(exported("build-gate.json")).doesNotContainKey("effort");
    }

    @Test
    void listAll_readsEffortFromFile() throws Exception {
        writePoolFile("test-flow.json", """
                {"name": {"en": "Test"}, "blocks": [{"type": "simple", "config": {}}], "effort": "xhigh"}
                """);

        assertThat(poolService.listAll().get(0).effort()).isEqualTo("xhigh");
    }

    @Test
    void install_passesEffortToTheCreatedWorkflow() throws Exception {
        writePoolFile("test-flow.json", """
                {"name": {"en": "Test"}, "blocks": [{"type": "simple", "config": {}}], "effort": "high"}
                """);
        when(workflowService.create(any(), eq(ChangeSource.POOL_INSTALL)))
                .thenReturn(Workflow.builder().id("wf-1").name("Test").build());

        poolService.install("test-flow", new InstallPoolWorkflowRequest("proj-1", null, null));

        verify(workflowService).create(argThat(req -> "high".equals(req.effort())), eq(ChangeSource.POOL_INSTALL));
    }

    /**
     * TODO 96640e6f: {@code UpdateWorkflowRequest} nimmt 14 Positionsargumente, {@code category},
     * {@code effort} und {@code version} stehen nebeneinander und sind alle String — eine Umsortierung
     * des Records wuerde effort sonst still als category einsetzen, ohne dass ein Test es merkt.
     */
    @Test
    void install_onAnExistingWorkflow_passesEffortToTheUpdate() throws Exception {
        writePoolFile("test-flow.json", """
                {"name": {"en": "Test"}, "blocks": [{"type": "simple", "config": {}}], "effort": "high"}
                """);
        Workflow existing = Workflow.builder().id("wf-9").name("Test").build();
        when(workflowService.findByNameAndProjectId("Test", "proj-1")).thenReturn(Optional.of(existing));
        when(workflowService.update(eq("wf-9"), any(), eq(ChangeSource.POOL_INSTALL))).thenReturn(existing);

        WorkflowPoolService.InstallResult result =
                poolService.install("test-flow", new InstallPoolWorkflowRequest("proj-1", null, null));

        assertThat(result.created()).isFalse();
        verify(workflowService).update(eq("wf-9"),
                argThat(req -> "high".equals(req.effort()) && req.category() == null),
                eq(ChangeSource.POOL_INSTALL));
    }

    /**
     * TODO a3b89355: Die Neuinstallation laeuft ueber update und verlor die Pool-Version — das Audit
     * Center zeigte v3.0.0 fuer eine 4.4.0-Pooldatei, und ein Export haette 3.0.0 zurueckgeschrieben.
     */
    @Test
    void install_onAnExistingWorkflow_passesThePoolVersionToTheUpdate() throws Exception {
        writePoolFile("test-flow.json", """
                {"name": {"en": "Test"}, "blocks": [{"type": "simple", "config": {}}],
                 "effort": "high", "version": "4.4.0"}
                """);
        Workflow existing = Workflow.builder().id("wf-9").name("Test").version("3.0.0").build();
        when(workflowService.findByNameAndProjectId("Test", "proj-1")).thenReturn(Optional.of(existing));
        when(workflowService.update(eq("wf-9"), any(), eq(ChangeSource.POOL_INSTALL))).thenReturn(existing);

        poolService.install("test-flow", new InstallPoolWorkflowRequest("proj-1", null, null));

        verify(workflowService).update(eq("wf-9"),
                argThat(req -> "4.4.0".equals(req.version()) && "high".equals(req.effort()) && req.category() == null),
                eq(ChangeSource.POOL_INSTALL));
    }

    /** Eine leere Version in der Pooldatei laesst das Label stehen, wie create seinen Default behaelt. */
    @Test
    void install_onAnExistingWorkflow_blankPoolVersionKeepsTheLabel() throws Exception {
        writePoolFile("test-flow.json", """
                {"name": {"en": "Test"}, "blocks": [{"type": "simple", "config": {}}], "version": " "}
                """);
        Workflow existing = Workflow.builder().id("wf-9").name("Test").version("3.0.0").build();
        when(workflowService.findByNameAndProjectId("Test", "proj-1")).thenReturn(Optional.of(existing));
        when(workflowService.update(eq("wf-9"), any(), eq(ChangeSource.POOL_INSTALL))).thenReturn(existing);

        poolService.install("test-flow", new InstallPoolWorkflowRequest("proj-1", null, null));

        verify(workflowService).update(eq("wf-9"), argThat(req -> req.version() == null), eq(ChangeSource.POOL_INSTALL));
    }

    // --- zweisprachige Pool-Dateien (TODO a129a661) ---

    @Test
    void listAll_bilingualFile_resolvesStepsToTheLocale_keepsNameMaps() throws Exception {
        writePoolFile("approval.json", """
                {"i18n": ["de", "en"],
                 "name": {"de": "Freigabe-Beispiel", "en": "Approval example"},
                 "description": {"de": "Zeigt eine Freigabe", "en": "Shows an approval"},
                 "instructions": {"de": "Deutsch", "en": "English"},
                 "blocks": [
                   {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
                   {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
                    "config": {"question": {"de": "{{ block.messen.output }} weiter?", "en": "{{ block.measure.output }} continue?"},
                               "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}]}}]}
                """);

        PoolWorkflowEntry de = poolService.findById("approval", "de");
        PoolWorkflowEntry en = poolService.findById("approval");

        assertThat(de.blocks()).extracting(BlockDefinition::getName).containsExactly("messen", "freigabe");
        assertThat(de.blocks().get(1).getConfig().get("question")).isEqualTo("{{ block.messen.output }} weiter?");
        assertThat(de.instructions()).isEqualTo("Deutsch");
        assertThat(en.blocks()).extracting(BlockDefinition::getName).containsExactly("measure", "approval");
        assertThat(en.blocks().get(1).getConfig().get("options")).isEqualTo(List.of("Yes", "No"));
        assertThat(de.name()).containsEntry("de", "Freigabe-Beispiel").containsEntry("en", "Approval example");
    }

    @Test
    void listAll_fileWithoutMarker_keepsDeEnDataMapsUntouched() throws Exception {
        writePoolFile("plain.json", """
                {"name": {"en": "Plain"}, "blocks": [{"type": "shell", "config": {"labels": {"de": "A", "en": "B"}}}]}
                """);

        PoolWorkflowEntry entry = poolService.findById("plain", "de");

        assertThat(entry.blocks().get(0).getConfig().get("labels")).isEqualTo(Map.of("de", "A", "en", "B"));
    }

    @Test
    void install_inGerman_createsTheGermanWorkflow() throws Exception {
        writePoolFile("approval.json", """
                {"i18n": ["de", "en"],
                 "name": {"de": "Freigabe-Beispiel", "en": "Approval example"},
                 "description": {"de": "Zeigt eine Freigabe", "en": "Shows an approval"},
                 "instructions": {"de": "Deutsch", "en": "English"},
                 "blocks": [
                   {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
                   {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
                    "config": {"question": {"de": "{{ block.messen.output }} weiter?", "en": "{{ block.measure.output }} continue?"},
                               "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}]}}]}
                """);
        when(workflowService.create(any(), eq(ChangeSource.POOL_INSTALL)))
                .thenReturn(Workflow.builder().id("wf-1").name("Freigabe-Beispiel").build());

        poolService.install("approval", new InstallPoolWorkflowRequest("proj-1", null, null, "de"));

        verify(workflowService).create(argThat(req -> "Freigabe-Beispiel".equals(req.name())
                        && "Zeigt eine Freigabe".equals(req.description())
                        && "Deutsch".equals(req.instructions())
                        && "messen".equals(req.blocks().get(0).getName())
                        && List.of("Ja", "Nein").equals(req.blocks().get(1).getConfig().get("options"))),
                eq(ChangeSource.POOL_INSTALL));
    }

    @Test
    void install_inTheOtherLanguage_updatesTheEarlierInstallInsteadOfDuplicating() throws Exception {
        writePoolFile("approval.json", """
                {"i18n": ["de", "en"],
                 "name": {"de": "Freigabe-Beispiel", "en": "Approval example"},
                 "description": {"de": "Zeigt eine Freigabe", "en": "Shows an approval"},
                 "instructions": {"de": "Deutsch", "en": "English"},
                 "blocks": [
                   {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
                   {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
                    "config": {"question": {"de": "{{ block.messen.output }} weiter?", "en": "{{ block.measure.output }} continue?"},
                               "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}]}}]}
                """);
        Workflow germanInstall = Workflow.builder().id("wf-7").name("Freigabe-Beispiel").build();
        when(workflowService.findByNameAndProjectId("Approval example", "proj-1")).thenReturn(Optional.empty());
        when(workflowService.findByNameAndProjectId("Freigabe-Beispiel", "proj-1")).thenReturn(Optional.of(germanInstall));
        when(workflowService.update(eq("wf-7"), any(), eq(ChangeSource.POOL_INSTALL))).thenReturn(germanInstall);

        WorkflowPoolService.InstallResult result =
                poolService.install("approval", new InstallPoolWorkflowRequest("proj-1", null, null, "en"));

        assertThat(result.created()).isFalse();
        verify(workflowService).update(eq("wf-7"),
                argThat(req -> "Approval example".equals(req.name()) && "measure".equals(req.blocks().get(0).getName())),
                eq(ChangeSource.POOL_INSTALL));
    }

    private void stubWorkflow(Workflow workflow) {
        when(workflowService.findById(workflow.getId())).thenReturn(workflow);
    }

    private Map<String, Object> exported(String filename) throws Exception {
        return objectMapper.readValue(tempDir.resolve(filename).toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private void writePoolFile(String filename, String content) throws Exception {
        Files.writeString(tempDir.resolve(filename), content);
    }

    /** Runs the action with an appender on the service's logger at WARN; logback-test.xml keeps root at ERROR. */
    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(WorkflowPoolService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        boolean additive = logger.isAdditive();
        logger.setLevel(Level.WARN);
        logger.setAdditive(false); // captured, not also printed to the console
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            logger.setLevel(previous);
            appender.stop();
        }
        return appender.list;
    }
}
