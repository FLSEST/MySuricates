package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.skill.SkillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class SkillCapabilityBlockExecutor implements BlockExecutor {

    /**
     * Tool whitelist applied at the host-CLI level when the block has not opted in
     * via {@code allow_writes: true}. This is the defense-in-depth safety net —
     * even if the capability metadata is missing or stale, blocks default to read-only.
     */
    private static final List<String> READ_ONLY_TOOLS = List.of(
            "Read", "Grep", "Glob", "WebFetch", "WebSearch", "Task", "TodoWrite"
    );

    private final CommandExecutorService commandExecutor;
    private final SkillService skillService;
    private final ModuleRegistry modules;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        if (!modules.isEnabled(ModuleCatalog.SKILLS)) {
            throw new com.automation.exception.WorkflowExecutionException(
                    ModuleCatalog.SKILLS.disabledMessage("Block type 'skill-capability'"));
        }
        Map<String, Object> config = block.getConfig();
        String capabilityName = (String) config.get("capability");
        if (capabilityName == null) {
            throw new com.automation.exception.WorkflowExecutionException(
                    "Skill-capability block requires a 'capability' in config");
        }
        Object rawInput = config.get("input");
        String input = context.resolveTemplate(rawInput != null ? rawInput.toString() : "");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) config.getOrDefault("params", Map.of());
        boolean allowWrites = Boolean.TRUE.equals(config.get("allow_writes"));

        // Resolve skill + capability for aiContext, description, writes flag.
        // If lookup fails (no skillIds, skill not found, capability not found), we proceed
        // without aiContext but keep the read-only safety net via allowedTools.
        Skill resolvedSkill = null;
        Capability resolvedCapability = null;
        List<String> skillIds = block.getSkillIds();
        if (skillIds != null) {
            for (String sid : skillIds) {
                try {
                    Skill s = skillService.findById(sid);
                    Capability c = s.getCapabilities().stream()
                            .filter(cc -> capabilityName.equals(cc.getName()))
                            .findFirst().orElse(null);
                    if (c != null) {
                        resolvedSkill = s;
                        resolvedCapability = c;
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Skill lookup failed for {}: {}", sid, e.getMessage());
                }
            }
        }

        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());
        result.setInput("skill-capability: " + capabilityName);

        // Backend-enforced guard: writes capability requires explicit opt-in.
        if (resolvedCapability != null && resolvedCapability.isWrites() && !allowWrites) {
            String msg = "Capability '" + capabilityName + "' writes to the project filesystem. "
                    + "Set 'allow_writes: true' in the block config to enable, or use a read-only capability.";
            log.warn("Blocked write-capable skill-capability call: {}", msg);
            result.setOutput(msg);
            result.setStatus(StepStatus.FAILED);
            result.setCompletedAt(Instant.now());
            result.setDurationMs(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
            return result;
        }

        // Compose user prompt: capability description (if known) + parameters + input.
        // Replaces the older "Use your '<capability>' capability" framing — that string
        // confused the LLM (it would disclaim the fictional tool) and burned tokens on disclaimers.
        StringBuilder userPrompt = new StringBuilder();
        if (resolvedCapability != null
                && resolvedCapability.getDescription() != null
                && !resolvedCapability.getDescription().isBlank()) {
            userPrompt.append("Task: ").append(resolvedCapability.getDescription()).append("\n\n");
        } else {
            // Fallback when capability metadata is unavailable.
            userPrompt.append("Task: ").append(capabilityName).append("\n\n");
        }
        if (!params.isEmpty()) {
            userPrompt.append("Parameters: ").append(params).append("\n\n");
        }
        userPrompt.append("Input:\n").append(input);

        // Compose system prompt: skill aiContext + workflow instructions.
        String workflowInstructions = context.getInstructionPrefix();
        String composedSystemPrompt = composeSystemPrompt(
                resolvedSkill != null ? resolvedSkill.getAiContext() : null,
                workflowInstructions
        );

        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        try {
            HostExecutionRequest.HostExecutionRequestBuilder reqBuilder = HostExecutionRequest.builder()
                    .projectPath(context.getProjectPath())
                    .command(userPrompt.toString())
                    .model(context.modelFor(block))
                    .systemPrompt(composedSystemPrompt)
                    .timeoutSeconds(claudeTimeout)
                    .providerId(context.providerFor(block))
                    .effort(context.effortFor(block));

            // Defense-in-depth: when the workflow author has not explicitly opted in to writes,
            // restrict the host-CLI to a read-only tool whitelist. This applies regardless of
            // whether the capability metadata could be resolved.
            if (!allowWrites) {
                reqBuilder.allowedTools(READ_ONLY_TOOLS);
            }

            var response = commandExecutor.executeOnHost(reqBuilder.build());
            result.setOutput(response.output());
            result.setStatus(StepStatus.COMPLETED);
            // provider, requestedModel, aiRequest (incl. allowedTools) and response metadata of the host call
            result.setMetadata(new LinkedHashMap<>(response.metadata()));
        } catch (Exception e) {
            result.setOutput("Capability execution failed: " + e.getMessage());
            result.setStatus(StepStatus.FAILED);
        }

        result.setCompletedAt(Instant.now());
        result.setDurationMs(
                result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli()
        );
        return result;
    }

    private String composeSystemPrompt(String aiContext, String workflowInstructions) {
        boolean hasAi = aiContext != null && !aiContext.isBlank();
        boolean hasWf = workflowInstructions != null && !workflowInstructions.isBlank();
        if (hasAi && hasWf) {
            return aiContext + "\n\n" + workflowInstructions;
        }
        if (hasAi) {
            return aiContext;
        }
        return workflowInstructions;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("skill-capability");
    }
}
