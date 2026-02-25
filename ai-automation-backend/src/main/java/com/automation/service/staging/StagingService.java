package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.response.StagingStatusResponse;
import com.automation.dto.response.SyncResult;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class StagingService {

    private final StagingConfig config;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;

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
                log.debug("Stable auto-start skipped: {}", e.getMessage());
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
                JsonNode info = httpGet(stableBaseUrl + "/system/info");
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
        runCommand(composeFile.getParent(), "docker", "compose", "-f", composeFile.toString(), "up", "-d");
        waitForHealth(60);
        log.info("Stable environment started.");
    }

    public void stop() {
        Path composeFile = resolveComposeFile();
        log.info("Stopping stable environment...");
        runCommand(composeFile.getParent(), "docker", "compose", "-f", composeFile.toString(), "down");
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
                    runCommand(backendDir, "docker", "compose", "-f", composeFile.toString(), "down");
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
                runCommand(backendDir, "docker", "compose", "-f", composeFile.toString(), "build");

                // Step 5: Start
                checkCancelled();
                promoteState.set(PromoteState.of(PromotePhase.STARTING, 5));
                runCommand(backendDir, "docker", "compose", "-f", composeFile.toString(), "up", "-d");
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

    public SyncResult syncDefinitions() {
        if (!isHealthy()) {
            throw new IllegalStateException("Stable instance is not running or not healthy. Start it first.");
        }

        String stableBaseUrl = deriveStableBaseUrl();
        int projectsSynced = 0;
        int workflowsSynced = 0;
        int skillsSynced = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        // Map dev project ID → stable project ID for workflow assignment
        Map<String, String> projectIdMapping = new HashMap<>();

        // 1. Sync projects
        List<Project> devProjects = projectRepository.findAll();
        JsonNode stableProjects = httpGet(stableBaseUrl + "/projects");
        Map<String, JsonNode> stableProjectsByNamePath = new HashMap<>();
        if (stableProjects != null && stableProjects.isArray()) {
            for (JsonNode sp : stableProjects) {
                String key = sp.path("name").asText() + "|" + sp.path("path").asText();
                stableProjectsByNamePath.put(key, sp);
            }
        }

        for (Project project : devProjects) {
            try {
                String key = project.getName() + "|" + project.getPath();
                JsonNode existing = stableProjectsByNamePath.get(key);
                if (existing != null) {
                    projectIdMapping.put(project.getId(), existing.path("id").asText());
                    skipped++;
                    log.debug("Project already exists on Stable: {}", project.getName());
                } else {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("name", project.getName());
                    body.put("type", project.getType().name());
                    body.put("path", project.getPath());
                    if (project.getSubtitle() != null) body.put("subtitle", project.getSubtitle());
                    if (project.getDescription() != null) body.put("description", project.getDescription());
                    if (project.getConfig() != null && !project.getConfig().isEmpty()) {
                        body.put("config", project.getConfig());
                    }
                    JsonNode created = httpPost(stableBaseUrl + "/projects", body);
                    if (created != null) {
                        projectIdMapping.put(project.getId(), created.path("id").asText());
                        projectsSynced++;
                        log.info("Synced project: {}", project.getName());
                    }
                }
            } catch (Exception e) {
                errors.add("Project '" + project.getName() + "': " + e.getMessage());
                log.warn("Failed to sync project '{}': {}", project.getName(), e.getMessage());
            }
        }

        // 2. Sync workflows
        List<Workflow> devWorkflows = workflowRepository.findAll();
        JsonNode stableWorkflows = httpGet(stableBaseUrl + "/workflows");
        Map<String, JsonNode> stableWorkflowsByNameProject = new HashMap<>();
        if (stableWorkflows != null && stableWorkflows.isArray()) {
            for (JsonNode sw : stableWorkflows) {
                String wKey = sw.path("name").asText() + "|" + sw.path("projectName").asText();
                stableWorkflowsByNameProject.put(wKey, sw);
            }
        }

        for (Workflow workflow : devWorkflows) {
            try {
                String projectName = workflow.getProject() != null ? workflow.getProject().getName() : "";
                String wKey = workflow.getName() + "|" + projectName;
                String stableProjectId = projectIdMapping.get(
                        workflow.getProject() != null ? workflow.getProject().getId() : null);

                JsonNode existingWorkflow = stableWorkflowsByNameProject.get(wKey);
                if (existingWorkflow != null) {
                    // Update existing workflow
                    String stableWorkflowId = existingWorkflow.path("id").asText();
                    Map<String, Object> updateBody = new LinkedHashMap<>();
                    updateBody.put("name", workflow.getName());
                    updateBody.put("description", workflow.getDescription());
                    updateBody.put("icon", workflow.getIcon());
                    updateBody.put("blocks", workflow.getBlocks());
                    if (workflow.getInstructions() != null) updateBody.put("instructions", workflow.getInstructions());
                    if (workflow.getModel() != null) updateBody.put("model", workflow.getModel());
                    if (workflow.getMetadata() != null && !workflow.getMetadata().isEmpty()) {
                        updateBody.put("metadata", workflow.getMetadata());
                    }
                    httpPut(stableBaseUrl + "/workflows/" + stableWorkflowId, updateBody);
                    workflowsSynced++;
                    log.info("Updated workflow on Stable: {}", workflow.getName());
                } else {
                    // Import as new workflow
                    Map<String, Object> importBody = new LinkedHashMap<>();
                    if (stableProjectId != null) importBody.put("projectId", stableProjectId);
                    importBody.put("name", workflow.getName());
                    importBody.put("description", workflow.getDescription());
                    importBody.put("icon", workflow.getIcon());
                    importBody.put("blocks", workflow.getBlocks());
                    if (workflow.getInstructions() != null) importBody.put("instructions", workflow.getInstructions());
                    if (workflow.getModel() != null) importBody.put("model", workflow.getModel());
                    httpPost(stableBaseUrl + "/workflows/import", importBody);
                    workflowsSynced++;
                    log.info("Created workflow on Stable: {}", workflow.getName());
                }
            } catch (Exception e) {
                errors.add("Workflow '" + workflow.getName() + "': " + e.getMessage());
                log.warn("Failed to sync workflow '{}': {}", workflow.getName(), e.getMessage());
            }
        }

        // 3. Sync custom skills (non-builtin only)
        List<Skill> customSkills = skillRepository.findByBuiltin(false);
        JsonNode stableSkills = httpGet(stableBaseUrl + "/skills");
        Set<String> stableSkillNames = new HashSet<>();
        if (stableSkills != null && stableSkills.isArray()) {
            for (JsonNode ss : stableSkills) {
                stableSkillNames.add(ss.path("name").asText());
            }
        }

        for (Skill skill : customSkills) {
            try {
                if (stableSkillNames.contains(skill.getName())) {
                    skipped++;
                    log.debug("Skill already exists on Stable: {}", skill.getName());
                } else {
                    Map<String, Object> skillBody = new LinkedHashMap<>();
                    skillBody.put("name", skill.getName());
                    skillBody.put("description", skill.getDescription());
                    skillBody.put("icon", skill.getIcon());
                    skillBody.put("category", skill.getCategory().name());
                    skillBody.put("aiContext", skill.getAiContext());
                    if (skill.getConfigSchema() != null && !skill.getConfigSchema().isEmpty()) {
                        skillBody.put("configSchema", skill.getConfigSchema());
                    }
                    httpPost(stableBaseUrl + "/skills/import", skillBody);
                    skillsSynced++;
                    log.info("Synced skill: {}", skill.getName());
                }
            } catch (Exception e) {
                errors.add("Skill '" + skill.getName() + "': " + e.getMessage());
                log.warn("Failed to sync skill '{}': {}", skill.getName(), e.getMessage());
            }
        }

        log.info("Definition sync complete: {} projects, {} workflows, {} skills synced, {} skipped, {} errors",
                projectsSynced, workflowsSynced, skillsSynced, skipped, errors.size());

        return new SyncResult(projectsSynced, workflowsSynced, skillsSynced, skipped, errors);
    }

    // ── Stable API HTTP helpers ────────────────────────────

    String deriveStableBaseUrl() {
        // healthUrl = "http://localhost:9091/api/v1/system/health" → "http://localhost:9091/api/v1"
        String healthUrl = config.getHealthUrl();
        int apiIdx = healthUrl.indexOf("/api/v1");
        if (apiIdx > 0) {
            return healthUrl.substring(0, apiIdx) + "/api/v1";
        }
        return "http://localhost:9091/api/v1";
    }

    private JsonNode httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                return objectMapper.readTree(body);
            }
            conn.disconnect();
            return null;
        } catch (Exception e) {
            log.warn("GET {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    private JsonNode httpPost(String url, Object body) {
        return httpSend("POST", url, body);
    }

    private JsonNode httpPut(String url, Object body) {
        return httpSend("PUT", url, body);
    }

    private JsonNode httpSend(String method, String url, Object body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                return responseBody.isEmpty() ? null : objectMapper.readTree(responseBody);
            } else {
                String errorBody = "";
                try {
                    errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                conn.disconnect();
                throw new RuntimeException("HTTP " + code + ": " + errorBody);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(method + " " + url + " failed: " + e.getMessage(), e);
        }
    }

    // ── Internal helpers ──────────────────────────────────

    boolean isContainerRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--filter",
                    "name=" + config.getContainerName(), "--format", "{{.Status}}");
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
            HttpURLConnection conn = (HttpURLConnection) URI.create(config.getHealthUrl()).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
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
            return null;
        }
    }

    private void runCommand(Path workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
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
                        try { Files.delete(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
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
