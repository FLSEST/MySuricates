package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.TestingStatusResponse;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestingService {

    private static final int HEALTH_CHECK_TIMEOUT_MS = 3000;
    private static final int DEFAULT_POLL_MAX_WAIT_SECONDS = 600;
    private static final int DEFAULT_POLL_INTERVAL_MS = 2000;
    private static final int HEALTH_WAIT_INTERVAL_SECONDS = 2;

    private final StagingConfig config;
    private final InstanceSyncHelper syncHelper;
    private final WorkflowRepository workflowRepository;
    private final ApiKeyService apiKeyService;
    private final DockerContainerManager dockerManager;

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
                // WARNING: Auto-sync on boot is intentionally disabled.
                // It was overwriting valuable workflows that only existed on the Test instance.
                // Sync must be triggered manually via POST /api/v1/testing/sync-definitions
                // or through the Settings UI.
                log.info("Test environment started. Use manual sync to push definitions from Dev.");
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
                JsonNode info = withTestApiKey(() -> syncHelper.httpGet(deriveTestBaseUrl() + "/system/info"));
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
        Map<String, String> env = Map.of("AUTOMATION_API_KEY", apiKeyService.getEffectiveApiKey());
        runCommand(composeFile.getParent(), env, "docker", "compose", "-f", composeFile.toString(), "up", "-d");
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
        String testApiKey = readContainerApiKey(config.getTestContainerName());
        if (testApiKey == null) {
            throw new IllegalStateException("Could not read API key from test container. Is the container running?");
        }
        return syncHelper.syncPreview(deriveTestBaseUrl(), testApiKey);
    }

    public SyncResult syncDefinitions() {
        return syncDefinitions(null);
    }

    public SyncResult syncDefinitions(SyncDefinitionsRequest request) {
        if (!isHealthy()) {
            throw new IllegalStateException("Test instance is not running or not healthy. Start it first.");
        }
        String testApiKey = readContainerApiKey(config.getTestContainerName());
        if (testApiKey == null) {
            return new SyncResult(0, 0, 0, 0, List.of("Could not read API key from test container"));
        }
        return syncHelper.syncDefinitionsTo(deriveTestBaseUrl(), testApiKey, request);
    }

    public void reportTestResult(String workflowName, String projectName, String status) {
        Optional<Workflow> match = findWorkflowByNameAndProject(workflowName, projectName);

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

        return withTestApiKey(() -> {
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

                    Optional<Workflow> devMatch = findWorkflowByNameAndProject(name, projectName);
                    if (devMatch.isEmpty()) {
                        continue;
                    }

                    String testWorkflowId = tw.path("id").asText();
                    JsonNode fullTestWorkflow = syncHelper.httpGet(testBaseUrl + "/workflows/" + testWorkflowId);
                    if (fullTestWorkflow == null) {
                        continue;
                    }

                    if (applyWorkflowChanges(devMatch.get(), fullTestWorkflow)) {
                        workflowsUpdated++;
                        log.info("Pulled changes for workflow '{}' from Test", name);
                    }
                } catch (Exception e) {
                    errors.add("Workflow '" + tw.path("name").asText() + "': " + e.getMessage());
                    log.warn("Failed to pull changes for workflow: {}", e.getMessage());
                }
            }

            return new SyncResult(0, workflowsUpdated, 0, 0, errors);
        });
    }

    // ── Remote execution on Test ─────────────────────────

    /**
     * Finds a workflow on the Test instance by name and project name,
     * then starts it with the given context. Returns the run ID.
     */
    public String executeWorkflowOnTest(String workflowName, String projectName,
                                         Map<String, Object> context) {
        if (!isHealthy()) {
            throw new IllegalStateException("Test instance is not running or not healthy.");
        }

        return withTestApiKey(() -> {
            String baseUrl = deriveTestBaseUrl();

            // Find workflow on test by name + project
            JsonNode workflows = syncHelper.httpGet(baseUrl + "/workflows");
            if (workflows == null || !workflows.isArray()) {
                throw new IllegalStateException("Could not fetch workflows from Test instance.");
            }

            String testWorkflowId = null;
            for (JsonNode w : workflows) {
                String name = w.path("name").asText();
                String pName = w.path("projectName").asText("");
                if (name.equals(workflowName) && pName.equals(projectName != null ? projectName : "")) {
                    testWorkflowId = w.path("id").asText();
                    break;
                }
            }
            if (testWorkflowId == null) {
                throw new IllegalStateException("Workflow '" + workflowName +
                        "' not found on Test instance (project: " + projectName + ").");
            }

            // Start execution
            Map<String, Object> body = context != null && !context.isEmpty()
                    ? Map.of("context", context)
                    : Map.of();
            JsonNode result = syncHelper.httpPost(baseUrl + "/workflows/" + testWorkflowId + "/execute", body);
            if (result == null || !result.has("id")) {
                throw new IllegalStateException("Failed to start workflow on Test instance.");
            }

            String runId = result.path("id").asText();
            log.info("Started workflow '{}' on Test instance: runId={}", workflowName, runId);
            return runId;
        });
    }

    /**
     * Polls the Test instance for a run's completion.
     * Returns the full run JSON node when done (COMPLETED/FAILED/CANCELLED).
     * Throws on timeout (default 10 min, 2s interval).
     */
    public JsonNode pollTestRun(String runId) {
        return pollTestRun(runId, DEFAULT_POLL_MAX_WAIT_SECONDS, DEFAULT_POLL_INTERVAL_MS);
    }

    public JsonNode pollTestRun(String runId, int maxWaitSeconds, int intervalMs) {
        return withTestApiKey(() -> {
            String baseUrl = deriveTestBaseUrl();
            String url = baseUrl + "/executions/" + runId;
            long waitedMs = 0;
            long maxWaitMs = maxWaitSeconds * 1000L;

            while (waitedMs < maxWaitMs) {
                JsonNode run = syncHelper.httpGet(url);
                if (run != null) {
                    String status = run.path("status").asText("");
                    if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                        log.info("Test run {} finished with status: {} ({}ms)", runId, status, waitedMs);
                        return run;
                    }
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Polling interrupted", e);
                }
                waitedMs += intervalMs;
            }
            throw new IllegalStateException("Test run " + runId + " did not complete within " + maxWaitSeconds + "s");
        });
    }

    // ── API key helpers ────────────────────────────────────

    private <T> T withTestApiKey(java.util.function.Supplier<T> action) {
        String apiKey = readContainerApiKey(config.getTestContainerName());
        if (apiKey == null) {
            log.warn("Could not read API key from test container — requests may fail with 401");
        }
        syncHelper.setTargetApiKeyOverride(apiKey);
        try {
            return action.get();
        } finally {
            syncHelper.clearTargetApiKeyOverride();
        }
    }

    String readContainerApiKey(String containerName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "exec", containerName,
                    "python3", "-c",
                    "import sqlite3; conn=sqlite3.connect('/app/data/automation.db'); c=conn.cursor(); c.execute(\"SELECT value FROM app_settings WHERE key='api-key'\"); r=c.fetchone(); print(r[0] if r else ''); conn.close()");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit == 0 && !output.isEmpty()) {
                log.debug("Read API key from container {}", containerName);
                return output;
            }
        } catch (Exception e) {
            log.warn("Failed to read API key from container {}: {}", containerName, e.getMessage());
        }
        return null;
    }

    // ── Internal helpers ──────────────────────────────────

    private Optional<Workflow> findWorkflowByNameAndProject(String workflowName, String projectName) {
        String effectiveProjectName = projectName != null ? projectName : "";
        return workflowRepository.findAll().stream()
                .filter(w -> w.getName().equals(workflowName))
                .filter(w -> {
                    String wProjectName = w.getProject() != null ? w.getProject().getName() : "";
                    return wProjectName.equals(effectiveProjectName);
                })
                .findFirst();
    }

    private boolean applyWorkflowChanges(Workflow devWorkflow, JsonNode fullTestWorkflow) {
        boolean changed = false;

        String testInstructions = fullTestWorkflow.path("instructions").asText(null);
        if (!java.util.Objects.equals(devWorkflow.getInstructions(), testInstructions)) {
            devWorkflow.setInstructions(testInstructions);
            changed = true;
        }

        String testModel = fullTestWorkflow.path("model").asText(null);
        if (!java.util.Objects.equals(devWorkflow.getModel(), testModel)) {
            devWorkflow.setModel(testModel);
            changed = true;
        }

        if (changed) {
            workflowRepository.save(devWorkflow);
        }
        return changed;
    }

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
        return dockerManager.isRunning(config.getTestContainerName());
    }

    public boolean isHealthy() {
        return dockerManager.isHealthy(config.getTestHealthUrl());
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
        dockerManager.runCommand("testing", workDir, command);
    }

    private void runCommand(Path workDir, Map<String, String> extraEnv, String... command) {
        dockerManager.runCommand("testing", workDir, extraEnv, command);
    }

    private void waitForHealth(int maxWaitSeconds) {
        int waited = 0;
        while (waited < maxWaitSeconds) {
            if (isHealthy()) {
                return;
            }
            try {
                Thread.sleep(HEALTH_WAIT_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            waited += HEALTH_WAIT_INTERVAL_SECONDS;
        }
        log.warn("Test health check did not pass within {}s", maxWaitSeconds);
    }
}
