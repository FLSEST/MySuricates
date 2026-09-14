package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleRegistry;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.skill.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillCapabilityBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @Mock
    private SkillService skillService;

    private SkillCapabilityBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new SkillCapabilityBlockExecutor(commandExecutor, skillService, ModuleRegistry.allEnabled());
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
        context.setProjectPath("/test/project");
    }

    @Test
    void supports_skillCapabilityType() {
        assertThat(executor.supports("skill-capability")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void execute_skillsModuleDisabled_failsBeforeAnyCliCall() {
        SkillCapabilityBlockExecutor coreOnly = new SkillCapabilityBlockExecutor(commandExecutor, skillService,
                ModuleRegistry.of(Map.of(), Map.of()));
        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(new HashMap<>(Map.of("capability", "fact_check")))
                .build();

        assertThatThrownBy(() -> coreOnly.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("module 'skills'");
        verifyNoInteractions(commandExecutor, skillService);
    }

    @Test
    void execute_validCapability_returnsCompletedResult() {
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("fact_check"))))
                .thenReturn(new CliSession.EnrichedCommandResult("Facts verified: 95% accuracy", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "fact_check");
        config.put("input", "The earth is round.");
        config.put("params", Map.of("strictness", "high"));

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("95% accuracy");
        assertThat(result.getInput()).contains("fact_check");
    }

    @Test
    void execute_resolvesInputTemplate() {
        context.setPreviousOutput("document content here");
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("document content here"))))
                .thenReturn(new CliSession.EnrichedCommandResult("analyzed", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "analyze");
        config.put("input", "{{ previous_output }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_commandThrows_returnsFailedResult() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new RuntimeException("AI unavailable"));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "ocr");
        config.put("input", "image data");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("AI unavailable");
    }

    @Test
    void execute_nullCapability_throwsException() {
        Map<String, Object> config = new HashMap<>();
        config.put("input", "some input");
        // No "capability" key

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("capability");
    }

    @Test
    void execute_noInput_usesEmptyString() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("processed", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "summarize");
        // No input

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_codexProvider_passesProviderIdToRequest() {
        context.setProvider("CODEX");
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("ok", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "summarize");
        config.put("input", "text");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req -> "CODEX".equals(req.providerId())));
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequest() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("ok", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "summarize");
        config.put("input", "text");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req -> req.providerId() == null));
    }

    // ── Safety: writes-capability requires opt-in ──────────────────────

    @Test
    void execute_writeCapabilityWithoutOptIn_returnsFailedAndDoesNotCallCli() {
        Skill skill = makeSkill("test-skill", "Be a tester.");
        addCapability(skill, "generate_tests", "Write integration tests", true);
        when(skillService.findById("test-skill")).thenReturn(skill);

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "generate_tests");
        config.put("input", "code here");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .skillIds(List.of("test-skill"))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("writes to the project filesystem");
        assertThat(result.getOutput()).contains("allow_writes: true");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void execute_writeCapabilityWithOptIn_succeedsWithoutToolRestriction() {
        Skill skill = makeSkill("test-skill", "Be a tester.");
        addCapability(skill, "generate_tests", "Write integration tests", true);
        when(skillService.findById("test-skill")).thenReturn(skill);
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("tests written", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "generate_tests");
        config.put("input", "code here");
        config.put("allow_writes", true);

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .skillIds(List.of("test-skill"))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor).executeOnHost(argThat(req ->
                req.allowedTools() == null || req.allowedTools().isEmpty()));
    }

    @Test
    void execute_readOnlyCapabilityByDefault_passesReadOnlyToolWhitelist() {
        Skill skill = makeSkill("analyzer-skill", "Analyze things.");
        addCapability(skill, "summarize", "Summarize input text", false);
        when(skillService.findById("analyzer-skill")).thenReturn(skill);
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("summary", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "summarize");
        config.put("input", "long text");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .skillIds(List.of("analyzer-skill"))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor).executeOnHost(argThat(req -> {
            List<String> tools = req.allowedTools();
            return tools != null && tools.contains("Read") && !tools.contains("Edit") && !tools.contains("Write");
        }));
    }

    @Test
    void execute_blockWithoutSkillIds_stillAppliesReadOnlySafetyNet() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("ok", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "anything");
        config.put("input", "text");
        // No skillIds — capability metadata cannot be resolved.

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req -> {
            List<String> tools = req.allowedTools();
            return tools != null && !tools.contains("Edit");
        }));
    }

    @Test
    void execute_skillAiContextInjectedIntoSystemPrompt() {
        context.setInstructionPrefix("Workflow says: be terse.");
        Skill skill = makeSkill("an-skill", "You are a security analyst.");
        addCapability(skill, "scan", "Scan code", false);
        when(skillService.findById("an-skill")).thenReturn(skill);
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("ok", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "scan");
        config.put("input", "snippet");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .skillIds(List.of("an-skill"))
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req ->
                req.systemPrompt() != null
                        && req.systemPrompt().contains("You are a security analyst.")
                        && req.systemPrompt().contains("Workflow says: be terse.")));
    }

    @Test
    void execute_capabilityDescriptionUsedInsteadOfFictionalToolFraming() {
        Skill skill = makeSkill("an-skill", "Analyst.");
        addCapability(skill, "scan", "Identify OWASP Top 10 issues", false);
        when(skillService.findById("an-skill")).thenReturn(skill);
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("ok", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "scan");
        config.put("input", "snippet");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .skillIds(List.of("an-skill"))
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req -> {
            String cmd = req.command();
            return cmd.contains("Identify OWASP Top 10 issues")
                    && !cmd.contains("Use your '")
                    && !cmd.contains("' capability");
        }));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Skill makeSkill(String id, String aiContext) {
        Skill s = Skill.builder()
                .id(id)
                .name(id)
                .aiContext(aiContext)
                .category(SkillCategory.CODE)
                .builtin(true)
                .capabilities(new ArrayList<>())
                .build();
        return s;
    }

    private void addCapability(Skill s, String name, String description, boolean writes) {
        Capability c = Capability.builder()
                .skill(s)
                .name(name)
                .description(description)
                .writes(writes)
                .build();
        s.getCapabilities().add(c);
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("CODEX");
        context.setModel("gpt-5");
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("ok", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "summarize");
        config.put("input", "text");
        config.put("provider", "PI");
        config.put("model", "anthropic/opus");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_copiesProviderModelAndAiRequestFromHostCall() {
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("fact_check"))))
                .thenReturn(new CliSession.EnrichedCommandResult("verified", 0, null, Map.of(
                        "aiRequest", Map.of("provider", "CLAUDE", "model", "opus", "allowedTools", "Read,Grep"),
                        "provider", "CLAUDE", "requestedModel", "opus", "inputTokens", 42)));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "fact_check");
        config.put("input", "The earth is round.");
        BlockDefinition block = BlockDefinition.builder().type("skill-capability").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata())
                .containsEntry("provider", "CLAUDE")
                .containsEntry("requestedModel", "opus")
                .containsEntry("inputTokens", 42);
        assertThat((Map<String, Object>) result.getMetadata().get("aiRequest")).containsEntry("allowedTools", "Read,Grep");
        result.getMetadata().put("x", 1);
    }
}
