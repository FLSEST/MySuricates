package com.automation.service.runoutput;

import java.util.regex.Pattern;

/**
 * The gate description a {@code checked_command} block makes up from its exit code when the command writes none
 * ({@code CheckedCommandGate}): {@code Command exit <n>}. It says that the command ran, not what it found — so it
 * never outranks a description with words of its own ({@code RunCompletionHandler}, TODO 2aa9ddd5). One place for
 * both sides, so the text the block writes and the text the run completion recognises cannot drift apart.
 */
public final class CommandExitDescription {

    private static final Pattern SYNTHETIC = Pattern.compile("Command exit -?\\d+");

    private CommandExitDescription() {
    }

    /** The description for this exit code. */
    public static String of(int exitCode) {
        return "Command exit " + exitCode;
    }

    /** Whether a parsed gate description is exactly one that {@link #of} writes. */
    public static boolean isSynthetic(String description) {
        return description != null && SYNTHETIC.matcher(description.strip()).matches();
    }
}
