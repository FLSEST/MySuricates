package com.automation.service.staging;

import com.automation.config.DataHome;
import com.automation.config.StagingConfig;
import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.StagingStatusResponse;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedStateException;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class StagingService {

    private final StagingConfig config;
    private final InstanceSyncHelper syncHelper;
    private final InstanceHttpClient httpClient;
    private final SyncPreviewService previewService;
    private final ApiKeyService apiKeyService;
    private final DockerContainerManager dockerManager;
    private final StagingComposeCommand compose;

    public enum PromotePhase {
        IDLE, STOPPING, BUILDING_BACKEND, BUILDING_FRONTEND, BUILDING_IMAGE, STARTING, ERROR
    }

    private record PromoteState(PromotePhase phase, int step, String error) {
        private static final PromoteState IDLE = new PromoteState(PromotePhase.IDLE, 0, null);
        static PromoteState idle() { return IDLE; }
        static PromoteState of(PromotePhase phase, int step) { return new PromoteState(phase, step, null); }
        static PromoteState error(String msg) { return new PromoteState(PromotePhase.ERROR, 0, msg); }
    }

    private final AtomicReference<PromoteState> promoteState = new AtomicReference<>(PromoteState.idle());
    private volatile Process currentProcess;
    private volatile boolean cancelRequested;

    @EventListener(ApplicationReadyEvent.class)
    void autoStartStable() {
        if (!config.isAutoStart()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Path composeFile = resolveComposeFile();
                Path stableFrontend = composeFile.getParent().resolve(config.getStableFrontendDir());
                if (!Files.isDirectory(stableFrontend)) {
                    log.debug("No stable-frontend/ found — skipping Stable auto-start.");
                    return;
                }
                if (isContainerRunning()) {
                    log.debug("Stable container already running.");
                    return;
                }
                log.info("Auto-starting Stable environment...");
                start();
            } catch (Exception e) {
                log.warn("Stable auto-start failed: {}", e.getMessage());
            }
        });
    }

    public StagingStatusResponse getStatus() {
        boolean running = isContainerRunning();
        boolean healthy = running && isHealthy();
        String lastSnapshot = findLastSnapshot();
        PromoteState state = promoteState.get();

        String stableVersion = null;
        String stableBuildTimestamp = null;
        if (healthy) {
            try {
                String stableBaseUrl = deriveStableBaseUrl();
                JsonNode info = httpClient.httpGet(stableBaseUrl + "/system/info");
                if (info != null) {
                    stableVersion = info.path("version").asText(null);
                    stableBuildTimestamp = info.path("buildTimestamp").asText(null);
                }
            } catch (Exception e) {
                log.debug("Failed to fetch stable system info: {}", e.getMessage());
            }
        }

        return new StagingStatusResponse(
                running,
                healthy,
                state.phase().name(),
                state.step(),
                5,
                lastSnapshot,
                state.error(),
                stableVersion,
                stableBuildTimestamp
        );
    }

    public void start() {
        Path composeFile = resolveComposeFile();
        Path stableFrontend = composeFile.getParent().resolve(config.getStableFrontendDir());
        if (!Files.isDirectory(stableFrontend)) {
            throw new IllegalStateException("No stable-frontend/ found. Run promote first.");
        }
        log.info("Starting stable environment...");
        runCommand(composeFile.getParent(), apiKeyEnv(), compose.command(resolveBackendDir(), composeFile, "stable", "up", "-d"));
        waitForHealth(60);
        log.info("Stable environment started.");
    }

    public void stop() {
        Path composeFile = resolveComposeFile();
        log.info("Stopping stable environment...");
        runCommand(composeFile.getParent(), compose.command(resolveBackendDir(), composeFile, "stable", "down"));
        log.info("Stable environment stopped.");
    }

    public void promote() {
        if (!promoteState.compareAndSet(PromoteState.idle(), PromoteState.of(PromotePhase.STOPPING, 1))) {
            PromoteState current = promoteState.get();
            if (current.phase() == PromotePhase.ERROR) {
                promoteState.set(PromoteState.of(PromotePhase.STOPPING, 1));
            } else {
                throw new IllegalStateException("Promote already in progress: " + current.phase());
            }
        }
        cancelRequested = false;

        CompletableFuture.runAsync(() -> {
            try {
                Path composeFile = resolveComposeFile();
                Path backendDir = resolveBackendDir();
                Path frontendDir = resolveFrontendDir();
                Path stableFrontendDir = backendDir.resolve(config.getStableFrontendDir());

                // Step 1: Stop
                checkCancelled();
                promoteState.set(PromoteState.of(PromotePhase.STOPPING, 1));
                try {
                    runCommand(backendDir, compose.command(backendDir, composeFile, "stable", "down"));
                } catch (Exception e) {
                    log.debug("Stop failed (container may not be running): {}", e.getMessage());
                }

                // Step 2: Build backend
                checkCancelled();
                promoteState.set(PromoteState.of(PromotePhase.BUILDING_BACKEND, 2));
                runCommand(backendDir, "mvn", "clean", "package", "-DskipTests", "-q");

                // Step 3: Build frontend
                checkCancelled();
                promoteState.set(PromoteState.of(PromotePhase.BUILDING_FRONTEND, 3));
                runCommand(frontendDir, "npm", "run", "build");
                // Copy frontend dist to stable-frontend
                if (Files.isDirectory(stableFrontendDir)) {
                    deleteRecursive(stableFrontendDir);
                }
                Path distDir = frontendDir.resolve("dist");
                copyDirectory(distDir, stableFrontendDir);

                // Step 4: Build Docker image
                checkCancelled();
                promoteState.set(PromoteState.of(PromotePhase.BUILDING_IMAGE, 4));
                runCommand(backendDir, compose.command(backendDir, composeFile, "stable", "build"));

                // Step 5: Start
                checkCancelled();
                promoteState.set(PromoteState.of(PromotePhase.STARTING, 5));
                runCommand(backendDir, apiKeyEnv(), compose.command(backendDir, composeFile, "stable", "up", "-d"));
                waitForHealth(60);

                // Tag the commit
                try {
                    Path projectRoot = backendDir.getParent();
                    String tag = "stable-" + java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                    runCommand(projectRoot, "git", "tag", tag);
                    log.info("Tagged: {}", tag);
                } catch (Exception e) {
                    log.debug("Git tag failed (dirty tree?): {}", e.getMessage());
                }

                promoteState.set(PromoteState.idle());
                log.info("Promote completed successfully.");
            } catch (CancelException e) {
                promoteState.set(PromoteState.idle());
                log.info("Promote cancelled by user.");
            } catch (Exception e) {
                log.error("Promote failed: {}", e.getMessage(), e);
                promoteState.set(PromoteState.error(e.getMessage()));
            } finally {
                currentProcess = null;
            }
        });
    }

    public void cancelPromote() {
        cancelRequested = true;
        Process p = currentProcess;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    public SyncPreview syncPreview() {
        if (!isHealthy()) throw new LocalizedStateException(ErrorMessage.STABLE_INSTANCE_NOT_RUNNING);
        return previewService.syncPreview(deriveStableBaseUrl(), readContainerApiKey(config.getContainerName()));
    }

    public SyncResult syncDefinitions() {
        return syncDefinitions(null);
    }

    public SyncResult syncDefinitions(SyncDefinitionsRequest request) {
        if (!isHealthy()) throw new LocalizedStateException(ErrorMessage.STABLE_INSTANCE_NOT_RUNNING);
        String stableApiKey = readContainerApiKey(config.getContainerName());
        return syncHelper.syncDefinitionsTo(deriveStableBaseUrl(), stableApiKey, request);
    }

    // ── Stable API URL helper ─────────────────────────────

    String deriveStableBaseUrl() {
        String healthUrl = config.getHealthUrl();
        int apiIdx = healthUrl.indexOf("/api/v1");
        if (apiIdx > 0) {
            return healthUrl.substring(0, apiIdx) + "/api/v1";
        }
        return "http://localhost:9091/api/v1";
    }

    // ── Container API key helper ──────────────────────────

    /**
     * The container's API key from the key file in its data folder, else the key it was started with
     * (this instance's, {@link #apiKeyEnv}) — never from its database ({@link DockerContainerManager#readApiKey}).
     * Throws a {@code LocalizedStateException} that says what is missing.
     */
    String readContainerApiKey(String containerName) {
        return dockerManager.readApiKey(containerName, apiKeyService.getEffectiveApiKey());
    }

    // ── Internal helpers ──────────────────────────────────

    boolean isContainerRunning() {
        return dockerManager.isRunning(config.getContainerName());
    }

    public boolean isHealthy() {
        return dockerManager.isHealthy(config.getHealthUrl());
    }

    String findLastSnapshot() {
        try {
            Path projectRoot = resolveBackendDir().getParent();
            ProcessBuilder pb = new ProcessBuilder("git", "tag", "-l", "stable-*", "--sort=-version:refname");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String first = reader.readLine();
                p.waitFor();
                return first != null && !first.isBlank() ? first.trim() : null;
            }
        } catch (Exception e) {
            log.debug("Failed to find last snapshot tag: {}", e.getMessage());
            return null;
        }
    }

    private void runCommand(Path workDir, String... command) {
        runCommand(workDir, Map.of(), command);
    }

    private void runCommand(Path workDir, Map<String, String> extraEnv, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().putAll(extraEnv);
            DataHome.export(pb.environment());
            Process p = pb.start();
            currentProcess = p;

            // Drain output to prevent blocking
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[staging] {}", line);
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Command failed (exit " + exitCode + "): " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Command interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Command execution failed: " + e.getMessage(), e);
        }
    }

    private Map<String, String> apiKeyEnv() {
        return Map.of("AUTOMATION_API_KEY", apiKeyService.getEffectiveApiKey());
    }

    private void waitForHealth(int maxWaitSeconds) {
        int waited = 0;
        while (waited < maxWaitSeconds) {
            if (isHealthy()) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            waited += 2;
        }
        log.warn("Health check did not pass within {}s", maxWaitSeconds);
    }

    private Path resolveComposeFile() {
        Path backendDir = resolveBackendDir();
        return backendDir.resolve(config.getComposeFile());
    }

    Path resolveBackendDir() {
        if (config.getBackendDir() != null && !config.getBackendDir().isBlank()) {
            return Path.of(config.getBackendDir());
        }
        // Auto-detect: assume running from ai-automation-backend/
        Path cwd = Path.of(System.getProperty("user.dir"));
        if (cwd.getFileName().toString().equals("ai-automation-backend")) {
            return cwd;
        }
        Path backendChild = cwd.resolve("ai-automation-backend");
        if (Files.isDirectory(backendChild)) {
            return backendChild;
        }
        return cwd;
    }

    Path resolveFrontendDir() {
        if (config.getFrontendDir() != null && !config.getFrontendDir().isBlank()) {
            return Path.of(config.getFrontendDir());
        }
        Path backendDir = resolveBackendDir();
        return backendDir.getParent().resolve("ai-automation-frontend");
    }

    private void checkCancelled() {
        if (cancelRequested) {
            throw new CancelException();
        }
    }

    private void deleteRecursive(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to walk directory for deletion {}: {}", path, e.getMessage());
        }
    }

    private void copyDirectory(Path source, Path target) {
        try (var walk = Files.walk(source)) {
            walk.forEach(s -> {
                Path dest = target.resolve(source.relativize(s));
                try {
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(s, dest);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to copy " + s + " -> " + dest, e);
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy directory", e);
        }
    }

    private static class CancelException extends RuntimeException {}
}
