package com.automation.service.cli;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.StepStatus;
import com.automation.entity.StepResult;
import com.automation.exception.WorkflowExecutionException;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.HostClaudeService.ActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
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
    private HostClaudeService hostClaudeService;

    @Mock
    private ShellCommandService shellCommandService;

    @Mock
    private AiProviderRegistry aiProviderRegistry;

    private CommandExecutorService commandExecutorService;

    @BeforeEach
    void setUp() throws IOException {
        commandExecutorService = new CommandExecutorService(sessionManager, hostClaudeService, shellCommandService, aiProviderRegistry);
        lenient().when(sessionManager.getOrCreateSession(anyString(), anyString())).thenReturn(cliSession);
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
        when(hostClaudeService.executeAction(eq("hello"), eq("sonnet"), eq("sys prompt"), eq("/project"), isNull()))
                .thenReturn(new ActionResult("Hello world", 0, null));

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
        when(hostClaudeService.executeAction(anyString(), any(), any(), anyString(), isNull()))
                .thenThrow(new IOException("Claude not found"));

        assertThatThrownBy(() -> commandExecutorService.executeOnHost(
                HostExecutionRequest.builder()
                        .projectPath("/project")
                        .command("cmd")
                        .build()))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("/project");
    }

    @Test
    void executeStepOnHost_success_returnsCompletedStep() throws IOException {
        when(hostClaudeService.executeAction(eq("npm test"), any(), any(), eq("/project"), isNull()))
                .thenReturn(new ActionResult("All tests passed", 0, null));

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
        when(hostClaudeService.executeAction(eq("npm test"), any(), any(), eq("/project"), isNull()))
                .thenReturn(new ActionResult("Error: tests failed", 1, null));

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
}
