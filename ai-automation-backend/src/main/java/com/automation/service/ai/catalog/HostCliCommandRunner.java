package com.automation.service.ai.catalog;

import com.automation.util.HostProcessUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * {@link CliCommandRunner} backed by {@link ProcessBuilder}; stderr is merged into stdout so a
 * failing CLI's message ends up in the catalog's {@code reason}.
 */
@Component
@RequiredArgsConstructor
public class HostCliCommandRunner implements CliCommandRunner {

    private final HostProcessUtils hostProcessUtils;

    @Override
    public String run(List<String> command, int timeoutSeconds) throws IOException {
        String label = String.join(" ", command);
        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = hostProcessUtils.drain(proc, timeoutSeconds, label);
        int exit = proc.exitValue();
        if (exit != 0) {
            throw new IOException(label + " exited with " + exit + ": " + firstLine(output));
        }
        return output;
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        String line = output.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
