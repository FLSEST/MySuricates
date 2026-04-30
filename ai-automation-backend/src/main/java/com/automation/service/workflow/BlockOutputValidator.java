package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Validates a block's output against rules declared in {@code block.config.validation}.
 * Used by WorkflowExecutor to decide whether a block needs to be retried.
 *
 * Supported rules (all optional, all combined with AND):
 *   - {@code contains}    — output must contain this substring
 *   - {@code max_length}  — output length must be ≤ this value
 *   - {@code min_length}  — output length must be ≥ this value
 *   - {@code retry_on_fail} (boolean, default false) and {@code max_retries} (int, default 1)
 *     are read by WorkflowExecutor, not by this validator.
 */
@Component
public class BlockOutputValidator {

    public record ValidationResult(boolean passed, String reason) {
        public static ValidationResult pass() {
            return new ValidationResult(true, null);
        }
        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validationConfig(BlockDefinition block) {
        Object raw = block == null || block.getConfig() == null ? null : block.getConfig().get("validation");
        return raw instanceof Map ? (Map<String, Object>) raw : null;
    }

    public ValidationResult validate(BlockDefinition block, String output) {
        Map<String, Object> config = validationConfig(block);
        if (config == null || config.isEmpty()) {
            return ValidationResult.pass();
        }
        String safeOutput = output == null ? "" : output;

        Object containsRaw = config.get("contains");
        if (containsRaw instanceof String contains && !contains.isEmpty()) {
            if (!safeOutput.contains(contains)) {
                return ValidationResult.fail("output missing required substring: " + contains);
            }
        }

        Object maxLengthRaw = config.get("max_length");
        if (maxLengthRaw instanceof Number maxLength && safeOutput.length() > maxLength.intValue()) {
            return ValidationResult.fail("output length " + safeOutput.length() + " exceeds max_length " + maxLength.intValue());
        }

        Object minLengthRaw = config.get("min_length");
        if (minLengthRaw instanceof Number minLength && safeOutput.length() < minLength.intValue()) {
            return ValidationResult.fail("output length " + safeOutput.length() + " below min_length " + minLength.intValue());
        }

        return ValidationResult.pass();
    }

    public boolean shouldRetry(BlockDefinition block) {
        Map<String, Object> config = validationConfig(block);
        if (config == null) return false;
        Object retry = config.get("retry_on_fail");
        return retry instanceof Boolean && (Boolean) retry;
    }

    public int maxRetries(BlockDefinition block) {
        Map<String, Object> config = validationConfig(block);
        if (config == null) return 0;
        Object max = config.get("max_retries");
        if (max instanceof Number n) return Math.max(0, n.intValue());
        return 1;
    }
}
