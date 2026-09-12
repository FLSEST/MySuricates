package com.automation.service.wish;

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
        StringBuilder sb = new StringBuilder();

        // Inject base agent instructions for consistent behavior
        String agentBase = agentInstructionsService.buildInstructionPrefix(null);
        if (!agentBase.isBlank()) {
            sb.append(agentBase).append("\n\n---\n\n");
        }

        sb.append("""
                You are a workflow architect. Your job is to decompose a user's wish into executable workflow blocks.

                ## Available Block Types

                - **simple**: Runs a single Claude CLI prompt. Config: `{"command": "prompt text"}`. Use for tasks that need AI reasoning.
                - **shell**: Runs a shell command. Config: `{"command": "shell command", "container_image": "image:tag"}`. Use for deterministic tasks (file ops, git, API calls, etc.). See "Ausfuehrungsumgebung" section above for available host tools and container images.
                - **agentic**: Multi-turn AI loop with goal. Config: `{"goal": "description", "max_iterations": 10}`. Use for complex tasks requiring exploration and multi-step work.
                - **conditional**: Branching logic. Config: `{"condition": "expression", "then_blocks": [...], "else_blocks": [...]}`.
                - **forEach**: Iterates over items. Config: `{"items": "expression", "variable": "item", "blocks": [...]}`.
                - **retry**: Retries on failure. Config: `{"command": "...", "max_retries": 3}`.
                - **sub-workflow**: Runs an existing workflow as a nested block. Config: `{"workflow_id": "id"}`. Use for reuse.

                ## Design Principles

                1. Prefer **shell** blocks for deterministic tasks (faster, reproducible)
                2. Follow the "Ausfuehrungsumgebung" rules for `container_image` — set it when shell blocks need tools beyond the host environment.
                3. Use **simple** blocks for single AI reasoning steps
                4. Use **agentic** blocks only when the task requires multi-step exploration
                5. Agentic block prompts (goal) must be self-contained — include absolute paths, specific names, and exact instructions
                6. Keep workflows focused — one workflow per coherent task
                7. Use a chain when tasks are logically separate but sequential
                8. Set realistic `max_iterations` for agentic blocks (3-10)
                9. Add shell cleanup steps before agentic blocks if stale data from previous runs could interfere
                10. Use `{{ previous_output }}` in block prompts to reference the output of the previous step
                11. Reference existing workflows via sub-workflow blocks instead of duplicating logic — check "Reusable Components" for workflows from ALL projects, not just the current one
                12. Workflows from other projects can be reused as sub-workflows — use their ID in the `workflow_id` config field
                13. User-selected "Referenced Workflows" should be prioritized for reuse when they match the task
                14. Set `model` for each workflow: Use "opus" (alias for the newest Opus model) for analysis, audits, architecture reviews, security scans, and complex reasoning tasks. Use null for standard automation workflows

                ## Timeout Guidelines

                Timeouts are applied automatically, but you can override them in block config:
                - **shell** blocks: `timeout_seconds` (default: 600). Increase to 900+ for long builds or large downloads.
                - **simple** blocks: `timeout_seconds` (default: 600). Usually sufficient.
                - **agentic** blocks: `timeout_seconds` (default: 900 per CLI call), `iteration_timeout_seconds` (default: 900 per iteration), `total_timeout_seconds` (default: 3600 overall). For complex tasks with Opus model, use 900/900/3600. IMPORTANT: `iteration_timeout_seconds` must be >= `timeout_seconds`.

                ## Output Format

                Respond with ONLY a JSON object:
                ```json
                {
                  "analysis": "Brief analysis of the wish",
                  "strategy": "Chosen implementation strategy",
                  "workflows": [
                    {
                      "name": "Workflow Name",
                      "description": "What this workflow does",
                      "model": "opus | sonnet | null",
                      "blocks": [
                        {"type": "shell", "config": {"command": "npm run lint"}},
                        {"type": "agentic", "config": {"goal": "Fix all lint errors", "max_iterations": 5}}
                      ]
                    }
                  ],
                  "chain": {
                    "name": "Chain Name",
                    "stopOnFailure": true
                  },
                  "needsClarification": false,
                  "clarificationQuestion": null
                }
                ```

                If the wish is too vague, set `needsClarification: true` and provide a `clarificationQuestion`.
                The `chain` field is only needed when generating multiple workflows.

                CRITICAL: Your ENTIRE response must be a single JSON object. No text before or after. No markdown fences. No explanations. Just the raw JSON object starting with { and ending with }.
                """);

        return sb.toString();
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
