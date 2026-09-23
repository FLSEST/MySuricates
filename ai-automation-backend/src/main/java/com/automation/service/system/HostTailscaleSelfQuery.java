package com.automation.service.system;

import com.automation.util.LineListener;
import com.automation.util.ProcessDrain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link TailscaleSelfQuery} against the host's {@code tailscale} CLI. Stderr is discarded (a
 * version warning there would break the JSON), stdin is closed, and every call is capped at
 * {@value #TIMEOUT_SECONDS} seconds: the answer feeds an origin check on the request path.
 */
@Component
@Slf4j
public class HostTailscaleSelfQuery implements TailscaleSelfQuery {

    static final int TIMEOUT_SECONDS = 5;

    private final String binary;

    public HostTailscaleSelfQuery() {
        this("tailscale");
    }

    /** Test seam: another binary, same arguments. */
    HostTailscaleSelfQuery(String binary) {
        this.binary = binary;
    }

    @Override
    public Optional<String> ipv4() {
        return run("ip", "-4");
    }

    @Override
    public Optional<String> statusJson() {
        return run("status", "--json");
    }

    private Optional<String> run(String... args) {
        List<String> command = new ArrayList<>(List.of(binary));
        command.addAll(List.of(args));
        String label = String.join(" ", command);
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            String output = ProcessDrain.drain(process, TIMEOUT_SECONDS, label, LineListener.NONE);
            if (process.exitValue() != 0) {
                log.debug("{} exited with {}", label, process.exitValue());
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            // Not installed, timed out or unreadable: this instance simply has no tailnet address.
            log.debug("{} unavailable: {}", label, e.getMessage());
            return Optional.empty();
        }
    }
}
