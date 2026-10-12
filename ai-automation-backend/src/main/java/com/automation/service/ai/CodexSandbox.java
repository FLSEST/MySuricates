package com.automation.service.ai;

/**
 * Write access of a Codex call. An enum instead of a {@code boolean readOnly} next to {@code boolean priority}:
 * swapping the two literals in {@code execute(…, true, model, false)} silently made Codex write.
 */
public enum CodexSandbox {
    /** {@code --sandbox read-only}: analysis without file writes. */
    READ_ONLY,
    /** Codex may write in its working directory. */
    WORKSPACE_WRITE;

    public static CodexSandbox of(boolean readOnly) {
        return readOnly ? READ_ONLY : WORKSPACE_WRITE;
    }
}
