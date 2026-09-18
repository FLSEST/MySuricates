package com.automation.executor;

import com.automation.entity.StepResult;
import com.automation.service.cli.HostExecutionRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step metadata that describes the AI call behind a step: {@code aiRequest} (the settings actually
 * used), {@code provider}, {@code requestedModel} and the response {@code model}.
 * <p>
 * Host steps get these keys from {@code CommandExecutorService}; wrapper blocks (foreach, while,
 * conditional, switch, retry, skill-capability) build their own aggregate {@link StepResult} and use
 * {@link #carryAi} to keep the keys of their first inner AI call, so the run view can show which
 * provider/model ran. Provider and model are constant per block, so the first call is representative.
 */
public final class StepAiMetadata {

    public static final String AI_REQUEST = "aiRequest";
    static final List<String> CARRIED_KEYS = List.of(AI_REQUEST, "provider", "requestedModel", "model");

    private StepAiMetadata() {
    }

    /** The {@code aiRequest} map of a host call: the request settings plus the resolved provider id. */
    public static Map<String, Object> aiRequest(HostExecutionRequest request, String resolvedProviderId) {
        Map<String, Object> params = aiRequest(resolvedProviderId, request.model(), request.effort());
        if (request.timeoutSeconds() != null) {
            params.put("timeoutSeconds", request.timeoutSeconds());
        }
        if (request.priority()) {
            params.put("priority", true);
        }
        putTools(params, "allowedTools", request.allowedTools());
        putTools(params, "disallowedTools", request.disallowedTools());
        return params;
    }

    /**
     * Adds a tool list as a comma-separated string, blank entries dropped. The {@code NO_TOOLS} sentinel
     * ({@code [""]}, "no built-in tools at all") therefore adds nothing instead of an empty string — same
     * convention as model and effort, which are omitted when blank.
     */
    private static void putTools(Map<String, Object> params, String key, List<String> tools) {
        if (tools == null) {
            return;
        }
        List<String> named = tools.stream().filter(t -> t != null && !t.isBlank()).toList();
        if (!named.isEmpty()) {
            params.put(key, String.join(",", named));
        }
    }

    /** Same shape without a request object (agentic loop): provider, model and effort actually used. */
    public static Map<String, Object> aiRequest(String providerId, String model, String effort) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("provider", providerId);
        if (model != null && !model.isBlank()) {
            params.put("model", model);
        }
        if (effort != null && !effort.isBlank()) {
            params.put("effort", effort);
        }
        return params;
    }

    /**
     * Puts a metadata entry, replacing a null or immutable map ({@code Map.of(...)} from shell or
     * error paths) with a mutable copy first.
     */
    public static void put(StepResult target, String key, Object value) {
        Map<String, Object> metadata = mutable(target);
        metadata.put(key, value);
        target.setMetadata(metadata);
    }

    /**
     * Copies the AI keys of an inner call into the wrapper step unless the wrapper already carries an
     * {@code aiRequest} (first inner AI call wins). Sources without {@code aiRequest} (shell steps,
     * error results) are ignored.
     *
     * @return true when the keys were copied
     */
    public static boolean carryAi(StepResult target, Map<String, Object> source) {
        if (source == null || !source.containsKey(AI_REQUEST)) {
            return false;
        }
        Map<String, Object> current = target.getMetadata();
        if (current != null && current.containsKey(AI_REQUEST)) {
            return false;
        }
        Map<String, Object> metadata = mutable(target);
        for (String key : CARRIED_KEYS) {
            Object value = source.get(key);
            if (value != null) {
                metadata.put(key, value);
            }
        }
        target.setMetadata(metadata);
        return true;
    }

    /** Null-safe variant of {@link #carryAi(StepResult, Map)} for inner {@link StepResult}s. */
    public static boolean carryAi(StepResult target, StepResult source) {
        return source != null && carryAi(target, source.getMetadata());
    }

    private static Map<String, Object> mutable(StepResult target) {
        Map<String, Object> current = target.getMetadata();
        return current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
    }
}
