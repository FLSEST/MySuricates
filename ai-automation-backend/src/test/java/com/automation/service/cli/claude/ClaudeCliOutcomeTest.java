package com.automation.service.cli.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Exit code 0 is not success when the result line reports an error (c6d1a872). */
class ClaudeCliOutcomeTest {

    private static ClaudeCliOutcome outcome(String ndjson, int exitCode) {
        return ClaudeCliOutcome.of(ndjson, exitCode, ClaudeOutputParser.parseStreamJson(ndjson));
    }

    @Test
    void exitZeroWithIsErrorTrue_failsWithSubtypeAndCliMessage() {
        String ndjson = """
                {"type":"system","subtype":"init","session_id":"s"}
                {"type":"result","subtype":"success","is_error":true,"result":"API Error: 529 Overloaded","num_turns":1}
                """;

        ClaudeCliOutcome outcome = outcome(ndjson, 0);

        assertThat(outcome.exitCode()).isEqualTo(ClaudeCliOutcome.REPORTED_ERROR_EXIT_CODE).isNotZero();
        assertThat(outcome.text())
                .startsWith("Claude CLI reported an error (success) after 1 turn")
                .endsWith(": API Error: 529 Overloaded")
                .doesNotContain("\"type\"");
    }

    @Test
    void exitZeroWithErrorSubtypeAndNoResult_failsWithoutRawNdjson() {
        String ndjson = """
                {"type":"system","subtype":"init","session_id":"s"}
                {"type":"assistant","message":{"model":"claude-sonnet-4-6","content":[{"type":"tool_use","name":"Read","input":{}}]}}
                {"type":"result","subtype":"error_max_turns","is_error":false,"num_turns":3,"session_id":"s"}
                """;

        ClaudeCliOutcome outcome = outcome(ndjson, 0);

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.text()).isEqualTo("Claude CLI reported an error (error_max_turns) after 3 turns");
    }

    @Test
    void exitZeroWithIsErrorFalse_isUnchanged() {
        String ndjson = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\"}";

        ClaudeCliOutcome outcome = outcome(ndjson, 0);

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.text()).isEqualTo("done");
    }

    @Test
    void successResultLineWithoutResult_isAnEmptyAnswer_notTheRawStream() {
        String ndjson = """
                {"type":"system","subtype":"init","session_id":"s"}
                {"type":"result","subtype":"success","is_error":false,"num_turns":1}
                """;

        ClaudeCliOutcome outcome = outcome(ndjson, 0);

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.text()).isEmpty();
    }

    @Test
    void nonZeroExitWithErrorResult_keepsTheExitCode() {
        ClaudeCliOutcome outcome = outcome(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Claude AI usage limit reached\"}", 1);

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.text()).contains("Claude AI usage limit reached");
    }

    @Test
    void plainTextWithoutResultLine_keepsTheTextAndExitCode() {
        ClaudeCliOutcome outcome = outcome("Invalid API key · Please run /login", 1);

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.text()).isEqualTo("Invalid API key · Please run /login");
    }

    @Test
    void longCliMessage_isShortened() {
        String ndjson = "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"result\":\""
                + "x".repeat(2000) + "\"}";

        assertThat(outcome(ndjson, 0).text()).hasSizeLessThan(600).endsWith("...");
    }
}
