package com.automation.service.system;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.exception.TailscaleException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

@Service
@Slf4j
public class TailscaleService {

    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    /** Read per call: the module can be switched at runtime (automation.modules.ui-editable). */
    private final BooleanSupplier enabled;

    /**
     * This instance runs in a container — there is no supported Tailscale path there:
     * none of the shipped images carries the Tailscale CLI or a place to keep its state across
     * {@code compose down}. Detected the same way as {@link ContainerAccessReport} and
     * {@code AiProviderStatusService}, not a new heuristic.
     */
    private final boolean inContainer;

    /** Remote access is the {@code remote-access} module (formerly {@code automation.tailscale.enabled}). */
    @Autowired
    public TailscaleService(ModuleRegistry modules,
                             @Value("${automation.instance.container:}") String instanceContainer) {
        this(() -> modules.isEnabled(ModuleCatalog.REMOTE_ACCESS), System::getenv, instanceContainer);
    }

    TailscaleService(boolean enabled) {
        this(() -> enabled, System::getenv, "");
    }

    TailscaleService(ModuleRegistry modules) {
        this(() -> modules.isEnabled(ModuleCatalog.REMOTE_ACCESS), System::getenv, "");
    }

    TailscaleService(BooleanSupplier enabled, UnaryOperator<String> env, String instanceContainer) {
        this.enabled = enabled;
        this.inContainer = "container".equals(env.apply("MYSURICATES_RUNTIME"))
                || (instanceContainer != null && !instanceContainer.isBlank());
    }

    public RemoteAccessResponse enable() {
        ensureEnabled();
        ensureNotInContainer();

        int exitCode = executeCommand("tailscale", "up", "--accept-routes");
        if (exitCode != 0) {
            throw new TailscaleException("Failed to enable Tailscale (exit code: " + exitCode + ")");
        }

        String ip = getCommandOutput("tailscale", "ip", "-4");
        String hostname = getCommandOutput("hostname");

        log.info("Tailscale enabled - IP: {}, hostname: {}", ip, hostname);
        return new RemoteAccessResponse(true, ip, hostname, false);
    }

    public void disable() {
        ensureEnabled();
        ensureNotInContainer();

        int exitCode = executeCommand("tailscale", "down");
        if (exitCode != 0) {
            throw new TailscaleException("Failed to disable Tailscale (exit code: " + exitCode + ")");
        }

        log.info("Tailscale disabled");
    }

    public RemoteAccessResponse getStatus() {
        if (!enabled.getAsBoolean()) {
            return new RemoteAccessResponse(false, null, null, inContainer);
        }
        if (inContainer) {
            return new RemoteAccessResponse(false, null, null, true);
        }

        try {
            int exitCode = executeCommand("tailscale", "status");
            if (exitCode == 0) {
                String ip = getCommandOutput("tailscale", "ip", "-4");
                String hostname = getCommandOutput("hostname");
                return new RemoteAccessResponse(true, ip, hostname, false);
            }
        } catch (Exception e) {
            log.debug("Tailscale not running: {}", e.getMessage());
        }

        return new RemoteAccessResponse(false, null, null, false);
    }

    public boolean isConfigEnabled() {
        return enabled.getAsBoolean();
    }

    private void ensureEnabled() {
        if (!enabled.getAsBoolean()) {
            throw new IllegalStateException("Tailscale integration is disabled in configuration");
        }
    }

    private void ensureNotInContainer() {
        if (inContainer) {
            throw new TailscaleException(
                    "Remote access has no supported path in a container — install Tailscale on the "
                    + "host machine instead and run `tailscale serve --bg --https=443 http://127.0.0.1:<port>`");
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
