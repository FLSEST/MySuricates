package com.automation.service.tray;

import com.automation.config.StagingConfig;
import com.automation.config.TrayConfig;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.staging.DockerContainerManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Detects the MySuricates instances that deserve their own tray status light. */
@Component
@RequiredArgsConstructor
class TrayInstanceMonitor {

    enum InstanceKind {
        DEV("Dev"), TEST("Test"), STABLE("Stable");

        private final String label;

        InstanceKind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum LightColor { GREEN, YELLOW, RED, GRAY }

    record InstanceLight(InstanceKind kind, LightColor color) { }

    private final TrayConfig trayConfig;
    private final StagingConfig stagingConfig;
    private final ModuleRegistry modules;
    private final DockerContainerManager docker;

    private int consecutiveDevFailures;

    synchronized List<InstanceLight> collect() {
        List<InstanceLight> lights = new ArrayList<>();
        lights.add(devLight());
        if (modules.isEnabled(ModuleCatalog.STAGING)) {
            optionalLight(InstanceKind.TEST, stagingConfig.getTestHealthUrl(),
                    stagingConfig.getTestContainerName()).ifPresent(lights::add);
            optionalLight(InstanceKind.STABLE, stagingConfig.getHealthUrl(),
                    stagingConfig.getContainerName()).ifPresent(lights::add);
        }
        return List.copyOf(lights);
    }

    private InstanceLight devLight() {
        if (endpointPortOpen(trayConfig.getWebUiUrl())) {
            consecutiveDevFailures = 0;
            return new InstanceLight(InstanceKind.DEV, LightColor.GREEN);
        }
        consecutiveDevFailures++;
        return new InstanceLight(InstanceKind.DEV,
                consecutiveDevFailures < 3 ? LightColor.YELLOW : LightColor.RED);
    }

    private Optional<InstanceLight> optionalLight(InstanceKind kind, String healthUrl, String containerName) {
        if (docker.isHealthy(healthUrl)) {
            return Optional.of(new InstanceLight(kind, LightColor.GREEN));
        }
        if (!docker.containerExists(containerName)) {
            return Optional.empty();
        }
        LightColor color = docker.isRunning(containerName) ? LightColor.YELLOW : LightColor.RED;
        return Optional.of(new InstanceLight(kind, color));
    }

    /** A TCP probe intentionally accepts local self-signed HTTPS certificates. */
    boolean endpointPortOpen(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 3000);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
