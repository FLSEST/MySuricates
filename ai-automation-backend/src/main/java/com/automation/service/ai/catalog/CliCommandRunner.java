package com.automation.service.ai.catalog;

import java.io.IOException;
import java.util.List;

/**
 * Runs a short host command and returns its combined stdout/stderr.
 *
 * <p>Abstracted so catalog sources can be unit-tested with canned output instead of a real CLI.
 */
@FunctionalInterface
public interface CliCommandRunner {

    /**
     * @param command        argv, e.g. {@code ["pi", "--offline", "--list-models"]}
     * @param timeoutSeconds wall-clock limit for the process
     * @return trimmed output
     * @throws IOException if the binary is missing, the process times out, or exits non-zero
     */
    String run(List<String> command, int timeoutSeconds) throws IOException;
}
