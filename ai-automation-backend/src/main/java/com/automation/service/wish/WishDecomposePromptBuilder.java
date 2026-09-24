package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.*;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.repository.*;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.workflow.AgentInstructionsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the decompose prompt and system prompt for wishes.
 * Extracted from WishPromptBuilder to separate decompose-specific logic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WishDecomposePromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowMemoryRepository memoryRepository;
    private final WorkflowPoolService workflowPoolService;
    private final AgentInstructionsService agentInstructionsService;
    private final WishPromptHelper helper;
    private final WishConfig wishConfig;

    public String buildPrompt(Wish wish) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Wish\n\n");
        sb.append(wish.getOriginalText()).append("\n\n");

        // Add clarification Q&A history
        if (wish.getMetadata() != null && wish.getMetadata().get("clarifications") instanceof List<?> clarifications && !clarifications.isEmpty()) {
            sb.append("## Clarifications from User\n\n");
            for (Object c : clarifications) {
                if (c instanceof Map<?, ?> cMap) {
                    sb.append("**Q:** ").append(cMap.get("question")).append("\n");
                    sb.append("**A:** ").append(cMap.get("answer")).append("\n\n");
                }
            }
        }

        // Add user feedback from refinement
        if (wish.getMetadata() != null && wish.getMetadata().get("userFeedback") instanceof String feedback && !feedback.isBlank()) {
            sb.append("## User Feedback\n\n");
            sb.append(feedback).append("\n\n");
            sb.append("Please adjust the workflow design based on this feedback.\n\n");
        }

        // Add feedback from previous iterations
        if (wish.getMetadata() != null && wish.getMetadata().get("evaluations") instanceof List<?> evals && !evals.isEmpty()) {
            sb.append("## Previous Iteration Feedback\n\n");
            Object lastEval = evals.get(evals.size() - 1);
            sb.append(MAPPER.valueToTree(lastEval).toPrettyString()).append("\n\n");
            sb.append("The previous attempt was not satisfactory. Please adjust the workflow design based on this feedback.\n\n");
        }

        // Add project context
        String projectName = null;
        if (wish.getProjectId() != null) {
            Project project = projectRepository.findById(wish.getProjectId()).orElse(null);
            if (project != null) {
                projectName = project.getName();
                sb.append("## Target Project\n\n");
                sb.append("- **Name**: ").append(project.getName()).append("\n");
                if (project.getPath() != null) {
                    sb.append("- **Path**: ").append(project.getPath()).append("\n");
                }
                if (project.getDescription() != null && !project.getDescription().isBlank()) {
                    sb.append("- **Description**: ").append(project.getDescription()).append("\n");
                }
                sb.append("\n");
            }

            // Add active workflow memories from project workflows
            List<WorkflowMemory> memories = new ArrayList<>();
            for (Workflow wf : workflowRepository.findByProjectId(wish.getProjectId())) {
                memoryRepository.findByWorkflowIdAndActiveTrue(wf.getId()).ifPresent(memories::add);
            }
            if (!memories.isEmpty()) {
                sb.append("## Accumulated Project Knowledge (from previous workflow runs)\n\n");
                for (WorkflowMemory mem : memories) {
                    sb.append("### ").append(mem.getTitle()).append("\n");
                    sb.append(WishStringUtil.truncate(mem.getContent(), 1000)).append("\n\n");
                }
            }
        }

        // ── Reusable Components (keyword-filtered, cross-project) ────────
        sb.append("## Reusable Components\n\n");
        Set<String> keywords = helper.extractKeywords(wish.getOriginalText());
        Set<String> shownIds = new HashSet<>();

        // 1. Referenced Workflows (user-selected hints)
        if (wish.getReferenceWorkflowIds() != null && !wish.getReferenceWorkflowIds().isEmpty()) {
            sb.append("### Referenced Workflows (user-selected)\n\n");
            for (String refId : wish.getReferenceWorkflowIds()) {
                workflowRepository.findById(refId).ifPresent(wf -> {
                    String pName = wf.getProject() != null ? wf.getProject().getName() : "?";
                    sb.append("- **").append(wf.getName()).append("** (`").append(wf.getId())
                            .append("`, Project: ").append(pName).append("): ");
                    if (wf.getDescription() != null) sb.append(wf.getDescription());
                    sb.append("\n");
                    sb.append("  Pipeline: ").append(helper.formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                });
            }
            sb.append("\n");
        }

        // 2. Current project workflows
        if (wish.getProjectId() != null) {
            List<Workflow> currentProjectWfs = workflowRepository.findByProjectId(wish.getProjectId());
            if (!currentProjectWfs.isEmpty()) {
                sb.append("### ").append(projectName != null ? projectName : "Current Project")
                        .append(" (current project — use as sub-workflow blocks)\n\n");
                for (Workflow wf : currentProjectWfs) {
                    if (shownIds.contains(wf.getId())) continue;
                    sb.append("- **").append(wf.getName()).append("** (`").append(wf.getId()).append("`)");
                    if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                        sb.append(": ").append(WishStringUtil.truncate(wf.getDescription(), 120));
                    }
                    sb.append("\n");
                    sb.append("  Pipeline: ").append(helper.formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                }
                sb.append("\n");
            }
        }

        // 3. Other project workflows — keyword-scored, top 10
        List<Workflow> allWorkflows = workflowRepository.findAll();
        List<Workflow> otherProjectWfs = allWorkflows.stream()
                .filter(wf -> !shownIds.contains(wf.getId()))
                .filter(wf -> wish.getProjectId() == null || !wish.getProjectId().equals(
                        wf.getProject() != null ? wf.getProject().getId() : null))
                .toList();

        if (!otherProjectWfs.isEmpty() && !keywords.isEmpty()) {
            List<Map.Entry<Workflow, Integer>> scored = new ArrayList<>();
            for (Workflow wf : otherProjectWfs) {
                int score = helper.scoreRelevance(keywords, wf.getName(), wf.getDescription(), wf.getBlocks());
                if (score > 0) scored.add(Map.entry(wf, score));
            }
            scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            List<Map.Entry<Workflow, Integer>> topCross = scored.stream().limit(10).toList();
            if (!topCross.isEmpty()) {
                sb.append("### Relevant workflows from other projects\n\n");
                for (var entry : topCross) {
                    Workflow wf = entry.getKey();
                    String pName = wf.getProject() != null ? wf.getProject().getName() : "?";
                    sb.append("- **").append(wf.getName()).append("** (`").append(wf.getId())
                            .append("`, Project: ").append(pName).append(")");
                    if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                        sb.append(": ").append(WishStringUtil.truncate(wf.getDescription(), 120));
                    }
                    sb.append("\n");
                    sb.append("  Pipeline: ").append(helper.formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                }
                sb.append("\n");
            }
        }

        // 4. Templates + Pool workflows — keyword-scored
        appendMatchingTemplatesAndPool(sb, keywords);

        // Execution Intelligence from past runs
        sb.append(helper.buildExecutionIntelligence(wish.getProjectId()));

        // Add available skills
        helper.appendAvailableSkills(sb);

        return sb.toString();
    }

    public String buildSystemPrompt(Wish wish) {
        // Base agent instructions first, for consistent behavior
        String agentBase = agentInstructionsService.buildInstructionPrefix(null);
        return (agentBase.isBlank() ? "" : agentBase + "\n\n---\n\n") + WishSystemPrompt.text(wishConfig);
    }

    private void appendMatchingTemplatesAndPool(StringBuilder sb, Set<String> keywords) {
        List<WorkflowTemplate> templates = templateRepository.findAllByOrderBySortOrderAsc();
        List<PoolWorkflowEntry> poolEntries = workflowPoolService.listAll();

        List<String> matchingEntries = new ArrayList<>();

        if (!templates.isEmpty() && !keywords.isEmpty()) {
            List<Map.Entry<WorkflowTemplate, Integer>> scoredTemplates = new ArrayList<>();
            for (WorkflowTemplate tmpl : templates) {
                String tmplName = tmpl.getName().getOrDefault("en",
                        tmpl.getName().values().isEmpty() ? "" : tmpl.getName().values().iterator().next());
                String tmplDesc = tmpl.getDescription() != null
                        ? tmpl.getDescription().getOrDefault("en", "") : "";
                int score = helper.scoreRelevance(keywords, tmplName, tmplDesc, tmpl.getBlocks());
                if (score > 0) scoredTemplates.add(Map.entry(tmpl, score));
            }
            scoredTemplates.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            for (var entry : scoredTemplates.stream().limit(10).toList()) {
                WorkflowTemplate tmpl = entry.getKey();
                String tmplName = tmpl.getName().getOrDefault("en",
                        tmpl.getName().values().iterator().next());
                StringBuilder line = new StringBuilder();
                line.append("- **").append(tmplName).append("** [Template]");
                String tmplDesc = tmpl.getDescription() != null
                        ? tmpl.getDescription().getOrDefault("en", "") : "";
                if (!tmplDesc.isBlank()) {
                    line.append(": ").append(WishStringUtil.truncate(tmplDesc, 120));
                }
                line.append("\n");
                line.append("  Pipeline: ").append(helper.formatBlockPipeline(tmpl.getBlocks())).append("\n");
                matchingEntries.add(line.toString());
            }
        }

        if (!poolEntries.isEmpty() && !keywords.isEmpty()) {
            List<Map.Entry<PoolWorkflowEntry, Integer>> scoredPool = new ArrayList<>();
            for (PoolWorkflowEntry pe : poolEntries) {
                String peName = pe.name().getOrDefault("en",
                        pe.name().values().isEmpty() ? "" : pe.name().values().iterator().next());
                String peDesc = pe.description() != null
                        ? pe.description().getOrDefault("en", "") : "";
                int score = helper.scoreRelevance(keywords, peName, peDesc, pe.blocks());
                if (score > 0) scoredPool.add(Map.entry(pe, score));
            }
            scoredPool.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            for (var entry : scoredPool.stream().limit(5).toList()) {
                PoolWorkflowEntry pe = entry.getKey();
                String peName = pe.name().getOrDefault("en",
                        pe.name().values().iterator().next());
                StringBuilder line = new StringBuilder();
                line.append("- **").append(peName).append("** [Pool]");
                String peDesc = pe.description() != null
                        ? pe.description().getOrDefault("en", "") : "";
                if (!peDesc.isBlank()) {
                    line.append(": ").append(WishStringUtil.truncate(peDesc, 120));
                }
                line.append("\n");
                line.append("  Pipeline: ").append(helper.formatBlockPipeline(pe.blocks())).append("\n");
                matchingEntries.add(line.toString());
            }
        }

        if (!matchingEntries.isEmpty()) {
            sb.append("### Matching templates & pool workflows\n\n");
            matchingEntries.forEach(sb::append);
            sb.append("\n");
        }
    }
}
