package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.StepActors.Actor;
import com.automation.executor.blocks.StepActors.AiAnswer;
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
        if (actor != Actor.HUMAN && !actors.enabled()) {
            return buildResult(stepIndex, start, prompt, StepActors.disabledMessage("human-input", actor),
                    StepStatus.FAILED, null);
        }
        String answer = null;
        Map<String, Object> metadata = null;
        AiAnswer proposal = null;
        String proposalText = null;
        String shownPrompt = prompt;
        if (actor != Actor.HUMAN) {
            proposal = actors.answer(block, context, stepIndex, HumanInputAnswers.task(prompt, settings));
            if (actor == Actor.AI) {
                if (proposal.failed()) {
                    return buildResult(stepIndex, start, prompt, StepActors.failedCallOutput(proposal),
                            StepStatus.FAILED, StepActors.aiMetadata(actor, proposal));
                }
                HumanInputAnswers.Validated checked = HumanInputAnswers.validate(proposal.text(), settings);
                if (!checked.valid()) {
                    return buildResult(stepIndex, start, prompt, "AI actor answered '" + proposal.text()
                            + "' — " + checked.error(), StepStatus.FAILED, StepActors.aiMetadata(actor, proposal));
                }
                answer = checked.answer();
                log.info("Human input answered by the AI actor for prompt: {}", prompt);
                metadata = StepActors.aiMetadata(actor, proposal);
            } else {
                // Shadow: the proposal is recorded as given, valid or not — the person decides either way
                proposalText = proposal.failed() ? "" : proposal.text();
                shownPrompt = StepActors.withProposal(prompt, proposalText);
            }
        }

        if (answer == null) {
            ApprovalRequest request = ApprovalRequest.builder()
                    .blockId("human-input-" + stepIndex)
                    .question(shownPrompt)
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

            try {
                answer = context.awaitApproval(pending, settings.timeoutMinutes(), TimeUnit.MINUTES);
                metadata = actor == Actor.SHADOW
                        ? StepActors.shadowMetadata(proposal, proposalText,
                                HumanInputAnswers.same(answer, proposalText, settings))
                        : Map.of(StepActors.ANSWERED_BY, Actor.HUMAN.key());
            } catch (TimeoutException e) {
                log.warn("Human input timed out for prompt: {}", prompt);
                String defaultValue = settings.defaultValue();
                if (settings.useDefaultOnTimeout() && defaultValue != null && !defaultValue.isEmpty()) {
                    answer = defaultValue;
                    log.info("Using default value '{}' after timeout for prompt: {}", defaultValue, prompt);
                } else {
                    return buildResult(stepIndex, start, prompt, "Timed out waiting for human input",
                            StepStatus.FAILED, null);
                }
            } catch (Exception e) {
                log.warn("Human input interrupted for prompt: {}", prompt);
                return buildResult(stepIndex, start, prompt, "Human input cancelled: " + e.getMessage(),
                        StepStatus.FAILED, null);
            }
            Map<String, Object> resolved = new HashMap<>();
            resolved.put("stepIndex", stepIndex);
            resolved.put("answer", answer);
            broadcaster.broadcastLogMessage(context.getWorkflowRunId(), "human_input_resolved", resolved);
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
