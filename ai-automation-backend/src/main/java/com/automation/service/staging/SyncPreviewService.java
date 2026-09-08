package com.automation.service.staging;

import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncPreviewItem;
import com.automation.dto.response.WorkflowDiff;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dry-run of a definition sync: compares the local projects, workflows, skills and chains with
 * the target instance and reports NEW / UPDATE / SKIP per item, including a field-level
 * workflow diff.
 *
 * <p>Extracted from {@code InstanceSyncHelper} (Audit-TODO 189ad41b). Reads only; the actual
 * sync stays in the helper.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncPreviewService {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final WorkflowChainRepository chainRepository;
    private final ObjectMapper objectMapper;
    private final InstanceHttpClient httpClient;

    // ── Target maps record ──────────────────────────────────

    private record TargetMaps(
            Map<String, JsonNode> projectsByNamePath,
            Map<String, JsonNode> workflowsByNameProject,
            Set<String> skillNames,
            Map<String, JsonNode> chainsByName
    ) {}

    private TargetMaps buildTargetMaps(String targetBaseUrl) {
        Map<String, JsonNode> projectsByNamePath = new HashMap<>();
        JsonNode targetProjects = httpClient.httpGet(targetBaseUrl + "/projects");
        if (targetProjects != null && targetProjects.isArray()) {
            for (JsonNode sp : targetProjects) {
                String key = sp.path("name").asText() + "|" + sp.path("path").asText();
                projectsByNamePath.put(key, sp);
            }
        }

        Map<String, JsonNode> workflowsByNameProject = new HashMap<>();
        JsonNode targetWorkflows = httpClient.httpGet(targetBaseUrl + "/workflows");
        if (targetWorkflows != null && targetWorkflows.isArray()) {
            for (JsonNode sw : targetWorkflows) {
                String wKey = sw.path("name").asText() + "|" + sw.path("projectName").asText();
                workflowsByNameProject.put(wKey, sw);
            }
        }

        Set<String> skillNames = new HashSet<>();
        JsonNode targetSkills = httpClient.httpGet(targetBaseUrl + "/skills");
        if (targetSkills != null && targetSkills.isArray()) {
            for (JsonNode ss : targetSkills) {
                skillNames.add(ss.path("name").asText());
            }
        }

        Map<String, JsonNode> chainsByName = new HashMap<>();
        JsonNode targetChains = httpClient.httpGet(targetBaseUrl + "/chains");
        if (targetChains != null && targetChains.isArray()) {
            for (JsonNode sc : targetChains) {
                chainsByName.put(sc.path("name").asText(), sc);
            }
        }

        return new TargetMaps(projectsByNamePath, workflowsByNameProject, skillNames, chainsByName);
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

        List<SyncPreviewItem> chains = new ArrayList<>();
        for (WorkflowChain chain : chainRepository.findAll()) {
            String action = target.chainsByName().containsKey(chain.getName()) ? "UPDATE" : "NEW";
            chains.add(new SyncPreviewItem(chain.getId(), chain.getName(), null, action));
        }

        return new SyncPreview(projects, workflows, skills, chains);
    }

    public SyncPreview syncPreview(String targetBaseUrl, String targetApiKey) {
        httpClient.setTargetApiKeyOverride(targetApiKey);
        try {
            return syncPreview(targetBaseUrl);
        } finally {
            httpClient.clearTargetApiKeyOverride();
        }
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
}
