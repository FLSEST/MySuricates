package com.automation.service.cli.claude;

/**
 * Verdict over one {@code claude -p --output-format stream-json} call: the text a caller gets and the
 * exit code it judges success by.
 *
 * <p>The exit code alone is not enough (c6d1a872): at an API error the CLI reports
 * {@code is_error: true}, at the turn limit the subtype {@code error_max_turns} — and still ends with
 * exit code 0. Every caller (block executors, reasoning via {@code CliExit}, assistant, tagging) only
 * checks {@code exitCode != 0}, so such a call counts as failed here with {@link #REPORTED_ERROR_EXIT_CODE}
 * instead of 0. Its text is a readable message naming the subtype, never the raw NDJSON stream, which
 * used to land in the step output when the error result carried no {@code result} text.
 */
public record ClaudeCliOutcome(String text, int exitCode) {

    /** Exit code given to a call that ended with 0 although its result line reported an error. */
    public static final int REPORTED_ERROR_EXIT_CODE = 1;

    /** Characters of the CLI's own error text kept in the message. */
    private static final int MESSAGE_CHARS = 500;

    public static ClaudeCliOutcome of(String rawOutput, int exitCode, ClaudeJsonResponse parsed) {
        if (parsed.failed()) {
            return new ClaudeCliOutcome(errorMessage(parsed), exitCode != 0 ? exitCode : REPORTED_ERROR_EXIT_CODE);
        }
        if (parsed.result() != null) {
            return new ClaudeCliOutcome(parsed.result(), exitCode);
        }
        // A result line without text is an empty answer; without any result line the CLI printed plain
        // text (crash, missing login) — that text is the only message there is.
        return new ClaudeCliOutcome(parsed.hasResultLine() ? "" : rawOutput, exitCode);
    }

    private static String errorMessage(ClaudeJsonResponse parsed) {
        StringBuilder sb = new StringBuilder("Claude CLI reported an error");
        if (parsed.subtype() != null && !parsed.subtype().isBlank()) {
            sb.append(" (").append(parsed.subtype()).append(')');
        }
        if (parsed.numTurns() != null) {
            sb.append(" after ").append(parsed.numTurns()).append(parsed.numTurns() == 1 ? " turn" : " turns");
        }
        String detail = parsed.result() == null ? "" : parsed.result().strip();
        if (!detail.isEmpty()) {
            sb.append(": ").append(detail.length() > MESSAGE_CHARS ? detail.substring(0, MESSAGE_CHARS) + "..." : detail);
        }
        return sb.toString();
    }
}
