package com.automation.service.assistant;

import com.automation.config.HostClaudeConfig;
import com.automation.exception.WorkflowExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.SQLTransientConnectionException;

/**
 * Turns an exception from the assistant chat flow into a stable error code plus a user-facing
 * message (timeout, pool exhaustion, malformed tool JSON, provider misconfiguration, CLI exit
 * code, unknown).
 *
 * <p>Extracted from {@code AssistantService} (Audit-TODO 7b5bbb4a). Does <b>not</b> log or
 * broadcast — the caller decides what to do with the classification.
 */
@Component
@RequiredArgsConstructor
public class AssistantErrorClassifier {

    private final HostClaudeConfig hostClaudeConfig;

    public record ErrorKind(String code, String userMessage) {}

    public ErrorKind classify(Throwable e) {
        Throwable root = unwrap(e);
        String msg = root != null && root.getMessage() != null ? root.getMessage() : "";

        if (root instanceof IOException && msg.contains("timed out")) {
            int timeout = hostClaudeConfig.getAssistantChatTimeoutSeconds();
            return new ErrorKind("TIMEOUT",
                    "The response took longer than " + timeout + " seconds. " +
                    "Try again or phrase the question more briefly. " +
                    "You can increase the timeout in the settings.");
        }
        if (root instanceof SQLTransientConnectionException
                || msg.contains("Connection is not available")) {
            return new ErrorKind("POOL_EXHAUSTED",
                    "The system is currently busy (database pool in use). " +
                    "Wait a moment and try again.");
        }
        // JSON parse failures from tool-call extraction (e.g. Unicode full-width comma in the
        // model's tool-JSON). Previously these surfaced as "Provider nicht konfiguriert",
        // which sent users on a wild goose chase.
        if (root instanceof JsonProcessingException
                || msg.contains("Unexpected close marker")
                || msg.contains("was expecting comma")
                || msg.contains("Unexpected character")) {
            return new ErrorKind("TOOL_CALL_MALFORMED",
                    "My tool input was not valid (JSON error). " +
                    "Phrase the request more simply or without special characters and try again.");
        }
        if (msg.toLowerCase().contains("api key")) {
            return new ErrorKind("PROVIDER_UNAVAILABLE",
                    "The AI provider is not configured correctly. " +
                    "Check the settings (API key, model).");
        }
        if (msg.contains("Assistant CLI failed (exit ")) {
            // Surface the CLI exit code so debugging doesn't require digging through logs.
            int excerptStart = msg.indexOf("(exit ");
            int excerptEnd = Math.min(msg.length(), excerptStart + 60);
            String excerpt = excerptStart >= 0 ? msg.substring(excerptStart, excerptEnd) : "";
            return new ErrorKind("CLI_NON_ZERO_EXIT",
                    "The AI process ended with an error " + excerpt + ". " +
                    "Check the API configuration; details are in the server log.");
        }
        return new ErrorKind("UNKNOWN",
                "Sorry, I could not process your request right now. " +
                "Details are in the server log — please try again.");
    }

    private static Throwable unwrap(Throwable e) {
        Throwable current = e;
        for (int i = 0; i < 10 && current != null; i++) {
            if (current instanceof WorkflowExecutionException && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            if (current.getCause() != null && current.getCause() != current) {
                Throwable cause = current.getCause();
                if (cause instanceof IOException || cause instanceof SQLTransientConnectionException) {
                    return cause;
                }
            }
            return current;
        }
        return e;
    }
}
