package com.automation.service.live;

import java.util.Locale;

/** What a streamed live-log line represents; the frontend styles lines by it. */
public enum LiveLineKind {
    /** Raw output line (shell, pi, codex, non-JSON claude output). */
    LINE,
    /** Text the model wrote between tool calls (truncated). */
    TEXT,
    /** A tool call, e.g. "Bash: mvn test". */
    TOOL,
    /** Size of a tool result. */
    TOOL_RESULT,
    /** A tool result flagged as error. */
    TOOL_ERROR,
    /** Final summary of an AI call (turns, cost, duration). */
    RESULT,
    /** Session metadata such as model and tool count. */
    SYSTEM;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
