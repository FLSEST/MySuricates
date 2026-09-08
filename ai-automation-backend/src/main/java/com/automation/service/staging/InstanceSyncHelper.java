package com.automation.service.staging;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncResult;
import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;

/**
 * Pushes the local definitions (projects, workflows, custom skills, chains, two settings) to a
 * Stable/Test instance, with a pre-sync backup on the target and stable_synced_at bookkeeping.
 * Transport lives in {@link InstanceHttpClient}, the dry-run in {@link SyncPreviewService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceSyncHelper {

    private final AppSettingsRepository appSettingsRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final WorkflowChainRepository chainRepository;
    private final InstanceHttpClient httpClient;
    private final DataSource dataSource;

    // ── Sync ────────────────────────────────────────────────

    public SyncResult syncDefinitionsTo(String targetBaseUrl, String targetApiKey, SyncDefinitionsRequest request) {
        httpClient.setTargetApiKeyOverride(targetApiKey);
        try {
            return doSyncDefinitionsTo(targetBaseUrl, request);
        } finally {
            httpClient.clearTargetApiKeyOverride();
        }
    }

    // ── Selective sync ──────────────────────────────────────

    private SyncResult doSyncDefinitionsTo(String targetBaseUrl, SyncDefinitionsRequest request) {
        int projectsSynced = 0;
        int workflowsSynced = 0;
        int skillsSynced = 0;
        int chainsSynced = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        // Pre-sync backup on target (rollback point) — don't block sync on failure.
        String preSyncBackup = createPreSyncBackup(targetBaseUrl, errors);

        Map<String, String> projectIdMapping = new HashMap<>();
        Map<String, String> workflowIdMapping = new HashMap<>();

        // Determine which items to sync
        Set<String> selectedProjectIds = request != null && request.projectIds() != null
                ? new HashSet<>(request.projectIds()) : null;
        Set<String> selectedWorkflowIds = request != null && request.workflowIds() != null
                ? new HashSet<>(request.workflowIds()) : null;
        Set<String> selectedSkillIds = request != null && request.skillIds() != null
                ? new HashSet<>(request.skillIds()) : null;
        Set<String> selectedChainIds = request != null && request.chainIds() != null
                ? new HashSet<>(request.chainIds()) : null;

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
        JsonNode targetProjects = httpClient.httpGet(targetBaseUrl + "/projects");
        Map<String, JsonNode> targetProjectsByNamePath = new HashMap<>();
        if (targetProjects != null && targetProjects.isArray()) {
            for (JsonNode sp : targetProjects) {
                String key = sp.path("name").asText() + "|" + sp.path("path").asText();
                targetProjectsByNamePath.put(key, sp);
            }
        } else if (targetProjects == null) {
            errors.add("Could not fetch projects from target — skipping project sync");
        }

        for (Project project : devProjects) {
            if (targetProjects == null) break;
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
                    JsonNode created = httpClient.httpPost(targetBaseUrl + "/projects", body);
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
        JsonNode targetWorkflows = httpClient.httpGet(targetBaseUrl + "/workflows");
        Map<String, JsonNode> targetWorkflowsByNameProject = new HashMap<>();
        if (targetWorkflows != null && targetWorkflows.isArray()) {
            for (JsonNode sw : targetWorkflows) {
                String wKey = sw.path("name").asText() + "|" + sw.path("projectName").asText();
                targetWorkflowsByNameProject.put(wKey, sw);
            }
        } else if (targetWorkflows == null) {
            errors.add("Could not fetch workflows from target — skipping workflow sync");
        }

        for (Workflow workflow : devWorkflows) {
            if (targetWorkflows == null) break;
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
                    workflowIdMapping.put(workflow.getId(), targetWorkflowId);
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
                    httpClient.httpPut(targetBaseUrl + "/workflows/" + targetWorkflowId, updateBody);
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
                    JsonNode created = httpClient.httpPost(targetBaseUrl + "/workflows/import", importBody);
                    if (created != null && !created.path("id").isMissingNode()) {
                        workflowIdMapping.put(workflow.getId(), created.path("id").asText());
                    }
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
        JsonNode targetSkills = httpClient.httpGet(targetBaseUrl + "/skills");
        Set<String> targetSkillNames = new HashSet<>();
        if (targetSkills != null && targetSkills.isArray()) {
            for (JsonNode ss : targetSkills) {
                targetSkillNames.add(ss.path("name").asText());
            }
        } else if (targetSkills == null) {
            errors.add("Could not fetch skills from target — skipping skill sync");
        }

        for (Skill skill : customSkills) {
            if (targetSkills == null) break;
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
                    httpClient.httpPost(targetBaseUrl + "/skills/import", skillBody);
                    skillsSynced++;
                    log.info("Synced skill: {}", skill.getName());
                }
            } catch (Exception e) {
                errors.add("Skill '" + skill.getName() + "': " + e.getMessage());
                log.warn("Failed to sync skill '{}': {}", skill.getName(), e.getMessage());
            }
        }

        // 4. Sync chains
        // Ensure workflow-id mapping covers all target workflows (chains may reference workflows
        // that weren't part of this sync but already exist on the target).
        if (targetWorkflows != null && targetWorkflows.isArray()) {
            for (Workflow workflow : devWorkflows) {
                if (workflowIdMapping.containsKey(workflow.getId())) continue;
                String projectName = workflow.getProject() != null ? workflow.getProject().getName() : "";
                String wKey = workflow.getName() + "|" + projectName;
                JsonNode match = targetWorkflowsByNameProject.get(wKey);
                if (match != null && !match.path("id").isMissingNode()) {
                    workflowIdMapping.put(workflow.getId(), match.path("id").asText());
                }
            }
        }

        List<WorkflowChain> devChains = chainRepository.findAll();
        JsonNode targetChains = httpClient.httpGet(targetBaseUrl + "/chains");
        Map<String, JsonNode> targetChainsByName = new HashMap<>();
        if (targetChains != null && targetChains.isArray()) {
            for (JsonNode tc : targetChains) {
                targetChainsByName.put(tc.path("name").asText(), tc);
            }
        } else if (targetChains == null) {
            errors.add("Could not fetch chains from target — skipping chain sync");
        }

        for (WorkflowChain chain : devChains) {
            if (targetChains == null) break;
            if (selectedChainIds != null && !selectedChainIds.contains(chain.getId())) {
                continue;
            }

            try {
                List<Map<String, Object>> entriesBody = new ArrayList<>();
                boolean missingMapping = false;
                for (ChainEntry entry : chain.getEntries()) {
                    String targetWorkflowId = workflowIdMapping.get(entry.getWorkflowId());
                    if (targetWorkflowId == null) {
                        errors.add("Chain '" + chain.getName() + "': workflow '" + entry.getWorkflowId()
                                + "' has no target mapping (workflow not on target)");
                        missingMapping = true;
                        break;
                    }
                    Map<String, Object> entryBody = new LinkedHashMap<>();
                    entryBody.put("workflowId", targetWorkflowId);
                    if (entry.getLabel() != null) entryBody.put("label", entry.getLabel());
                    entryBody.put("sortOrder", entry.getSortOrder());
                    entryBody.put("stopOnFailure", entry.isStopOnFailure());
                    if (entry.getInputContext() != null && !entry.getInputContext().isEmpty()) {
                        entryBody.put("inputContext", entry.getInputContext());
                    }
                    entriesBody.add(entryBody);
                }
                if (missingMapping) continue;
                if (entriesBody.isEmpty()) {
                    errors.add("Chain '" + chain.getName() + "': no entries — skipped");
                    continue;
                }

                Map<String, Object> chainBody = new LinkedHashMap<>();
                chainBody.put("name", chain.getName());
                if (chain.getDescription() != null) chainBody.put("description", chain.getDescription());
                if (chain.getIcon() != null) chainBody.put("icon", chain.getIcon());
                chainBody.put("entries", entriesBody);
                chainBody.put("stopOnFailure", chain.isStopOnFailure());

                JsonNode existingChain = targetChainsByName.get(chain.getName());
                if (existingChain != null) {
                    String targetChainId = existingChain.path("id").asText();
                    httpClient.httpPut(targetBaseUrl + "/chains/" + targetChainId, chainBody);
                    chainsSynced++;
                    log.info("Updated chain on target: {}", chain.getName());
                } else {
                    httpClient.httpPost(targetBaseUrl + "/chains", chainBody);
                    chainsSynced++;
                    log.info("Created chain on target: {}", chain.getName());
                }
            } catch (Exception e) {
                errors.add("Chain '" + chain.getName() + "': " + e.getMessage());
                log.warn("Failed to sync chain '{}': {}", chain.getName(), e.getMessage());
            }
        }

        // 5. Sync auto-todo-default setting
        try {
            appSettingsRepository.get("auto-todo-default").ifPresent(value -> {
                try {
                    httpClient.httpPut(targetBaseUrl + "/system/settings/auto-todo-default",
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
                    httpClient.httpPut(targetBaseUrl + "/system/settings/auto-repair-default",
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

        log.info("Definition sync complete: {} projects, {} workflows, {} skills, {} chains synced, {} skipped, {} errors",
                projectsSynced, workflowsSynced, skillsSynced, chainsSynced, skipped, errors.size());

        return new SyncResult(projectsSynced, workflowsSynced, skillsSynced, chainsSynced, skipped, errors, preSyncBackup);
    }

    /**
     * Triggers a database backup on the sync target so the user has a rollback point.
     * Failures are recorded in {@code errors} but do not block the sync — a broken
     * backup endpoint shouldn't prevent the actual work.
     *
     * @return the backup filename reported by the target, or {@code null} if the backup failed
     */
    private String createPreSyncBackup(String targetBaseUrl, List<String> errors) {
        try {
            JsonNode result = httpClient.httpPost(
                    targetBaseUrl + "/system/backup?reason=pre-sync-definitions",
                    null
            );
            if (result == null) {
                errors.add("Pre-sync backup failed: target returned no response — sync continued without rollback point");
                log.warn("Pre-sync backup on {} returned null", targetBaseUrl);
                return null;
            }
            String filename = result.path("filename").asText(null);
            if (filename != null) {
                log.info("Pre-sync backup created on target: {}", filename);
            }
            return filename;
        } catch (Exception e) {
            errors.add("Pre-sync backup failed: " + e.getMessage() + " — sync continued without rollback point");
            log.warn("Pre-sync backup failed on {}, continuing anyway", targetBaseUrl, e);
            return null;
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
}
