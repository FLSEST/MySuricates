package com.automation.service.cli;

import com.automation.executor.StepAiMetadata;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderCapabilities;

import java.util.List;
import java.util.Map;

/**
 * The {@code aiRequest} step metadata of a host call: {@link StepAiMetadata#aiRequest(String, String, String)}
 * plus the settings of the {@link HostExecutionRequest}. Lives next to the request, so the executor
 * package does not depend on {@code service.cli}.
 */
final class HostRequestAiMetadata {

    private HostRequestAiMetadata() {
    }

    /** The {@code aiRequest} map of a host call made through {@code provider}. */
    static Map<String, Object> aiRequest(HostExecutionRequest request, AiProvider provider) {
        AiProviderCapabilities capabilities = provider.getCapabilities();
        return aiRequest(request, provider.getId(), capabilities != null && capabilities.supportsEffort());
    }

    /**
     * The {@code aiRequest} map of a host call: the request settings plus the resolved provider id.
     *
     * @param effortApplies whether the provider passes an effort on to its CLI. Codex has no flag for it
     *                      and ignores it, so its steps do not claim one (TODO 20adda30).
     */
    static Map<String, Object> aiRequest(HostExecutionRequest request, String resolvedProviderId, boolean effortApplies) {
        Map<String, Object> params = StepAiMetadata.aiRequest(resolvedProviderId, request.model(),
                effortApplies ? request.effort() : null);
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
}
