package com.automation.executor;

import com.automation.entity.StepResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step metadata that describes the AI call behind a step: {@code aiRequest} (the settings actually
 * used), {@code provider}, {@code requestedModel} and the response {@code model}.
 * <p>
 * Host steps get these keys from {@code CommandExecutorService} (via {@code HostRequestAiMetadata});
 * wrapper blocks (foreach, while, conditional, switch, retry, skill-capability) build their own
 * aggregate {@link StepResult} and use {@link #carryAi} to keep the keys of their first inner AI call,
 * so the run view can show which provider/model ran. Provider and model are constant per block, so
 * the first call is representative.
 */
public final class StepAiMetadata {

    public static final String AI_REQUEST = "aiRequest";
    static final List<String> CARRIED_KEYS = List.of(AI_REQUEST, "provider", "requestedModel", "model");

    private StepAiMetadata() {
    }

    /**
     * The {@code aiRequest} map: provider, model and effort actually used (agentic loop). A host call adds
     * its request settings on top ({@code service.cli.HostRequestAiMetadata}); the result is mutable for that.
     */
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
