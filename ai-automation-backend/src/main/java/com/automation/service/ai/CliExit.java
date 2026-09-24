package com.automation.service.ai;

import java.io.IOException;

/**
 * Exit code rule for reasoning calls: only a CLI that ends with 0 has answered. At a usage limit,
 * without login or after a crash the CLI prints its message and ends with another code. A reasoning
 * caller gets nothing but text back, so it would take that message for the answer — the summarizer
 * would store it as workflow memory, the context generator as project context, and every later run
 * would read it (9cc6c615). Codex throws on its own in {@code HostCodexService}; Claude and pi use this.
 */
final class CliExit {

    /** Characters of the CLI's message kept in the exception. */
    private static final int MESSAGE_CHARS = 300;

    private CliExit() {}

    /** {@code output} when the CLI ended with 0; otherwise an exception naming the exit code and the message. */
    static String answerOrThrow(String label, int exitCode, String output) throws IOException {
        if (exitCode == 0) {
            return output;
        }
        String message = output == null ? "" : output.strip();
        if (message.length() > MESSAGE_CHARS) {
            message = message.substring(0, MESSAGE_CHARS) + "...";
        }
        throw new IOException(label + " failed with exit code " + exitCode + ": " + message);
    }
}
