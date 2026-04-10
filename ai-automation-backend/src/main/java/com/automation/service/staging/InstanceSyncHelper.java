package com.automation.service.staging;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncPreviewItem;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.WorkflowDiff;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceSyncHelper {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final AppSettingsRepository appSettingsRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    /** Overrides the API key for outgoing HTTP calls within the current thread (used for Stable/Test targets). */
    private final ThreadLocal<String> targetApiKeyOverride = new ThreadLocal<>();

    /** Sets the API key override for the current thread. Call {@link #clearTargetApiKeyOverride()} in a finally block. */
    public void setTargetApiKeyOverride(String apiKey) {
        targetApiKeyOverride.set(apiKey);
    }

    /** Clears the API key override for the current thread. */
    public void clearTargetApiKeyOverride() {
        targetApiKeyOverride.remove();
    }

    // ── Target maps record ──────────────────────────────────

    private record TargetMaps(
            Map<String, JsonNode> projectsByNamePath,
            Map<String, JsonNode> workflowsByNameProject,
            Set<String> skillNames
    ) {}

    private TargetMaps buildTargetMaps(String targetBaseUrl) {
        Map<String, JsonNode> projectsByNamePath = new HashMap<>();
        JsonNode targetProjects = httpGet(targetBaseUrl + "/projects");
        if (targetProjects != null && targetProjects.isArray()) {
            for (JsonNode sp : targetProjects) {
                String key = sp.path("name").asText() + "|" + sp.path("path").asText();
                projectsByNamePath.put(key, sp);
            }
        }

        Map<String, JsonNode> workflowsByNameProject = new HashMap<>();
        JsonNode targetWorkflows = httpGet(targetBaseUrl + "/workflows");
        if (targetWorkflows != null && targetWorkflows.isArray()) {
            for (JsonNode sw : targetWorkflows) {
                String wKey = sw.path("name").asText() + "|" + sw.path("projectName").asText();
                workflowsByNameProject.put(wKey, sw);
            }
        }

        Set<String> skillNames = new HashSet<>();
        JsonNode targetSkills = httpGet(targetBaseUrl + "/skills");
        if (targetSkills != null && targetSkills.isArray()) {
            for (JsonNode ss : targetSkills) {
                skillNames.add(ss.path("name").asText());
            }
        }

        return new TargetMaps(projectsByNamePath, workflowsByNameProject, skillNames);
    }

    // ── Sync preview ────────────────────────────────────────

    public SyncPreview syncPreview(String targetBaseUrl) {
        TargetMaps target = buildTargetMaps(targetBaseUrl);

        List<SyncPreviewItem> projects = new ArrayList<>();
        for (Project project : projectRepository.findAll()) {
            String key = project.getName() + "|" + project.getPath();
            JsonNode existing = target.projectsByNamePath().get(key);
            String action = existing != null ? "SKIP" : "NEW";
            projects.add(new SyncPreviewItem(project.getId(), project.getName(), null, action));
        }

        List<SyncPreviewItem> workflows = new ArrayList<>();
        for (Workflow workflow : workflowRepository.findAllReadOnly()) {
            String projectName = workflow.getProject() != null ? workflow.getProject().getName() : "";
            String wKey = workflow.getName() + "|" + projectName;
            JsonNode existing = target.workflowsByNameProject().get(wKey);
            String action;
            WorkflowDiff diff = null;
            if (existing != null) {
                diff = computeWorkflowDiff(workflow, existing);
                action = diff.changedFields().isEmpty() ? "SKIP" : "UPDATE";
            } else {
                action = "NEW";
            }
            workflows.add(new SyncPreviewItem(
                    workflow.getId(),
                    workflow.getName(),
                    projectName.isEmpty() ? null : projectName,
                    action,
                    diff
            ));
        }

        List<SyncPreviewItem> skills = new ArrayList<>();
        for (Skill skill : skillRepository.findByBuiltin(false)) {
            String action = target.skillNames().contains(skill.getName()) ? "SKIP" : "NEW";
            skills.add(new SyncPreviewItem(skill.getId(), skill.getName(), null, action));
        }

        return new SyncPreview(projects, workflows, skills);
    }

    // ── Sync all (backwards compatible) ─────────────────────

    public SyncResult syncDefinitionsTo(String targetBaseUrl) {
        return syncDefinitionsTo(targetBaseUrl, null, null);
    }

    public SyncResult syncDefinitionsTo(String targetBaseUrl, SyncDefinitionsRequest request) {
        return syncDefinitionsTo(targetBaseUrl, null, request);
    }

    public SyncResult syncDefinitionsTo(String targetBaseUrl, String targetApiKey, SyncDefinitionsRequest request) {
        targetApiKeyOverride.set(targetApiKey);
        try {
            return doSyncDefinitionsTo(targetBaseUrl, request);
        } finally {
            targetApiKeyOverride.remove();
        }
    }

    public SyncPreview syncPreview(String targetBaseUrl, String targetApiKey) {
        targetApiKeyOverride.set(targetApiKey);
        try {
            return syncPreview(targetBaseUrl);
        } finally {
            targetApiKeyOverride.remove();
        }
    }

    // ── Selective sync ──────────────────────────────────────

    private SyncResult doSyncDefinitionsTo(String targetBaseUrl, SyncDefinitionsRequest request) {
        int projectsSynced = 0;
        int workflowsSynced = 0;
        int skillsSynced = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        Map<String, String> projectIdMapping = new HashMap<>();

        // Determine which items to sync
        Set<String> selectedProjectIds = request != null && request.projectIds() != null
                ? new HashSet<>(request.projectIds()) : null;
        Set<String> selectedWorkflowIds = request != null && request.workflowIds() != null
                ? new HashSet<>(request.workflowIds()) : null;
        Set<String> selectedSkillIds = request != null && request.skillIds() != null
                ? new HashSet<>(request.skillIds()) : null;

        // If workflows are selected, ensure their projects are included
        if (selectedWorkflowIds != null && selectedProjectIds != null) {
            for (Workflow workflow : workflowRepository.findAllReadOnly()) {
                if (selectedWorkflowIds.contains(workflow.getId()) && workflow.getProject() != null) {
                    selectedProjectIds.add(workflow.getProject().getId());
                }
            }
        }

        // 1. Sync projects
        List<Project> devProjects = projectRepository.findAll();
        JsonNode targetProjects = httpGet(targetBaseUrl + "/projects");
        Map<String, JsonNode> targetProjectsByNamePath = new HashMap<>();
        if (targetProjects != null && targetProjects.isArray()) {
            for (JsonNode sp : targetProjects) {
                String key = sp.path("name").asText() + "|" + sp.path("path").asText();
                targetProjectsByNamePath.put(key, sp);
            }
        }

        for (Project project : devProjects) {
            // Always build the ID mapping for existing projects (needed for workflow sync)
            String key = project.getName() + "|" + project.getPath();
            JsonNode existing = targetProjectsByNamePath.get(key);
            if (existing != null) {
                projectIdMapping.put(project.getId(), existing.path("id").asText());
            }

            // Skip if not selected
            if (selectedProjectIds != null && !selectedProjectIds.contains(project.getId())) {
                continue;
            }

            try {
                if (existing != null) {
                    skipped++;
                    log.debug("Project already exists on target: {}", project.getName());
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
                    JsonNode created = httpPost(targetBaseUrl + "/projects", body);
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

        // 2. Sync workflows — use findAllReadOnly() to prevent Hibernate dirty-check flushes
        // from overwriting native SQL updates to stable_synced_at
        List<Workflow> devWorkflows = workflowRepository.findAllReadOnly();
        JsonNode targetWorkflows = httpGet(targetBaseUrl + "/workflows");
        Map<String, JsonNode> targetWorkflowsByNameProject = new HashMap<>();
        if (targetWorkflows != null && targetWorkflows.isArray()) {
            for (JsonNode sw : targetWorkflows) {
                String wKey = sw.path("name").asText() + "|" + sw.path("projectName").asText();
                targetWorkflowsByNameProject.put(wKey, sw);
            }
        }

        for (Workflow workflow : devWorkflows) {
            if (selectedWorkflowIds != null && !selectedWorkflowIds.contains(workflow.getId())) {
                continue;
            }
            if (selectedProjectIds != null) {
                String wfProjectId = workflow.getProject() != null ? workflow.getProject().getId() : null;
                if (wfProjectId == null || !selectedProjectIds.contains(wfProjectId)) {
                    continue;
                }
            }

            try {
                String projectName = workflow.getProject() != null ? workflow.getProject().getName() : "";
                String wKey = workflow.getName() + "|" + projectName;
                String targetProjectId = projectIdMapping.get(
                        workflow.getProject() != null ? workflow.getProject().getId() : null);

                JsonNode existingWorkflow = targetWorkflowsByNameProject.get(wKey);
                if (existingWorkflow != null) {
                    String targetWorkflowId = existingWorkflow.path("id").asText();
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
                    httpPut(targetBaseUrl + "/workflows/" + targetWorkflowId, updateBody);
                    workflowsSynced++;
                    markStableSynced(workflow.getId());
                    log.info("Updated workflow on target: {}", workflow.getName());
                } else {
                    Map<String, Object> importBody = new LinkedHashMap<>();
                    if (targetProjectId != null) importBody.put("projectId", targetProjectId);
                    importBody.put("name", workflow.getName());
                    importBody.put("description", workflow.getDescription());
                    importBody.put("icon", workflow.getIcon());
                    importBody.put("blocks", workflow.getBlocks());
                    if (workflow.getInstructions() != null) importBody.put("instructions", workflow.getInstructions());
                    if (workflow.getModel() != null) importBody.put("model", workflow.getModel());
                    httpPost(targetBaseUrl + "/workflows/import", importBody);
                    workflowsSynced++;
                    markStableSynced(workflow.getId());
                    log.info("Created workflow on target: {}", workflow.getName());
                }
            } catch (Exception e) {
                errors.add("Workflow '" + workflow.getName() + "': " + e.getMessage());
                log.warn("Failed to sync workflow '{}': {}", workflow.getName(), e.getMessage());
            }
        }

        // 3. Sync custom skills
        List<Skill> customSkills = skillRepository.findByBuiltin(false);
        JsonNode targetSkills = httpGet(targetBaseUrl + "/skills");
        Set<String> targetSkillNames = new HashSet<>();
        if (targetSkills != null && targetSkills.isArray()) {
            for (JsonNode ss : targetSkills) {
                targetSkillNames.add(ss.path("name").asText());
            }
        }

        for (Skill skill : customSkills) {
            if (selectedSkillIds != null && !selectedSkillIds.contains(skill.getId())) {
                continue;
            }

            try {
                if (targetSkillNames.contains(skill.getName())) {
                    skipped++;
                    log.debug("Skill already exists on target: {}", skill.getName());
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
                    httpPost(targetBaseUrl + "/skills/import", skillBody);
                    skillsSynced++;
                    log.info("Synced skill: {}", skill.getName());
                }
            } catch (Exception e) {
                errors.add("Skill '" + skill.getName() + "': " + e.getMessage());
                log.warn("Failed to sync skill '{}': {}", skill.getName(), e.getMessage());
            }
        }

        // 4. Sync auto-todo-default setting
        try {
            appSettingsRepository.get("auto-todo-default").ifPresent(value -> {
                try {
                    httpPut(targetBaseUrl + "/system/settings/auto-todo-default",
                            Map.of("value", value));
                    log.info("Synced auto-todo-default setting: {}", value);
                } catch (Exception e) {
                    errors.add("Setting 'auto-todo-default': " + e.getMessage());
                    log.warn("Failed to sync auto-todo-default: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            errors.add("Setting 'auto-todo-default' (read): " + e.getMessage());
            log.warn("Failed to read auto-todo-default: {}", e.getMessage());
        }

        // 5. Sync auto-repair-default setting
        try {
            appSettingsRepository.get("auto-repair-default").ifPresent(value -> {
                try {
                    httpPut(targetBaseUrl + "/system/settings/auto-repair-default",
                            Map.of("value", value));
                    log.info("Synced auto-repair-default setting: {}", value);
                } catch (Exception e) {
                    errors.add("Setting 'auto-repair-default': " + e.getMessage());
                    log.warn("Failed to sync auto-repair-default: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            errors.add("Setting 'auto-repair-default' (read): " + e.getMessage());
            log.warn("Failed to read auto-repair-default: {}", e.getMessage());
        }

        log.info("Definition sync complete: {} projects, {} workflows, {} skills synced, {} skipped, {} errors",
                projectsSynced, workflowsSynced, skillsSynced, skipped, errors.size());

        return new SyncResult(projectsSynced, workflowsSynced, skillsSynced, skipped, errors);
    }

    // ── Workflow diff ────────────────────────────────────────

    private WorkflowDiff computeWorkflowDiff(Workflow dev, JsonNode target) {
        List<String> changedFields = new ArrayList<>();

        String devDesc = dev.getDescription() != null ? dev.getDescription() : "";
        String targetDesc = target.path("description").asText("");
        if (!devDesc.equals(targetDesc)) changedFields.add("description");

        String devModel = dev.getModel() != null ? dev.getModel() : "";
        String targetModel = target.path("model").asText("");
        String modelBefore = null, modelAfter = null;
        if (!devModel.equals(targetModel)) {
            changedFields.add("model");
            modelBefore = targetModel.isBlank() ? null : targetModel;
            modelAfter = devModel.isBlank() ? null : devModel;
        }

        String devInstr = dev.getInstructions() != null ? dev.getInstructions() : "";
        String targetInstr = target.path("instructions").asText("");
        if (!devInstr.equals(targetInstr)) changedFields.add("instructions");

        int blocksAdded = 0, blocksRemoved = 0;
        try {
            String devBlocksJson = objectMapper.writeValueAsString(dev.getBlocks());
            String targetBlocksJson = target.path("blocks").toString();
            if (!objectMapper.readTree(devBlocksJson).equals(objectMapper.readTree(targetBlocksJson))) {
                changedFields.add("blocks");
                int devCount = dev.getBlocks() != null ? dev.getBlocks().size() : 0;
                int targetCount = target.path("blocks").size();
                if (devCount > targetCount) blocksAdded = devCount - targetCount;
                else if (targetCount > devCount) blocksRemoved = targetCount - devCount;
            }
        } catch (Exception e) {
            log.warn("Block diff failed for workflow '{}': {}", dev.getName(), e.getMessage());
            changedFields.add("blocks");
        }

        return new WorkflowDiff(changedFields, modelBefore, modelAfter, blocksAdded, blocksRemoved);
    }

    // ── HTTP helpers ──────────────────────────────────────

    public JsonNode httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
            addApiKeyHeader(conn);
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

    public JsonNode httpPost(String url, Object body) {
        return httpSend("POST", url, body);
    }

    public JsonNode httpPut(String url, Object body) {
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
            addApiKeyHeader(conn);
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

    /** Updates stable_synced_at via raw JDBC — bypasses Hibernate to avoid @LastModifiedDate. */
    private void markStableSynced(String workflowId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE workflows SET stable_synced_at = ? WHERE id = ?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, workflowId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to mark workflow {} as stable-synced: {}", workflowId, e.getMessage());
        }
    }

    private void addApiKeyHeader(HttpURLConnection conn) {
        String apiKey = targetApiKeyOverride.get();
        if (apiKey == null) {
            apiKey = apiKeyService.getEffectiveApiKey();
        }
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty(API_KEY_HEADER, apiKey);
        }
        conn.setRequestProperty("X-Internal-Sync", "true");
    }
}
