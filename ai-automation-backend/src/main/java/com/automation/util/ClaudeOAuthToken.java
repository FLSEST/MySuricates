package com.automation.util;

/**
 * Whether a value of {@code CLAUDE_CODE_OAUTH_TOKEN} can be a token from {@code claude setup-token} at
 * all: at least 40 characters, no whitespace, no angle brackets. That catches what a copied instruction
 * leaves behind — a placeholder like {@code <token>}, an ellipsis, a sentence — which would otherwise
 * count as a login until the first workflow fails on it. No fixed prefix: the token format belongs to
 * Anthropic.
 *
 * <p>Same rule as {@code claude_token_plausible} in scripts/lib/host-mounts.sh, which decides whether a
 * container gets the token at all.
 */
public final class ClaudeOAuthToken {

    public static final String VARIABLE = "CLAUDE_CODE_OAUTH_TOKEN";
    static final int MIN_LENGTH = 40;

    private ClaudeOAuthToken() {
    }

    public static boolean isPlausible(String value) {
        return value != null
                && value.length() >= MIN_LENGTH
                && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '<' || c == '>');
    }
}
