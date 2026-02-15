package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SkillCapabilityBlockExecutor implements BlockExecutor {

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String capability = (String) config.get("capability");
        if (capability == null) {
            throw new com.automation.exception.WorkflowExecutionException(
                    "Skill-capability block requires a 'capability' in config");
        }
        Object rawInput = config.get("input");
        String input = context.resolveTemplate(rawInput != null ? rawInput.toString() : "");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) config.getOrDefault("params", Map.of());

        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());

        // Build capability-aware prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("Use your '").append(capability).append("' capability to process the following.\n");

        if (!params.isEmpty()) {
            prompt.append("Parameters: ").append(params).append("\n");
        }

        prompt.append("\nInput:\n").append(input);

        result.setInput("skill-capability: " + capability);

        try {
            String response = commandExecutor.executeCommand(
                    context.getContainerId(),
                    context.getCliType(),
                    prompt.toString()
            );

            result.setOutput(response);
            result.setStatus(StepStatus.COMPLETED);
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

    @Override
    public boolean supports(String blockType) {
        return "skill-capability".equals(blockType);
    }
}
