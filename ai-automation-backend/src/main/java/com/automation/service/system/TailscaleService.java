package com.automation.service.system;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.exception.TailscaleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TailscaleService {

    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    private final boolean enabled;

    public TailscaleService(
            @Value("${automation.tailscale.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public RemoteAccessResponse enable() {
        ensureEnabled();

        int exitCode = executeCommand("sudo", "tailscale", "up", "--accept-routes");
        if (exitCode != 0) {
            throw new TailscaleException("Failed to enable Tailscale (exit code: " + exitCode + ")");
        }

        String ip = getCommandOutput("tailscale", "ip", "-4");
        String hostname = getCommandOutput("hostname");

        log.info("Tailscale enabled - IP: {}, hostname: {}", ip, hostname);
        return new RemoteAccessResponse(true, ip, hostname);
    }

    public void disable() {
        ensureEnabled();

        int exitCode = executeCommand("sudo", "tailscale", "down");
        if (exitCode != 0) {
            throw new TailscaleException("Failed to disable Tailscale (exit code: " + exitCode + ")");
        }

        log.info("Tailscale disabled");
    }

    public RemoteAccessResponse getStatus() {
        if (!enabled) {
            return new RemoteAccessResponse(false, null, null);
        }

        try {
            int exitCode = executeCommand("tailscale", "status");
            if (exitCode == 0) {
                String ip = getCommandOutput("tailscale", "ip", "-4");
                String hostname = getCommandOutput("hostname");
                return new RemoteAccessResponse(true, ip, hostname);
            }
        } catch (Exception e) {
            log.debug("Tailscale not running: {}", e.getMessage());
        }

        return new RemoteAccessResponse(false, null, null);
    }

    public boolean isConfigEnabled() {
        return enabled;
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Tailscale integration is disabled in configuration");
        }
    }

    private Process startProcess(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TailscaleException("Command timed out after " + PROCESS_TIMEOUT_SECONDS + "s: "
                    + String.join(" ", command));
        }

        return process;
    }

    private int executeCommand(String... command) {
        try {
            Process process = startProcess(command);
            return process.exitValue();
        } catch (IOException e) {
            throw new TailscaleException("Failed to execute command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TailscaleException("Command interrupted: " + String.join(" ", command), e);
        }
    }

    private String getCommandOutput(String... command) {
        Process process = null;
        try {
            process = startProcess(command);
            if (process.exitValue() != 0) {
                return null;
            }
            try (InputStream is = process.getInputStream()) {
                return new String(is.readAllBytes()).trim();
            }
        } catch (Exception e) {
            log.debug("Failed to get output from command: {}", String.join(" ", command));
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
