package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.StepActors.Actor;
import com.automation.service.workflow.ExecutionBroadcaster;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final ExecutionBroadcaster broadcaster;
    private final StepActors actors;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Set<String> supportedTypes() {
        return Set.of("human-input");
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = Instant.now();
        Settings settings = Settings.from(block.getConfig(), context);
        String prompt = settings.prompt();

        // Who answers (TODO dca0c03e): a person as always, an AI call in the role, or both (shadow)
        Actor actor = StepActors.resolve(block.getConfig(), context);
        String refusal = actors.refusal(actor, "human-input");
        if (refusal != null) {
            return buildResult(stepIndex, start, prompt, refusal, StepStatus.FAILED, null);
        }
        ActorDecision decision = actors.decide(actor, block, context, stepIndex, HumanInputAnswers.task(prompt, settings),
                text -> HumanInputAnswers.checked(text, settings));
        if (decision.failed()) {
            return buildResult(stepIndex, start, prompt, decision.failure(), StepStatus.FAILED, decision.aiMetadata());
        }

        String answer;
        Map<String, Object> metadata;
        if (decision.answeredByAi()) {
            answer = decision.answer();
            metadata = decision.aiMetadata();
            log.info("Human input answered by the AI actor for prompt: {}", prompt);
        } else {
            PersonAnswer person = awaitPerson(stepIndex, settings, decision, context);
            if (person.failure() != null) {
                return buildResult(stepIndex, start, prompt, person.failure(), StepStatus.FAILED, person.metadata());
            }
            answer = person.answer();
            metadata = person.metadata();
        }

        String output;
        if (settings.multiField()) {
            output = storeMultiField(answer, settings, context);
        } else {
            // Single field: the raw answer under variable_name (legacy behavior)
            context.setVariable(settings.variableName(), answer);
            output = answer;
        }
        return buildResult(stepIndex, start, prompt, output, StepStatus.COMPLETED, metadata);
    }

    /**
     * A person's answer, the default value after a timeout, or why there is none.
     *
     * @param failure the step output when the step fails (timeout without default, cancel)
     */
    private record PersonAnswer(String answer, String failure, Map<String, Object> metadata) {}

    /**
     * Asks the person (in shadow mode with the AI's proposal below the prompt) and waits. After a timeout the
     * default value answers when the block allows it; nobody answered then, so a shadow step records no
     * agreement either way.
     */
    private PersonAnswer awaitPerson(int stepIndex, Settings settings, ActorDecision decision, ExecutionContext context) {
        String prompt = settings.prompt();
        ApprovalRequest request = ApprovalRequest.builder()
                .blockId("human-input-" + stepIndex)
                .question(decision.shown(prompt))
                .options(settings.options())
                .stepIndex(stepIndex)
                .type("human-input")
                .inputType(settings.inputType())
                .defaultValue(settings.defaultValue())
                .variableName(settings.variableName())
                .required(settings.required())
                .validationPattern(settings.validationPattern())
                .fields(settings.fields())
                .timeoutMinutes(settings.timeoutMinutes())
                .build();
        CompletableFuture<String> pending = ask(request, context);
        String answer;
        Map<String, Object> metadata;
        try {
            answer = context.awaitApproval(pending, settings.timeoutMinutes(), TimeUnit.MINUTES);
            metadata = decision.afterPerson(HumanInputAnswers.same(answer, decision.proposalText(), settings));
        } catch (TimeoutException e) {
            log.warn("Human input timed out for prompt: {}", prompt);
            String defaultValue = settings.defaultValue();
            if (!settings.useDefaultOnTimeout() || defaultValue == null || defaultValue.isEmpty()) {
                return new PersonAnswer(null, "Timed out waiting for human input", decision.unanswered());
            }
            log.info("Using default value '{}' after timeout for prompt: {}", defaultValue, prompt);
            answer = defaultValue;
            metadata = decision.unanswered();
        } catch (Exception e) {
            log.warn("Human input interrupted for prompt: {}", prompt);
            return new PersonAnswer(null, "Human input cancelled: " + e.getMessage(), decision.unanswered());
        }
        Map<String, Object> resolved = new HashMap<>();
        resolved.put("stepIndex", stepIndex);
        resolved.put("answer", answer);
        broadcaster.broadcastLogMessage(context.getWorkflowRunId(), "human_input_resolved", resolved);
        return new PersonAnswer(answer, null, metadata);
    }

    /**
     * The settings of one human-input block. Prompt and default value are resolved like the approval
     * question: {@code {{ block.X.output }}} should appear in the dialog, not literally (TODO e2efb393).
     *
     * @param fields a form with several inputs, {@code null} when the block has none
     */
    record Settings(String prompt, String inputType, String variableName, List<String> options,
                            String defaultValue, boolean required, String validationPattern, int timeoutMinutes,
                            boolean useDefaultOnTimeout, List<Map<String, Object>> fields) {

        @SuppressWarnings("unchecked")
        static Settings from(Map<String, Object> config, ExecutionContext context) {
            return new Settings(
                    context.resolveTemplate((String) config.getOrDefault("prompt", "Please provide input:")),
                    (String) config.getOrDefault("input_type", "text"),
                    (String) config.getOrDefault("variable_name", "user_input"),
                    config.containsKey("options") ? (List<String>) config.get("options") : List.of(),
                    context.resolveTemplate((String) config.getOrDefault("default_value", "")),
                    !config.containsKey("required") || Boolean.TRUE.equals(config.get("required")),
                    (String) config.get("validation_pattern"),
                    config.containsKey("timeout_minutes") ? ((Number) config.get("timeout_minutes")).intValue() : 30,
                    !config.containsKey("use_default_on_timeout")
                            || Boolean.TRUE.equals(config.get("use_default_on_timeout")),
                    config.get("fields") instanceof List ? (List<Map<String, Object>>) config.get("fields") : null);
        }

        boolean multiField() {
            return fields != null && !fields.isEmpty();
        }
    }

    /** Opens the request in the run and announces it on the run's log topic; the future carries the answer. */
    private CompletableFuture<String> ask(ApprovalRequest request, ExecutionContext context) {
        CompletableFuture<String> pending = context.requestApproval(request);
        Map<String, Object> payload = new HashMap<>();
        payload.put("blockId", request.getBlockId());
        payload.put("prompt", request.getQuestion());
        payload.put("inputType", request.getInputType());
        payload.put("options", request.getOptions());
        payload.put("defaultValue", request.getDefaultValue());
        payload.put("required", request.isRequired());
        payload.put("validationPattern", request.getValidationPattern());
        payload.put("variableName", request.getVariableName());
        payload.put("stepIndex", request.getStepIndex());
        if (request.getFields() != null && !request.getFields().isEmpty()) {
            payload.put("fields", request.getFields());
        }
        broadcaster.broadcastLogMessage(context.getWorkflowRunId(), "human_input_required", payload);
        return pending;
    }

    /**
     * A form with several fields: the answer is a JSON object, each field's value goes into the field's own
     * variable (a missing one as empty text). An answer that is no JSON object is kept whole under
     * {@code variable_name}. Returns the step output.
     */
    private static String storeMultiField(String answer, Settings settings, ExecutionContext context) {
        Map<String, String> parsed;
        try {
            parsed = JSON.readValue(answer == null ? "{}" : answer, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Multi-field human input could not be parsed as JSON, raw answer kept under {}: {}",
                    settings.variableName(), e.getMessage());
            context.setVariable(settings.variableName(), answer);
            return answer;
        }
        for (Map<String, Object> field : settings.fields()) {
            Object varNameObj = field.get("variable_name");
            if (!(varNameObj instanceof String varName) || varName.isBlank()) continue;
            context.setVariable(varName, parsed.getOrDefault(varName, ""));
        }
        return answer == null ? "" : answer;
    }

    private StepResult buildResult(int stepIndex, Instant start, String input, String output, StepStatus status,
                                   Map<String, Object> metadata) {
        Instant end = Instant.now();
        return StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Human Input] " + input)
                .output(output)
                .status(status)
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .metadata(metadata == null ? null : new HashMap<>(metadata))
                .build();
    }
}
