package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.TestingStatusResponse;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestingService {

    private final StagingConfig config;
    private final InstanceSyncHelper syncHelper;
    private final WorkflowRepository workflowRepository;

    @EventListener(ApplicationReadyEvent.class)
    void autoStartTest() {
        if (!config.isTestAutoStart()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Path composeFile = resolveTestComposeFile();
                Path stableFrontend = composeFile.getParent().resolve(config.getStableFrontendDir());
                if (!Files.isDirectory(stableFrontend)) {
                    log.debug("No stable-frontend/ found — skipping Test auto-start.");
                    return;
                }
                if (isContainerRunning()) {
                    log.debug("Test container already running.");
                    return;
                }
                log.info("Auto-starting Test environment...");
                start();
                autoSyncOnBoot();
            } catch (Exception e) {
                log.debug("Test auto-start skipped: {}", e.getMessage());
            }
        });
    }

    public TestingStatusResponse getStatus() {
        boolean running = isContainerRunning();
        boolean healthy = running && isHealthy();

        String testVersion = null;
        String testBuildTimestamp = null;
        if (healthy) {
            try {
                String baseUrl = deriveTestBaseUrl();
                JsonNode info = syncHelper.httpGet(baseUrl + "/system/info");
                if (info != null) {
                    testVersion = info.path("version").asText(null);
                    testBuildTimestamp = info.path("buildTimestamp").asText(null);
                }
            } catch (Exception e) {
                log.debug("Failed to fetch test system info: {}", e.getMessage());
            }
        }

        return new TestingStatusResponse(running, healthy, testVersion, testBuildTimestamp);
    }

    public void start() {
        Path composeFile = resolveTestComposeFile();
        Path stableFrontend = composeFile.getParent().resolve(config.getStableFrontendDir());
        if (!Files.isDirectory(stableFrontend)) {
            throw new IllegalStateException("No stable-frontend/ found. Run promote first.");
        }
        log.info("Starting test environment...");
        runCommand(composeFile.getParent(), "docker", "compose", "-f", composeFile.toString(), "up", "-d");
        waitForHealth(60);
        log.info("Test environment started.");
    }

    public void stop() {
        Path composeFile = resolveTestComposeFile();
        log.info("Stopping test environment...");
        runCommand(composeFile.getParent(), "docker", "compose", "-f", composeFile.toString(), "down");
        log.info("Test environment stopped.");
    }

    public void resetDatabase() {
        Path composeFile = resolveTestComposeFile();
        log.info("Resetting test database...");

        // Stop container
        try {
            runCommand(composeFile.getParent(), "docker", "compose", "-f", composeFile.toString(), "down", "-v");
        } catch (Exception e) {
            log.debug("Stop/remove volume failed (may not exist): {}", e.getMessage());
        }

        // Start fresh — Flyway + Seeds will run on boot
        start();

        // Auto-sync definitions after reset
        autoSyncOnBoot();

        log.info("Test database reset complete.");
    }

    public SyncPreview syncPreview() {
        if (!isHealthy()) {
            throw new IllegalStateException("Test instance is not running or not healthy. Start it first.");
        }
        return syncHelper.syncPreview(deriveTestBaseUrl());
    }

    public SyncResult syncDefinitions() {
        return syncDefinitions(null);
    }

    public SyncResult syncDefinitions(SyncDefinitionsRequest request) {
        if (!isHealthy()) {
            throw new IllegalStateException("Test instance is not running or not healthy. Start it first.");
        }
        return syncHelper.syncDefinitionsTo(deriveTestBaseUrl(), request);
    }

    public void reportTestResult(String workflowName, String projectName, String status) {
        Optional<Workflow> match = workflowRepository.findAll().stream()
                .filter(w -> w.getName().equals(workflowName))
                .filter(w -> {
                    String wProjectName = w.getProject() != null ? w.getProject().getName() : "";
                    return wProjectName.equals(projectName != null ? projectName : "");
                })
                .findFirst();

        if (match.isPresent()) {
            Workflow workflow = match.get();
            workflow.setTestStatus(status);
            workflow.setLastTestedAt(Instant.now());
            workflowRepository.save(workflow);
            log.info("Updated test status for workflow '{}': {}", workflowName, status);
        } else {
            log.warn("Could not find workflow '{}' in project '{}' for test result reporting", workflowName, projectName);
        }
    }

    public SyncResult pullChanges() {
        if (!isHealthy()) {
            throw new IllegalStateException("Test instance is not running or not healthy. Start it first.");
        }

        String testBaseUrl = deriveTestBaseUrl();
        int workflowsUpdated = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();

        // Fetch all workflows from Test
        JsonNode testWorkflows = syncHelper.httpGet(testBaseUrl + "/workflows");
        if (testWorkflows == null || !testWorkflows.isArray()) {
            return new SyncResult(0, 0, 0, 0, java.util.List.of("Could not fetch workflows from Test instance"));
        }

        for (JsonNode tw : testWorkflows) {
            try {
                String name = tw.path("name").asText();
                String projectName = tw.path("projectName").asText("");

                Optional<Workflow> devMatch = workflowRepository.findAll().stream()
                        .filter(w -> w.getName().equals(name))
                        .filter(w -> {
                            String wProjectName = w.getProject() != null ? w.getProject().getName() : "";
                            return wProjectName.equals(projectName);
                        })
                        .findFirst();

                if (devMatch.isPresent()) {
                    Workflow devWorkflow = devMatch.get();
                    // Fetch full workflow details from Test
                    String testWorkflowId = tw.path("id").asText();
                    JsonNode fullTestWorkflow = syncHelper.httpGet(testBaseUrl + "/workflows/" + testWorkflowId);
                    if (fullTestWorkflow != null) {
                        boolean changed = false;

                        // Compare and update instructions
                        String testInstructions = fullTestWorkflow.path("instructions").asText(null);
                        if (!java.util.Objects.equals(devWorkflow.getInstructions(), testInstructions)) {
                            devWorkflow.setInstructions(testInstructions);
                            changed = true;
                        }

                        // Compare and update model
                        String testModel = fullTestWorkflow.path("model").asText(null);
                        if (!java.util.Objects.equals(devWorkflow.getModel(), testModel)) {
                            devWorkflow.setModel(testModel);
                            changed = true;
                        }

                        if (changed) {
                            workflowRepository.save(devWorkflow);
                            workflowsUpdated++;
                            log.info("Pulled changes for workflow '{}' from Test", name);
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("Workflow '" + tw.path("name").asText() + "': " + e.getMessage());
                log.warn("Failed to pull changes for workflow: {}", e.getMessage());
            }
        }

        return new SyncResult(0, workflowsUpdated, 0, 0, errors);
    }

    // ── Internal helpers ──────────────────────────────────

    private void autoSyncOnBoot() {
        try {
            if (isHealthy()) {
                log.info("Auto-syncing definitions to Test...");
                SyncResult result = syncDefinitions();
                log.info("Auto-sync to Test: {} projects, {} workflows, {} skills",
                        result.projectsSynced(), result.workflowsSynced(), result.skillsSynced());
            }
        } catch (Exception e) {
            log.warn("Auto-sync to Test failed: {}", e.getMessage());
        }
    }

    boolean isContainerRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--filter",
                    "name=" + config.getTestContainerName(), "--format", "{{.Status}}");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return !output.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    boolean isHealthy() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(config.getTestHealthUrl()).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    String deriveTestBaseUrl() {
        String healthUrl = config.getTestHealthUrl();
        int apiIdx = healthUrl.indexOf("/api/v1");
        if (apiIdx > 0) {
            return healthUrl.substring(0, apiIdx) + "/api/v1";
        }
        return "http://localhost:8090/api/v1";
    }

    private Path resolveTestComposeFile() {
        Path backendDir = resolveBackendDir();
        return backendDir.resolve(config.getTestComposeFile());
    }

    private Path resolveBackendDir() {
        if (config.getBackendDir() != null && !config.getBackendDir().isBlank()) {
            return Path.of(config.getBackendDir());
        }
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

    private void runCommand(Path workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[testing] {}", line);
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
        log.warn("Test health check did not pass within {}s", maxWaitSeconds);
    }
}
