package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class HumanInputBlockExecutor implements BlockExecutor {

    private final SimpMessagingTemplate messagingTemplate;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Set<String> supportedTypes() {
        return Set.of("human-input");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = Instant.now();
        Map<String, Object> config = block.getConfig();

        // Resolved like the approval question: {{ block.X.output }} should appear in the dialog, not literally (TODO e2efb393)
        String prompt = context.resolveTemplate((String) config.getOrDefault("prompt", "Please provide input:"));
        String inputType = (String) config.getOrDefault("input_type", "text");
        String variableName = (String) config.getOrDefault("variable_name", "user_input");
        List<String> options = config.containsKey("options")
                ? (List<String>) config.get("options")
                : List.of();
        String defaultValue = context.resolveTemplate((String) config.getOrDefault("default_value", ""));
        boolean required = config.containsKey("required")
                ? Boolean.TRUE.equals(config.get("required"))
                : true;
        String validationPattern = (String) config.get("validation_pattern");
        int timeoutMinutes = config.containsKey("timeout_minutes")
                ? ((Number) config.get("timeout_minutes")).intValue()
                : 30;
        boolean useDefaultOnTimeout = config.containsKey("use_default_on_timeout")
                ? Boolean.TRUE.equals(config.get("use_default_on_timeout"))
                : true;

        List<Map<String, Object>> fields = config.get("fields") instanceof List
                ? (List<Map<String, Object>>) config.get("fields")
                : null;
        boolean multiField = fields != null && !fields.isEmpty();

        String blockId = "human-input-" + stepIndex;
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId(blockId)
                .question(prompt)
                .options(options)
                .stepIndex(stepIndex)
                .type("human-input")
                .inputType(inputType)
                .defaultValue(defaultValue)
                .variableName(variableName)
                .required(required)
                .validationPattern(validationPattern)
                .fields(fields)
                .timeoutMinutes(timeoutMinutes)
                .build();

        CompletableFuture<String> future = context.requestApproval(request);

        // Broadcast human_input_required via WebSocket
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "human_input_required");
            payload.put("blockId", blockId);
            payload.put("prompt", prompt);
            payload.put("inputType", inputType);
            payload.put("options", options);
            payload.put("defaultValue", defaultValue);
            payload.put("required", required);
            payload.put("validationPattern", validationPattern);
            payload.put("variableName", variableName);
            payload.put("stepIndex", stepIndex);
            if (multiField) {
                payload.put("fields", fields);
            }

            messagingTemplate.convertAndSend(
                    "/topic/executions/" + context.getWorkflowRunId() + "/logs",
                    payload
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast human input request: {}", e.getMessage());
        }

        String answer;
        try {
            answer = context.awaitApproval(future, timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("Human input timed out for prompt: {}", prompt);
            if (useDefaultOnTimeout && defaultValue != null && !defaultValue.isEmpty()) {
                answer = defaultValue;
                log.info("Using default value '{}' after timeout for prompt: {}", defaultValue, prompt);
            } else {
                return buildResult(stepIndex, start, prompt, "Timed out waiting for human input",
                        StepStatus.FAILED);
            }
        } catch (Exception e) {
            log.warn("Human input interrupted for prompt: {}", prompt);
            return buildResult(stepIndex, start, prompt, "Human input cancelled: " + e.getMessage(),
                    StepStatus.FAILED);
        }

        // Broadcast human_input_resolved
        try {
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + context.getWorkflowRunId() + "/logs",
                    Map.of(
                            "type", "human_input_resolved",
                            "stepIndex", stepIndex,
                            "answer", answer
                    )
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast human input resolution: {}", e.getMessage());
        }

        // Multi-field: parse JSON answer and store each entry as a separate variable.
        // Single-field: store the raw answer under variableName (legacy behavior).
        String storedOutput;
        if (multiField) {
            Map<String, String> parsed;
            try {
                parsed = JSON.readValue(answer == null ? "{}" : answer, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Multi-field human input could not be parsed as JSON, raw answer kept under {}: {}",
                        variableName, e.getMessage());
                context.setVariable(variableName, answer);
                return buildResult(stepIndex, start, prompt, answer, StepStatus.COMPLETED);
            }
            for (Map<String, Object> field : fields) {
                Object varNameObj = field.get("variable_name");
                if (!(varNameObj instanceof String varName) || varName.isBlank()) continue;
                String value = parsed.getOrDefault(varName, "");
                context.setVariable(varName, value);
            }
            storedOutput = answer == null ? "" : answer;
        } else {
            context.setVariable(variableName, answer);
            storedOutput = answer;
        }

        return buildResult(stepIndex, start, prompt, storedOutput, StepStatus.COMPLETED);
    }

    private StepResult buildResult(int stepIndex, Instant start, String input, String output, StepStatus status) {
        Instant end = Instant.now();
        return StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Human Input] " + input)
                .output(output)
                .status(status)
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .build();
    }
}
