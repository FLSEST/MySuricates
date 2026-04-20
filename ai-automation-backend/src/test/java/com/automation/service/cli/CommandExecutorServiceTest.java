package com.automation.service.cli;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.StepStatus;
import com.automation.entity.StepResult;
import com.automation.exception.WorkflowExecutionException;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommandExecutorServiceTest {

    @Mock
    private CliSessionManager sessionManager;

    @Mock
    private CliSession cliSession;

    @Mock
    private ShellCommandService shellCommandService;

    @Mock
    private AiProviderRegistry aiProviderRegistry;

    @Mock
    private AiProvider aiProvider;

    private CommandExecutorService commandExecutorService;

    @BeforeEach
    void setUp() throws IOException {
        commandExecutorService = new CommandExecutorService(sessionManager, shellCommandService, aiProviderRegistry);
        lenient().when(sessionManager.getOrCreateSession(anyString(), anyString())).thenReturn(cliSession);
        lenient().when(aiProviderRegistry.resolve(any())).thenReturn(aiProvider);
    }

    @Test
    void executeCommand_success_returnsResponse() throws IOException {
        when(cliSession.sendCommand("echo hello")).thenReturn(new CliSession.CommandResult("hello", 0));

        CliSession.CommandResult result = commandExecutorService.executeCommand("container-1", "claude", "echo hello");

        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void executeCommand_ioException_throwsWorkflowExecutionException() throws IOException {
        when(sessionManager.getOrCreateSession(anyString(), anyString()))
                .thenThrow(new IOException("Connection failed"));

        assertThatThrownBy(() -> commandExecutorService.executeCommand("container-1", "claude", "cmd"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("container-1");
    }

    @Test
    void executeStep_successfulCommand_returnsCompletedStep() throws IOException {
        when(cliSession.sendCommandEnriched(eq("npm test"), any(), any()))
                .thenReturn(new CliSession.EnrichedCommandResult("All tests passed", 0, null));

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "npm test"))
                .build();

        StepResult result = commandExecutorService.executeStep("container-1", "claude", block, 0);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("All tests passed");
        assertThat(result.getStepIndex()).isEqualTo(0);
        assertThat(result.getInput()).isEqualTo("npm test");
        assertThat(result.getDurationMs()).isNotNull();
    }

    @Test
    void executeStep_nonZeroExitCode_returnsFailedStep() throws IOException {
        when(cliSession.sendCommandEnriched(eq("npm test"), any(), any()))
                .thenReturn(new CliSession.EnrichedCommandResult("Error: test suite failed", 1, null));

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "npm test"))
                .build();

        StepResult result = commandExecutorService.executeStep("container-1", "claude", block, 0);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void executeStep_exception_returnsFailedWithErrorMessage() throws IOException {
        when(cliSession.sendCommandEnriched(anyString(), any(), any())).thenThrow(new IOException("Session dead"));

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "some command"))
                .build();

        StepResult result = commandExecutorService.executeStep("container-1", "claude", block, 0);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("container-1");
    }

    @Test
    void executeStep_noCommandInConfig_throwsException() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of())
                .build();

        assertThatThrownBy(() ->
                commandExecutorService.executeStep("container-1", "claude", block, 0))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("command");
    }

    @Test
    void injectSkillContext_withSkills_sendsAggregatedContext() throws IOException {
        when(cliSession.sendCommand(contains("Code Reviewer")))
                .thenReturn(new CliSession.CommandResult("ok", 0));

        Skill skill = Skill.builder()
                .id("skill-1")
                .name("Code Reviewer")
                .category(SkillCategory.CODE)
                .aiContext("You are a code reviewer.")
                .build();

        commandExecutorService.injectSkillContext("container-1", "claude", List.of(skill));

        verify(cliSession).sendCommand(contains("Code Reviewer"));
    }

    @Test
    void injectSkillContext_nullSkills_doesNothing() throws IOException {
        commandExecutorService.injectSkillContext("container-1", "claude", null);

        verify(cliSession, never()).sendCommand(anyString());
    }

    @Test
    void injectSkillContext_emptySkills_doesNothing() throws IOException {
        commandExecutorService.injectSkillContext("container-1", "claude", List.of());

        verify(cliSession, never()).sendCommand(anyString());
    }

    @Test
    void buildSkillContext_combinesMultipleSkills() {
        Skill skill1 = Skill.builder()
                .id("s1").name("Skill A").category(SkillCategory.CODE)
                .aiContext("Context A").build();
        Skill skill2 = Skill.builder()
                .id("s2").name("Skill B").category(SkillCategory.DOCUMENT)
                .aiContext("Context B").build();

        String context = commandExecutorService.buildSkillContext(List.of(skill1, skill2));

        assertThat(context).contains("Skill A").contains("Context A");
        assertThat(context).contains("Skill B").contains("Context B");
    }

    @Test
    void buildSkillContext_skipsSkillsWithoutContext() {
        Skill skill = Skill.builder()
                .id("s1").name("No Context").category(SkillCategory.CODE)
                .aiContext(null).build();

        String context = commandExecutorService.buildSkillContext(List.of(skill));

        assertThat(context).isEmpty();
    }

    // === Host-based execution tests ===

    @Test
    void executeOnHost_success_returnsEnrichedResult() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("Hello world", 0, null));

        CliSession.EnrichedCommandResult result = commandExecutorService.executeOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .command("hello")
                        .model("sonnet")
                        .systemPrompt("sys prompt")
                        .build());

        assertThat(result.output()).isEqualTo("Hello world");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void executeOnHost_ioException_throwsWorkflowExecutionException() throws IOException {
        when(aiProvider.executeAction(any())).thenThrow(new IOException("Claude not found"));

        assertThatThrownBy(() -> commandExecutorService.executeOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .command("cmd")
                        .build()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("/project");
    }

    @Test
    void executeOnHost_routesThroughRegistry_forClaudeProvider() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("result", 0, null));

        commandExecutorService.executeOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .command("cmd")
                        .providerId("CLAUDE")
                        .build());

        verify(aiProviderRegistry).resolve("CLAUDE");
        verify(aiProvider).executeAction(any());
    }

    @Test
    void executeStepOnHost_success_returnsCompletedStep() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("All tests passed", 0, null));
        when(aiProvider.getId()).thenReturn("CLAUDE");

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "npm test"))
                .build();

        StepResult result = commandExecutorService.executeStepOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .block(block)
                        .stepIndex(0)
                        .build());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("All tests passed");
        assertThat(result.getStepIndex()).isEqualTo(0);
        assertThat(result.getInput()).isEqualTo("npm test");
        assertThat(result.getDurationMs()).isNotNull();
    }

    @Test
    void executeStepOnHost_nonZeroExitCode_returnsFailedStep() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("Error: tests failed", 1, null));
        when(aiProvider.getId()).thenReturn("CLAUDE");

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "npm test"))
                .build();

        StepResult result = commandExecutorService.executeStepOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .block(block)
                        .stepIndex(0)
                        .build());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void executeStepOnHost_storesProviderIdInMetadata() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("output", 0, null));
        when(aiProvider.getId()).thenReturn("CODEX");
        when(aiProviderRegistry.resolve("CODEX")).thenReturn(aiProvider);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "codex task"))
                .build();

        StepResult result = commandExecutorService.executeStepOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .block(block)
                        .providerId("CODEX")
                        .build());

        assertThat(result.getMetadata()).containsEntry("provider", "CODEX");
    }

    @SuppressWarnings("unchecked")
    @Test
    void executeStepOnHost_storesAiSystemPromptAndAiRequestInMetadata() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));
        when(aiProvider.getId()).thenReturn("CLAUDE");

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "analyze something"))
                .build();

        StepResult result = commandExecutorService.executeStepOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .block(block)
                        .model("claude-opus-4-6")
                        .systemPrompt("You are a helpful assistant.\nBe precise.")
                        .timeoutSeconds(300)
                        .build());

        assertThat(result.getMetadata())
                .containsEntry("aiSystemPrompt", "You are a helpful assistant.\nBe precise.")
                .containsKey("aiRequest");

        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "claude-opus-4-6")
                .containsEntry("timeoutSeconds", 300);
        assertThat(aiRequest).doesNotContainKey("priority");
    }

    @Test
    void executeStepOnHost_omitsAiSystemPromptWhenNull() throws IOException {
        when(aiProvider.executeAction(any())).thenReturn(new AiProvider.ActionResult("ok", 0, null));
        when(aiProvider.getId()).thenReturn("CLAUDE");

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("command", "task"))
                .build();

        StepResult result = commandExecutorService.executeStepOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .block(block)
                        .build());

        assertThat(result.getMetadata()).doesNotContainKey("aiSystemPrompt");
        assertThat(result.getMetadata()).containsKey("aiRequest");
    }

    // === writeSkillContextToProject (skill-context file on host project) ===

    @Test
    void writeSkillContextToProject_writesAggregatedContent(@TempDir Path tempDir) throws IOException {
        Skill skill1 = Skill.builder().id("s1").name("Lint").aiContext("Run eslint").build();
        Skill skill2 = Skill.builder().id("s2").name("Test").aiContext("Run pytest").build();

        commandExecutorService.writeSkillContextToProject(tempDir.toString(), List.of(skill1, skill2));

        Path contextFile = tempDir.resolve(".claude/rules/skills-context.md");
        assertThat(contextFile).exists();
        String content = Files.readString(contextFile);
        assertThat(content).contains("=== Skill: Lint ===", "Run eslint");
        assertThat(content).contains("=== Skill: Test ===", "Run pytest");
    }

    @Test
    void writeSkillContextToProject_nullSkills_deletesExistingFile(@TempDir Path tempDir) throws IOException {
        Path contextFile = tempDir.resolve(".claude/rules/skills-context.md");
        Files.createDirectories(contextFile.getParent());
        Files.writeString(contextFile, "stale content");

        commandExecutorService.writeSkillContextToProject(tempDir.toString(), null);

        assertThat(contextFile).doesNotExist();
    }

    @Test
    void writeSkillContextToProject_emptySkillsList_deletesExistingFile(@TempDir Path tempDir) throws IOException {
        Path contextFile = tempDir.resolve(".claude/rules/skills-context.md");
        Files.createDirectories(contextFile.getParent());
        Files.writeString(contextFile, "stale content");

        commandExecutorService.writeSkillContextToProject(tempDir.toString(), List.of());

        assertThat(contextFile).doesNotExist();
    }

    @Test
    void writeSkillContextToProject_blankProjectPath_isNoop(@TempDir Path tempDir) {
        Skill skill = Skill.builder().id("s1").name("Lint").aiContext("Run eslint").build();

        // Must not throw, must not create anything in the temp dir
        commandExecutorService.writeSkillContextToProject("", List.of(skill));
        commandExecutorService.writeSkillContextToProject(null, List.of(skill));

        assertThat(tempDir.resolve(".claude/rules/skills-context.md")).doesNotExist();
    }

    @Test
    void writeSkillContextToProject_skillsWithBlankContext_deletesFile(@TempDir Path tempDir) throws IOException {
        // buildSkillContext filters out blank aiContext → aggregated result is empty → delete path
        Path contextFile = tempDir.resolve(".claude/rules/skills-context.md");
        Files.createDirectories(contextFile.getParent());
        Files.writeString(contextFile, "old content");
        Skill blank = Skill.builder().id("s1").name("Empty").aiContext("  ").build();

        commandExecutorService.writeSkillContextToProject(tempDir.toString(), List.of(blank));

        assertThat(contextFile).doesNotExist();
    }
}
