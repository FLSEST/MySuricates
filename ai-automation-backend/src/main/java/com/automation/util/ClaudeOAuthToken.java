package com.automation.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    /**
     * The value compose would read from an env file such as ~/.config/mysuricates/ai.env: the last
     * {@code CLAUDE_CODE_OAUTH_TOKEN=} line wins, one pair of surrounding quotes comes off (as
     * {@code claude_token_from_file} in host-mounts.sh). Empty when the file or the line is missing —
     * plausibility is the caller's check.
     */
    public static Optional<String> fromEnvFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return Optional.empty();
        }
        String prefix = VARIABLE + "=";
        return lines.stream()
                .filter(l -> l.startsWith(prefix))
                .reduce((first, second) -> second)
                .map(l -> unquote(l.substring(prefix.length())));
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
