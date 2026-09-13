package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.service.ai.ReasoningEffort;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the AI settings of a block with the precedence
 * <b>block config &gt; workflow level &gt; provider/system default</b>.
 *
 * <p>Config keys: {@value #PROVIDER_KEY}, {@value #MODEL_KEY}, {@value #EFFORT_KEY}. Only
 * AI-capable executors consult these keys — the {@code integration} block, for example, uses
 * {@code provider} for its integration provider id and never routes through here.
 *
 * <p>A {@code null} result means "no explicit choice": the provider registry then falls back
 * to the system default provider, and the provider CLI to its default model / effort.
 */
@Slf4j
public final class BlockAiSettings {

    public static final String PROVIDER_KEY = "provider";
    public static final String MODEL_KEY = "model";
    public static final String EFFORT_KEY = "effort";

    private BlockAiSettings() {}

    /** Block {@code provider} if set, otherwise the workflow provider (may be null = system default). */
    public static String providerFor(BlockDefinition block, String workflowProvider) {
        String blockProvider = configString(block, PROVIDER_KEY);
        return blockProvider != null ? blockProvider : workflowProvider;
    }

    /**
     * Block {@code model} if set, otherwise the workflow model — unless the block switches to a
     * provider other than the workflow's: model ids are provider-specific ({@code opus} vs.
     * {@code anthropic/opus}), so a workflow model is never carried over to a different provider.
     */
    public static String modelFor(BlockDefinition block, String workflowProvider, String workflowModel) {
        String blockModel = configString(block, MODEL_KEY);
        if (blockModel != null) {
            return blockModel;
        }
        String blockProvider = configString(block, PROVIDER_KEY);
        if (blockProvider != null && !blockProvider.equalsIgnoreCase(workflowProvider)) {
            return null;
        }
        return workflowModel;
    }

    /**
     * Block {@code effort} if valid, otherwise the workflow effort. Invalid block values are
     * ignored with a warning, not escalated — the run proceeds with the workflow effort.
     */
    public static String effortFor(BlockDefinition block, String workflowEffort) {
        String raw = configString(block, EFFORT_KEY);
        if (raw != null) {
            var parsed = ReasoningEffort.parse(raw);
            if (parsed.isPresent()) {
                return parsed.get().cliValue();
            }
            log.warn("Ignoring invalid block effort '{}' (valid: low, medium, high, xhigh)", raw);
        }
        return workflowEffort;
    }

    private static String configString(BlockDefinition block, String key) {
        if (block == null || block.getConfig() == null) return null;
        return block.getConfig().get(key) instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
