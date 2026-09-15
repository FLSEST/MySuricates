package com.automation.executor;

import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code execution_mode} of foreach, while, conditional, retry and switch blocks (and blocks nested
 * in switch cases): {@code "ai"} (default — the provider comes from {@code provider} on the block
 * or workflow) or {@code "shell"}. {@code "claude"} stays accepted as a legacy alias for
 * {@code "ai"}. Any other value is rejected: a typo like {@code "shel"} must not silently run as AI.
 */
public final class ExecutionMode {

    public static final String KEY = "execution_mode";
    public static final String AI = "ai";
    public static final String SHELL = "shell";
    /** Legacy name of {@link #AI} from before the provider was selectable. */
    public static final String LEGACY_CLAUDE = "claude";

    private static final Set<String> VALID = Set.of(AI, SHELL, LEGACY_CLAUDE);

    private ExecutionMode() {
    }

    /**
     * @return true for {@code "shell"}; false for {@code "ai"}, the legacy {@code "claude"} or no value
     * @throws IllegalArgumentException for any other value
     */
    public static boolean isShell(Map<String, Object> config) {
        Object raw = config == null ? null : config.get(KEY);
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof String value) || !VALID.contains(value)) {
            throw new IllegalArgumentException(invalidMessage(raw));
        }
        return SHELL.equals(value);
    }

    /**
     * Validates {@code execution_mode} in every block, including blocks nested in switch
     * {@code cases[].blocks} and {@code default}.
     *
     * @throws IllegalArgumentException naming the first block with an unknown value
     */
    public static void rejectUnknown(List<BlockDefinition> blocks) {
        if (blocks == null) {
            return;
        }
        for (BlockDefinition block : blocks) {
            if (block != null) {
                checkConfig(block.getName() != null ? block.getName() : block.getType(), block.getConfig());
            }
        }
    }

    private static void checkConfig(String blockLabel, Map<String, Object> config) {
        if (config == null) {
            return;
        }
        Object raw = config.get(KEY);
        if (raw != null && !(raw instanceof String value && VALID.contains(value))) {
            throw new IllegalArgumentException("Block '" + blockLabel + "': " + invalidMessage(raw));
        }
        if (config.get("cases") instanceof List<?> cases) {
            for (Object entry : cases) {
                if (entry instanceof Map<?, ?> caseEntry) {
                    checkNested(caseEntry.get("blocks"));
                }
            }
        }
        checkNested(config.get("default"));
    }

    @SuppressWarnings("unchecked")
    private static void checkNested(Object nestedBlocks) {
        if (!(nestedBlocks instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> nested && nested.get("config") instanceof Map<?, ?> nestedConfig) {
                Object label = nested.get("name") != null ? nested.get("name") : nested.get("type");
                checkConfig(String.valueOf(label), (Map<String, Object>) nestedConfig);
            }
        }
    }

    private static String invalidMessage(Object raw) {
        return "unknown " + KEY + " '" + raw + "' — use \"" + AI + "\" (provider from the block or workflow) or \""
                + SHELL + "\"";
    }
}
