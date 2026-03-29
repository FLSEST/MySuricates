package com.automation.service.pool;

import com.automation.config.WorkflowPoolConfig;
import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.response.PoolWorkflowEntry;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        poolService = new WorkflowPoolService(config, objectMapper, workflowService, projectRepository);
    }

    @Test
    void listAll_emptyDirectory_returnsEmptyList() {
        List<PoolWorkflowEntry> result = poolService.listAll();
        assertThat(result).isEmpty();
    }

    @Test
    void listAll_nonExistentDirectory_returnsEmptyList() {
        config.setDirectory(tempDir.resolve("nonexistent").toString());
        poolService = new WorkflowPoolService(config, objectMapper, workflowService, projectRepository);

        List<PoolWorkflowEntry> result = poolService.listAll();
        assertThat(result).isEmpty();
    }

    @Test
    void listAll_disabledPool_returnsEmptyList() throws Exception {
        writePoolFile("test.json", """
                {"name": {"en": "Test"}, "blocks": [{"type": "simple", "config": {"command": "test"}}]}
                """);
        config.setEnabled(false);
        poolService = new WorkflowPoolService(config, objectMapper, workflowService, projectRepository);

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
        when(workflowService.create(any())).thenReturn(mockWorkflow);

        InstallPoolWorkflowRequest request = new InstallPoolWorkflowRequest("proj-1", null, null);
        WorkflowPoolService.InstallResult result = poolService.install("test-flow", request);

        assertThat(result.workflow().getId()).isEqualTo("wf-1");
        verify(workflowService).create(any());
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
        when(workflowService.create(any())).thenReturn(mockWorkflow);

        InstallPoolWorkflowRequest request = new InstallPoolWorkflowRequest("proj-1", "Custom Name", null);
        poolService.install("test-flow", request);

        verify(workflowService).create(argThat(req -> req.name().equals("Custom Name")));
    }

    @Test
    void install_nonExistentPool_throwsResourceNotFoundException() {
        InstallPoolWorkflowRequest request = new InstallPoolWorkflowRequest("proj-1", null, null);
        assertThatThrownBy(() -> poolService.install("nonexistent", request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void writePoolFile(String filename, String content) throws Exception {
        Files.writeString(tempDir.resolve(filename), content);
    }
}
