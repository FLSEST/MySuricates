package com.automation.service.wish;

import com.automation.entity.*;
import com.automation.repository.*;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.service.learning.LearningService;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.workflow.AgentInstructionsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WishPromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "will", "have", "been",
            "are", "was", "were", "not", "but", "all", "can", "had", "her", "his",
            "one", "our", "out", "you", "its", "also", "into", "just", "than", "them",
            "then", "what", "when", "who", "how", "about", "which", "would", "there",
            "their", "should", "could", "does", "each", "more", "some", "such", "only",
            "der", "die", "das", "und", "ein", "eine", "ist", "ich", "sie", "den",
            "auf", "mit", "dem", "nicht", "sich", "des", "von", "aus", "hat", "noch",
            "nach", "bei", "wie", "nur", "über", "aber", "vor", "auch", "dann", "soll",
            "für", "oder", "wird", "alle", "mein", "dein", "sein", "ihr", "wir"
    );

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s,;:.!?()\\[\\]{}\"/]+");

    private static final Pattern ANALYSIS_PATTERN = Pattern.compile(
            "(?i)(analy|audit|review|security|vulnerabilit|architect|assess|inspect|evaluat|quality|scorecard|coverage|debt|radar)",
            Pattern.CASE_INSENSITIVE);

    private final WorkflowRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final SkillRepository skillRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowMemoryRepository memoryRepository;
    private final WorkflowPoolService workflowPoolService;
    private final AgentInstructionsService agentInstructionsService;
    private final LearningService learningService;
    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final StepResultRepository stepResultRepository;

    public WishPromptBuilder(
            WorkflowRepository workflowRepository,
            ProjectRepository projectRepository,
            SkillRepository skillRepository,
            WorkflowTemplateRepository templateRepository,
            WorkflowMemoryRepository memoryRepository,
            WorkflowPoolService workflowPoolService,
            AgentInstructionsService agentInstructionsService,
            LearningService learningService,
            WorkflowRunRepository workflowRunRepository,
            ChainRunRepository chainRunRepository,
            StepResultRepository stepResultRepository
    ) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.skillRepository = skillRepository;
        this.templateRepository = templateRepository;
        this.memoryRepository = memoryRepository;
        this.workflowPoolService = workflowPoolService;
        this.agentInstructionsService = agentInstructionsService;
        this.learningService = learningService;
        this.workflowRunRepository = workflowRunRepository;
        this.chainRunRepository = chainRunRepository;
        this.stepResultRepository = stepResultRepository;
    }

    public String buildDecomposePrompt(Wish wish) {
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
        Set<String> keywords = extractKeywords(wish.getOriginalText());
        Set<String> shownIds = new HashSet<>();

        // 1. Referenced Workflows (user-selected hints) — always shown with details
        if (wish.getReferenceWorkflowIds() != null && !wish.getReferenceWorkflowIds().isEmpty()) {
            sb.append("### Referenced Workflows (user-selected)\n\n");
            for (String refId : wish.getReferenceWorkflowIds()) {
                workflowRepository.findById(refId).ifPresent(wf -> {
                    String pName = wf.getProject() != null ? wf.getProject().getName() : "?";
                    sb.append("- **").append(wf.getName()).append("** (`").append(wf.getId())
                            .append("`, Project: ").append(pName).append("): ");
                    if (wf.getDescription() != null) sb.append(wf.getDescription());
                    sb.append("\n");
                    sb.append("  Pipeline: ").append(formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                });
            }
            sb.append("\n");
        }

        // 2. Current project workflows — always show all
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
                    sb.append("  Pipeline: ").append(formatBlockPipeline(wf.getBlocks())).append("\n");
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
                int score = scoreRelevance(keywords, wf.getName(), wf.getDescription(), wf.getBlocks());
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
                    sb.append("  Pipeline: ").append(formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                }
                sb.append("\n");
            }
        }

        // 4. Templates + Pool workflows — keyword-scored, top 10 templates + top 5 pool
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
                int score = scoreRelevance(keywords, tmplName, tmplDesc, tmpl.getBlocks());
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
                line.append("  Pipeline: ").append(formatBlockPipeline(tmpl.getBlocks())).append("\n");
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
                int score = scoreRelevance(keywords, peName, peDesc, pe.blocks());
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
                line.append("  Pipeline: ").append(formatBlockPipeline(pe.blocks())).append("\n");
                matchingEntries.add(line.toString());
            }
        }

        if (!matchingEntries.isEmpty()) {
            sb.append("### Matching templates & pool workflows\n\n");
            matchingEntries.forEach(sb::append);
            sb.append("\n");
        }

        // Execution Intelligence from past runs
        sb.append(buildExecutionIntelligence(wish.getProjectId()));

        // Add available skills
        List<Skill> skills = skillRepository.findAll();
        if (!skills.isEmpty()) {
            sb.append("## Available Skills\n\n");
            for (Skill skill : skills) {
                sb.append("- **").append(skill.getName()).append("**: ").append(skill.getDescription()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public String buildDecomposeSystemPrompt(Wish wish) {
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
                14. Set `model` for each workflow: Use "claude-opus-4-6" for analysis, audits, architecture reviews, security scans, and complex reasoning tasks. Use null for standard automation workflows

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
                      "model": "claude-opus-4-6 | claude-sonnet-4-6 | null",
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

    @SuppressWarnings("unchecked")
    public String buildEvaluatePrompt(Wish wish) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original Wish\n\n");
        sb.append(wish.getOriginalText()).append("\n\n");

        // Include decomposition summary (truncate if very large)
        sb.append("## Generated Workflow Structure\n\n");
        if (wish.getDecomposition() != null) {
            String decompositionJson = MAPPER.valueToTree(wish.getDecomposition()).toPrettyString();
            if (decompositionJson.length() > 3000) {
                sb.append("Analysis: ").append(wish.getDecomposition().getOrDefault("analysis", "")).append("\n");
                sb.append("Strategy: ").append(wish.getDecomposition().getOrDefault("strategy", "")).append("\n");
                Object wfs = wish.getDecomposition().get("workflows");
                if (wfs instanceof List<?> wfList) {
                    for (Object wfObj : wfList) {
                        if (wfObj instanceof Map<?, ?> wfMap) {
                            sb.append("- Workflow: ").append(wfMap.get("name")).append("\n");
                        }
                    }
                }
                sb.append("\n");
            } else {
                sb.append(decompositionJson).append("\n\n");
            }
        }

        sb.append("## Execution Results\n\n");

        if (wish.getGeneratedChainId() != null && wish.getCurrentRunId() != null) {
            chainRunRepository.findById(wish.getCurrentRunId()).ifPresent(chainRun -> {
                sb.append("Chain Status: ").append(chainRun.getStatus()).append("\n");
                if (chainRun.getError() != null) {
                    sb.append("Chain Error: ").append(chainRun.getError()).append("\n");
                }
                for (ChainRunEntry entry : chainRun.getEntryRuns()) {
                    sb.append("- Workflow '").append(entry.getWorkflowName())
                            .append("': ").append(entry.getStatus()).append("\n");
                    if (entry.getWorkflowRunId() != null) {
                        appendWorkflowRunOutput(sb, entry.getWorkflowRunId());
                    }
                }
            });
        } else if (wish.getCurrentRunId() != null) {
            appendWorkflowRunOutput(sb, wish.getCurrentRunId());
        }

        // Add execution cost context
        if (wish.getCurrentRunId() != null) {
            try {
                Map<String, Object> runInsights = learningService.getRunInsights(wish.getCurrentRunId());
                double cost = ((Number) runInsights.getOrDefault("totalCostUsd", 0.0)).doubleValue();
                long tokens = ((Number) runInsights.getOrDefault("totalTokens", 0L)).longValue();
                long durationMs = ((Number) runInsights.getOrDefault("durationMs", 0L)).longValue();
                if (cost > 0 || tokens > 0) {
                    sb.append("\n## Execution Cost\n\n");
                    sb.append(String.format("- Cost: $%.4f, Tokens: %d, Duration: %.0fs\n",
                            cost, tokens, durationMs / 1000.0));
                    sb.append("- Consider cost efficiency when suggesting improvements\n\n");
                }
            } catch (Exception e) {
                log.debug("Could not add run insights to evaluate prompt: {}", e.getMessage());
            }
        }

        // Add verification results if available
        if (wish.getMetadata() != null && wish.getMetadata().get("verificationResults") instanceof List<?> vResults && !vResults.isEmpty()) {
            sb.append("## Pre-flight Verification Results\n\n");
            for (Object vr : vResults) {
                if (vr instanceof Map<?, ?> vrMap) {
                    sb.append("- Workflow ").append(vrMap.get("workflowId"))
                            .append(": ").append(vrMap.get("status"));
                    Object err = vrMap.get("error");
                    if (err != null && !String.valueOf(err).isBlank()) {
                        sb.append(" (").append(WishStringUtil.truncate(String.valueOf(err), 100)).append(")");
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        // Cap total prompt size to prevent empty/truncated Claude responses
        String task = """

                ## Task

                Evaluate whether the wish has been fulfilled.

                CRITICAL: Your ENTIRE response must be a single JSON object. No text before or after. No markdown fences. No explanations. Just the raw JSON object starting with { and ending with }.

                {
                  "satisfied": true/false,
                  "summary": "What was achieved",
                  "issues": ["Issue 1", "Issue 2"],
                  "suggestions": ["Improvement 1", "Improvement 2"],
                  "shouldRetry": true/false
                }
                """;

        int maxContextLength = 15000;
        if (sb.length() > maxContextLength) {
            log.info("Wish evaluation prompt truncated from {} to {} chars", sb.length(), maxContextLength);
            sb.setLength(maxContextLength);
            sb.append("\n\n[... context truncated for prompt size ...]\n");
        }
        sb.append(task);

        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────

    String formatBlockPipeline(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.isEmpty()) return "[empty]";
        return blocks.stream().map(b -> {
            String type = b.getType() != null ? b.getType() : "?";
            Map<String, Object> cfg = b.getConfig();
            String label = "";
            if (cfg != null) {
                if (cfg.containsKey("command")) {
                    label = WishStringUtil.truncate(String.valueOf(cfg.get("command")), 40);
                } else if (cfg.containsKey("goal")) {
                    String goal = WishStringUtil.truncate(String.valueOf(cfg.get("goal")), 30);
                    Object maxIter = cfg.get("max_iterations");
                    label = goal + (maxIter != null ? " (" + maxIter + " iter)" : "");
                }
            }
            return label.isEmpty() ? "[" + type + "]" : "[" + type + ": " + label + "]";
        }).collect(Collectors.joining(" → "));
    }

    Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(WORD_SPLIT.split(text.toLowerCase()))
                .filter(w -> w.length() >= 3)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    int scoreRelevance(Set<String> keywords, String name, String description, List<BlockDefinition> blocks) {
        if (keywords.isEmpty()) return 0;
        int score = 0;
        String nameLower = name != null ? name.toLowerCase() : "";
        String descLower = description != null ? description.toLowerCase() : "";

        for (String kw : keywords) {
            if (nameLower.contains(kw)) score += 3;
            if (descLower.contains(kw)) score += 2;
        }

        if (blocks != null) {
            for (BlockDefinition block : blocks) {
                if (block.getConfig() == null) continue;
                String configStr = block.getConfig().values().stream()
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(Collectors.joining(" "))
                        .toLowerCase();
                for (String kw : keywords) {
                    if (configStr.contains(kw)) {
                        score += 1;
                        break;
                    }
                }
            }
        }
        return score;
    }

    public String inferModelFromBlocks(List<BlockDefinition> blocks, String description) {
        if (description != null && ANALYSIS_PATTERN.matcher(description).find()) {
            return "claude-opus-4-6";
        }
        for (BlockDefinition block : blocks) {
            if ("agentic".equals(block.getType()) && block.getConfig() != null) {
                String goal = (String) block.getConfig().getOrDefault("goal", "");
                if (ANALYSIS_PATTERN.matcher(goal).find()) {
                    return "claude-opus-4-6";
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String buildExecutionIntelligence(String projectId) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Workflow> projectWorkflows = projectId != null
                    ? workflowRepository.findByProjectId(projectId)
                    : List.of();

            List<String> performanceLines = new ArrayList<>();
            double totalCost = 0;
            long totalRuns = 0;
            Set<String> allModels = new LinkedHashSet<>();
            List<String> pitfalls = new ArrayList<>();

            for (Workflow wf : projectWorkflows) {
                Map<String, Object> stats = learningService.getWorkflowStats(wf.getId());
                int runs = ((Number) stats.getOrDefault("totalRuns", 0)).intValue();
                if (runs < 2) continue;

                double successRate = ((Number) stats.getOrDefault("successRate", 0.0)).doubleValue();
                double avgDuration = ((Number) stats.getOrDefault("avgDurationMs", 0.0)).doubleValue();
                totalRuns += runs;

                Map<String, Object> insights = learningService.getExecutionInsights(wf.getId());
                double cost = ((Number) insights.getOrDefault("avgCostPerRunUsd", 0.0)).doubleValue();
                totalCost += ((Number) insights.getOrDefault("totalCostUsd", 0.0)).doubleValue();
                Object models = insights.get("modelsUsed");
                if (models instanceof List<?> modelList) {
                    modelList.forEach(m -> allModels.add(String.valueOf(m)));
                }

                performanceLines.add(String.format(
                        "- **%s** (%d runs): %.0f%% success, avg %.0fs, $%.3f/run",
                        wf.getName(), runs, successRate * 100, avgDuration / 1000.0, cost));

                List<Map<String, Object>> suggestions = learningService.getOptimizationSuggestions(wf.getId());
                for (Map<String, Object> sug : suggestions) {
                    String severity = (String) sug.getOrDefault("severity", "");
                    if ("HIGH".equals(severity)) {
                        pitfalls.add("- " + sug.getOrDefault("message", ""));
                    }
                }
            }

            if (performanceLines.isEmpty() && projectWorkflows.size() < 2) {
                return "";
            }

            sb.append("## Execution Intelligence (from past runs)\n\n");

            if (!performanceLines.isEmpty()) {
                sb.append("### Workflow Performance\n\n");
                performanceLines.stream().limit(10).forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }

            if (!pitfalls.isEmpty()) {
                sb.append("### Common Issues to Avoid\n\n");
                pitfalls.stream().distinct().limit(5).forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }

            int agenticRunsTotal = 0;
            int goalsAchieved = 0;
            double iterationsSum = 0;
            int iterationsCount = 0;

            for (Workflow wf : projectWorkflows) {
                Map<String, Object> agenticAnalytics = learningService.getAgenticAnalytics(wf.getId());
                int agRuns = ((Number) agenticAnalytics.getOrDefault("totalAgenticRuns", 0)).intValue();
                if (agRuns == 0) continue;
                agenticRunsTotal += agRuns;
                goalsAchieved += ((Number) agenticAnalytics.getOrDefault("achievedGoals", 0)).intValue();
                double avgIter = ((Number) agenticAnalytics.getOrDefault("avgIterationsToGoal", 0.0)).doubleValue();
                if (avgIter > 0) {
                    iterationsSum += avgIter * agRuns;
                    iterationsCount += agRuns;
                }
            }

            if (agenticRunsTotal > 0) {
                double agenticSuccessRate = (double) goalsAchieved / agenticRunsTotal;
                double avgIter = iterationsCount > 0 ? iterationsSum / iterationsCount : 0;
                sb.append("### Agentic Block Insights\n\n");
                sb.append(String.format("- %d agentic runs total, %.0f%% goal achieved\n",
                        agenticRunsTotal, agenticSuccessRate * 100));
                if (avgIter > 0) {
                    sb.append(String.format("- Avg iterations to success: %.1f\n", avgIter));
                }
                sb.append("\n");
            }

            if (totalRuns > 0) {
                sb.append("### Cost Profile\n\n");
                sb.append(String.format("- Total project cost (recent): $%.2f across %d runs\n", totalCost, totalRuns));
                if (!allModels.isEmpty()) {
                    sb.append("- Models used: ").append(String.join(", ", allModels)).append("\n");
                }
                sb.append("\n");
            }

        } catch (Exception e) {
            log.debug("Could not build execution intelligence: {}", e.getMessage());
        }
        return sb.toString();
    }

    private void appendWorkflowRunOutput(StringBuilder sb, String runId) {
        workflowRunRepository.findById(runId).ifPresent(run -> {
            sb.append("  **Run Status: ").append(run.getStatus()).append("**\n");
            if (run.getError() != null && !run.getError().isEmpty()) {
                sb.append("  Error: ").append(WishStringUtil.truncate(run.getError().toString(), 200)).append("\n");
            }
            List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(runId);
            int maxDetailedSteps = 8;
            int startIdx = Math.max(0, steps.size() - maxDetailedSteps);

            if (startIdx > 0) {
                long succeeded = steps.subList(0, startIdx).stream()
                        .filter(s -> "COMPLETED".equals(String.valueOf(s.getStatus())))
                        .count();
                sb.append("  [Steps 0-").append(startIdx - 1).append(": ")
                        .append(succeeded).append("/").append(startIdx).append(" succeeded]\n");
            }

            for (int i = startIdx; i < steps.size(); i++) {
                StepResult step = steps.get(i);
                String blockType = step.getMetadata() != null
                        ? (String) step.getMetadata().getOrDefault("blockType", "unknown")
                        : "unknown";
                sb.append("  Step ").append(step.getStepIndex()).append(" (").append(blockType).append("): ");
                sb.append(step.getStatus()).append("\n");
                if (step.getOutput() != null) {
                    sb.append("    Output: ").append(WishStringUtil.truncate(step.getOutput(), 300)).append("\n");
                }
            }
        });
    }
}
