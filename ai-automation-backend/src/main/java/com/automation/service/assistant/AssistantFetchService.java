package com.automation.service.assistant;

import com.automation.entity.BlockDefinition;
import com.automation.entity.IntegrationConnection;
import com.automation.entity.RunStatus;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.project.ProjectContextService;
import com.automation.util.TruncationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles dynamic fetch requests (the <fetch> tag mechanism) for assistant chat.
 * When the assistant's first response contains <fetch type="..." id="..."/> tags,
 * this service resolves them into supplemental context data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantFetchService {

    private static final Pattern FETCH_PATTERN =
            Pattern.compile("<fetch type=\"([^\"]+)\" id=\"([^\"]+)\"/>");

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final WishRepository wishRepository;
    private final SkillRepository skillRepository;
    private final IntegrationRegistry integrationRegistry;
    private final IntegrationConnectionRepository integrationConnectionRepository;
    private final WorkflowChainRepository workflowChainRepository;
    private final WorkflowPoolService workflowPoolService;
    private final ProjectContextService projectContextService;
    private final AssistantContextBuilder contextBuilder;

    public record FetchRequest(String type, String id) {}

    public List<FetchRequest> parseFetchRequests(String response) {
        if (response == null) return List.of();
        List<FetchRequest> result = new ArrayList<>();
        Matcher m = FETCH_PATTERN.matcher(response);
        while (m.find()) result.add(new FetchRequest(m.group(1), m.group(2)));
        return result;
    }

    public String stripFetchTags(String response) {
        if (response == null) return response;
        return FETCH_PATTERN.matcher(response).replaceAll("").trim();
    }

    public String executeFetch(FetchRequest req) {
        return switch (req.type()) {
            case "run_detail"       -> formatRunDetail(req.id());
            case "wish_detail"      -> formatWishDetail(req.id());
            case "workflow_runs"    -> formatWorkflowRuns(req.id());
            case "workflow_detail"  -> formatWorkflowDetail(req.id());
            case "workflow-full"    -> formatWorkflowFull(req.id());
            case "workflow-inputs"  -> formatWorkflowInputs(req.id());
            case "find_workflow"    -> formatFindWorkflow(req.id());
            case "step_output"      -> formatStepOutput(req.id());
            case "docs"             -> formatDocsSection(req.id());
            case "pool_workflows"   -> formatPoolWorkflows();
            case "pool_workflow"    -> formatPoolWorkflowDetail(req.id());
            case "project_context"  -> formatProjectContext(req.id());
            case "project_files"    -> formatProjectFiles(req.id());
            case "project_file"     -> formatProjectFile(req.id());
            case "skill"            -> formatSkillDetail(req.id());
            case "integration"      -> formatIntegrationDetail(req.id());
            case "chain"            -> formatChainDetail(req.id());
            case "block-spec"       -> formatBlockSpec(req.id());
            default -> "";
        };
    }

    // ── Fetch implementations ────────────────────────────────────────────────

    private String formatRunDetail(String id) {
        try {
            return workflowRunRepository.findByIdWithSteps(id).map(run -> {
                var sb = new StringBuilder();
                sb.append("**Run ").append(id, 0, Math.min(8, id.length())).append("…** — ");
                sb.append(contextBuilder.runLabel(run)).append(", Status: ").append(run.getStatus());
                if (run.getDurationMs() != null)
                    sb.append(" (").append(run.getDurationMs() / 1000).append("s)");
                sb.append("\n");
                String err = contextBuilder.extractError(run);
                if (err != null) sb.append("Run error: ").append(TruncationUtil.smart(err, 200, 400)).append("\n");
                var steps = run.getSteps();
                if (!steps.isEmpty()) {
                    sb.append("Steps:\n");
                    steps.forEach(s -> {
                        sb.append("  ").append(s.getStepIndex() + 1).append(". ").append(s.getStatus());
                        if (s.getStatus() == StepStatus.FAILED && s.getOutput() != null)
                            sb.append(": ").append(TruncationUtil.smart(s.getOutput(), 200, 600));
                        sb.append("\n");
                    });
                }
                return sb.toString();
            }).orElse("Run " + id + " not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch run detail for {}", id, e);
            return "";
        }
    }

    private String formatWishDetail(String id) {
        try {
            return wishRepository.findById(id).map(w -> {
                var sb = new StringBuilder();
                sb.append("**Wish ").append(id, 0, Math.min(8, id.length())).append("…** — [")
                        .append(w.getStatus()).append("] ").append(contextBuilder.truncate(w.getOriginalText(), 200))
                        .append("\n");
                sb.append("Iterations: ").append(w.getIterationCount())
                        .append("/").append(w.getMaxIterations()).append("\n");
                if (w.getError() != null)
                    sb.append("Error: ").append(contextBuilder.truncate(w.getError(), 300)).append("\n");
                return sb.toString();
            }).orElse("Wish " + id + " not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch wish detail for {}", id, e);
            return "";
        }
    }

    private String formatWorkflowDetail(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                String projectId = wf.getProject() != null ? wf.getProject().getId() : "—";
                sb.append("**Workflow: ").append(wf.getName()).append("** (").append(projectName).append(")\n");
                sb.append("Project ID: ").append(projectId).append("\n");
                if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                    sb.append("Description: ").append(contextBuilder.truncate(wf.getDescription().trim(), 150)).append("\n");
                }
                if (wf.getModel() != null && !wf.getModel().isBlank()) {
                    sb.append("Model: ").append(wf.getModel()).append("\n");
                }
                if (wf.getInstructions() != null && !wf.getInstructions().isBlank()) {
                    sb.append("Instructions: ").append(contextBuilder.truncate(wf.getInstructions().trim(), 400)).append("\n");
                }
                var blocks = wf.getBlocks();
                if (blocks != null && !blocks.isEmpty()) {
                    sb.append("\nBlocks (").append(blocks.size()).append("):\n");
                    for (int i = 0; i < blocks.size(); i++) {
                        BlockDefinition block = blocks.get(i);
                        Map<String, Object> cfg = block.getConfig() != null ? block.getConfig() : Map.of();
                        String label = cfg.containsKey("label") ? String.valueOf(cfg.get("label")) : "";
                        sb.append("  ").append(i + 1).append(". [").append(block.getType()).append("]");
                        if (!label.isBlank()) sb.append(" ").append(label);
                        sb.append("\n");
                        appendBlockConfigSummary(sb, block.getType(), cfg);
                    }
                }
                return sb.toString();
            }).orElse("Workflow " + workflowId + " not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow detail for {}", workflowId, e);
            return "";
        }
    }

    private String formatWorkflowRuns(String workflowId) {
        try {
            List<WorkflowRun> runs = workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc(workflowId);
            if (runs.isEmpty()) return "No runs for workflow " + workflowId + ".\n";
            var sb = new StringBuilder();
            sb.append("**Recent runs for workflow ").append(workflowId, 0, Math.min(8, workflowId.length()))
                    .append("…:**\n");
            runs.forEach(r -> {
                sb.append("  - [").append(contextBuilder.fmt(r.getStartedAt())).append("] ")
                        .append(r.getStatus()).append(" (link:").append(contextBuilder.runLink(r)).append(")");
                if (r.getStatus() == RunStatus.FAILED) {
                    String err = contextBuilder.extractError(r);
                    if (err != null) sb.append(" — ").append(contextBuilder.truncate(err, 100));
                }
                sb.append("\n");
            });
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not fetch workflow runs for {}", workflowId, e);
            return "";
        }
    }

    private String formatStepOutput(String idAndStep) {
        // Optional paging suffix: <runId>/<stepNumber>?offset=<n> or ?tail=<n>
        String idPart = idAndStep;
        String paging = null;
        int q = idAndStep.indexOf('?');
        if (q >= 0) {
            idPart = idAndStep.substring(0, q);
            paging = idAndStep.substring(q + 1);
        }
        int slash = idPart.lastIndexOf('/');
        if (slash < 0) return "Invalid format. Expected: <runId>/<stepNumber>\n";
        String runId = idPart.substring(0, slash);
        int stepNum;
        try {
            stepNum = Integer.parseInt(idPart.substring(slash + 1));
        } catch (NumberFormatException e) {
            return "Invalid step number: " + idPart.substring(slash + 1) + "\n";
        }
        Integer offset = null;
        Integer tailChars = null;
        if (paging != null) {
            try {
                if (paging.startsWith("offset=")) {
                    offset = Integer.parseInt(paging.substring("offset=".length()));
                } else if (paging.startsWith("tail=")) {
                    tailChars = Integer.parseInt(paging.substring("tail=".length()));
                } else {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                return "Invalid paging parameter: " + paging + ". Expected ?offset=<int> or ?tail=<int>\n";
            }
        }
        final Integer windowOffset = offset;
        final Integer tailLimit = tailChars;
        int targetIndex = stepNum - 1;
        return workflowRunRepository.findByIdWithSteps(runId).map(run ->
                run.getSteps().stream()
                        .filter(s -> s.getStepIndex() == targetIndex)
                        .findFirst()
                        .map(s -> {
                            var sb = new StringBuilder();
                            sb.append("**Step ").append(stepNum).append("** (").append(s.getStatus()).append(")");
                            if (s.getDurationMs() != null) sb.append(" — ").append(s.getDurationMs() / 1000).append("s");
                            sb.append("\n");
                            String out = s.getOutput();
                            if (out != null && !out.isBlank()) {
                                String rendered;
                                if (windowOffset != null) {
                                    rendered = TruncationUtil.window(out, windowOffset, 8000);
                                } else if (tailLimit != null) {
                                    rendered = TruncationUtil.tail(out, Math.min(tailLimit, 8000));
                                } else {
                                    // Tail-biased default: errors sit at the end of the output.
                                    rendered = TruncationUtil.smart(out, 2000, 6000);
                                }
                                sb.append(rendered).append("\n");
                            } else {
                                sb.append("(No output)\n");
                            }
                            return sb.toString();
                        })
                        .orElse("Step " + stepNum + " not found in run " + runId.substring(0, Math.min(8, runId.length())) + "…\n")
        ).orElse("Run " + runId + " not found.\n");
    }

    // ── Docs ─────────────────────────────────────────────────────────────────

    private static final Map<String, String> DOC_FILES = Map.of(
            "workflow-guide", "docs/WORKFLOW-GUIDE.md",
            "architektur",    "docs/architektur.md",
            "vision",         "docs/vision.md"
    );

    private static final Map<String, String> WF_GUIDE_SECTIONS = Map.ofEntries(
            Map.entry("quick-start",    "## 1."),
            Map.entry("block-types",    "## 2."),
            Map.entry("variables",      "## 3."),
            Map.entry("patterns",       "## 4."),
            Map.entry("timeouts",       "## 5."),
            Map.entry("models",         "## 6."),
            Map.entry("environment",    "## 7."),
            Map.entry("agentic",        "## 8."),
            Map.entry("memory",         "## 9."),
            Map.entry("chains",         "## 10."),
            Map.entry("dos-and-donts",  "## 11."),
            Map.entry("appendix",       "## Appendix")
    );

    private String formatDocsSection(String docId) {
        String baseId = docId;
        String sectionKey = null;
        int slash = docId.indexOf('/');
        if (slash >= 0) {
            baseId = docId.substring(0, slash);
            sectionKey = docId.substring(slash + 1);
        }

        String filename = DOC_FILES.get(baseId);
        if (filename == null) return "Unknown documentation: " + baseId + "\n";

        try {
            var resource = new ClassPathResource(filename);
            String fullContent = resource.getContentAsString(StandardCharsets.UTF_8);

            if (sectionKey == null) {
                return buildDocsToc(baseId, fullContent);
            }

            String sectionHeader = "workflow-guide".equals(baseId)
                    ? WF_GUIDE_SECTIONS.get(sectionKey) : null;
            if (sectionHeader == null) {
                String content = fullContent.length() > 8000
                        ? fullContent.substring(0, 8000) + "\n\n[Output truncated: 8000 of " + fullContent.length() + " characters]" : fullContent;
                return "**Documentation: " + baseId + "**\n\n" + content + "\n";
            }

            String section = extractSection(fullContent, sectionHeader);
            if (section.isBlank()) return "Section '" + sectionKey + "' not found in " + baseId + ".\n";
            if (section.length() > 12000) section = section.substring(0, 12000) + "\n\n[Output truncated: 12000 of " + section.length() + " characters]";
            return "**" + baseId + " / " + sectionKey + ":**\n\n" + section + "\n";

        } catch (Exception e) {
            log.debug("Could not load doc '{}': {}", docId, e.getMessage());
            return "Documentation '" + docId + "' not available.\n";
        }
    }

    private String buildDocsToc(String docId, String content) {
        var sb = new StringBuilder();
        sb.append("**Documentation: ").append(docId).append("** — Table of contents\n\n");
        content.lines()
                .filter(l -> l.startsWith("## "))
                .forEach(l -> sb.append(l).append("\n"));
        if ("workflow-guide".equals(docId)) {
            sb.append("\nAvailable section IDs for `<fetch type=\"docs\" id=\"workflow-guide/<id>\"/>`:\n");
            WF_GUIDE_SECTIONS.forEach((k, v) -> sb.append("  ").append(k).append("\n"));
        }
        sb.append("\nFetch a section to get details.\n");
        return sb.toString();
    }

    private String extractSection(String content, String headerPrefix) {
        String[] lines = content.split("\n");
        var sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (!inSection) {
                if (line.startsWith(headerPrefix)) inSection = true;
            } else {
                if (line.startsWith("## ") && !line.startsWith(headerPrefix)) break;
            }
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // ── Pool ─────────────────────────────────────────────────────────────────

    private String formatPoolWorkflows() {
        try {
            List<PoolWorkflowEntry> entries = workflowPoolService.listAll();
            if (entries.isEmpty()) return "No pool workflows available.\n";
            var sb = new StringBuilder();
            sb.append("**Workflow pool (").append(entries.size()).append(" templates):**\n\n");
            entries.forEach(e -> {
                String deName = e.name() != null ? e.name().getOrDefault("de", e.name().getOrDefault("en", e.id())) : e.id();
                sb.append("- **").append(deName).append("** (ID: `").append(e.id()).append("`");
                if (e.category() != null) sb.append(", cat: ").append(e.category());
                if (e.blocks() != null) sb.append(", ").append(e.blocks().size()).append(" blocks");
                sb.append(")");
                if (e.tags() != null && !e.tags().isEmpty()) sb.append(" Tags: ").append(String.join(", ", e.tags()));
                String deDesc = e.description() != null ? e.description().getOrDefault("de", e.description().getOrDefault("en", "")) : "";
                if (!deDesc.isBlank()) sb.append("\n  ").append(contextBuilder.truncate(deDesc, 120));
                sb.append("\n");
            });
            sb.append("\nUse `<fetch type=\"pool_workflow\" id=\"<pool-id>\"/>` for details incl. blocks.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load pool workflows for assistant", e);
            return "Pool workflows could not be loaded.\n";
        }
    }

    private String formatPoolWorkflowDetail(String poolId) {
        try {
            PoolWorkflowEntry entry = workflowPoolService.findById(poolId);
            if (entry == null) return "Pool workflow '" + poolId + "' not found.\n";
            var sb = new StringBuilder();
            String deName = entry.name() != null ? entry.name().getOrDefault("de", poolId) : poolId;
            sb.append("**Pool workflow: ").append(deName).append("** (").append(poolId).append(")\n");
            String deDesc = entry.description() != null ? entry.description().getOrDefault("de", "") : "";
            if (!deDesc.isBlank()) sb.append("Description: ").append(deDesc).append("\n");
            if (entry.model() != null) sb.append("Model: ").append(entry.model()).append("\n");
            if (entry.instructions() != null && !entry.instructions().isBlank())
                sb.append("Instructions: ").append(contextBuilder.truncate(entry.instructions(), 500)).append("\n");
            if (entry.defaultContext() != null && !entry.defaultContext().isEmpty())
                sb.append("Default variables: ").append(entry.defaultContext()).append("\n");
            if (entry.blocks() != null && !entry.blocks().isEmpty()) {
                sb.append("\nBlocks (").append(entry.blocks().size()).append("):\n");
                for (int i = 0; i < entry.blocks().size(); i++) {
                    BlockDefinition block = entry.blocks().get(i);
                    Map<String, Object> cfg = block.getConfig() != null ? block.getConfig() : Map.of();
                    String label = cfg.containsKey("label") ? String.valueOf(cfg.get("label")) : "";
                    sb.append("  ").append(i + 1).append(". [").append(block.getType()).append("]");
                    if (!label.isBlank()) sb.append(" ").append(label);
                    sb.append("\n");
                    appendBlockConfigSummary(sb, block.getType(), cfg);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load pool workflow detail for {}", poolId, e);
            return "Pool workflow '" + poolId + "' not found.\n";
        }
    }

    private String formatProjectContext(String projectId) {
        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Project '" + projectId + "' not found.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Project '" + project.getName() + "' has no path configured.\n";

            // Cache-first: use stored aiContext if available
            String context = project.getAiContext();
            if (context == null || context.isBlank()) {
                context = projectContextService.generateContext(project.getPath());
                if (context != null && !context.isBlank()) {
                    project.setAiContext(context);
                    projectRepository.save(project);
                }
            }

            if (context == null || context.isBlank())
                return "No project context available for '" + project.getName() + "'.\n";
            return "**Project analysis: " + project.getName() + "** (path: " + project.getPath() + ")\n\n" + context + "\n";
        } catch (Exception e) {
            log.debug("Could not generate project context for {}", projectId, e);
            return "Project context could not be generated: " + e.getMessage() + "\n";
        }
    }

    // ── Project file access ─────────────────────────────────────────────────

    private static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", ".git", "target", "build", "dist", "__pycache__",
            ".gradle", ".idea", ".vscode", ".next", "venv", ".venv", ".cache"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "txt", "java", "py", "ts", "tsx", "js", "jsx", "json", "yml", "yaml",
            "xml", "toml", "gradle", "properties", "sh", "bash", "sql", "html", "css",
            "scss", "rs", "go", "kt", "kts", "swift", "rb", "php", "c", "h", "cpp",
            "hpp", "cs", "r", "lua", "vim", "conf", "cfg", "ini", "env", "dockerfile",
            "makefile", "gitignore", "editorconfig", "lock"
    );

    private String formatProjectFiles(String projectId) {
        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Project '" + projectId + "' not found.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Project '" + project.getName() + "' has no path configured.\n";

            Path root = Path.of(project.getPath());
            if (!Files.isDirectory(root))
                return "Project path does not exist: " + project.getPath() + "\n";

            var sb = new StringBuilder();
            sb.append("**Project files: ").append(project.getName()).append("** (").append(project.getPath()).append(")\n\n");

            // Top-level files
            int count = 0;
            try (Stream<Path> topLevel = Files.list(root).sorted()) {
                for (Path entry : topLevel.toList()) {
                    if (count >= 50) { sb.append("  … (more files)\n"); break; }
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) {
                        if (!IGNORED_DIRS.contains(name)) {
                            sb.append("  ").append(name).append("/\n");
                            appendSubdirectory(sb, entry, 1, count);
                        }
                    } else {
                        long size = Files.size(entry);
                        sb.append("  ").append(name).append(" (").append(formatSize(size)).append(")\n");
                    }
                    count++;
                }
            }

            sb.append("\nUse `<fetch type=\"project_file\" id=\"").append(projectId)
                    .append("/<path>\"/>` to read a file.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not list project files for {}", projectId, e);
            return "Project files could not be loaded: " + e.getMessage() + "\n";
        }
    }

    private void appendSubdirectory(StringBuilder sb, Path dir, int depth, int parentCount) {
        if (depth > 1 || parentCount > 40) return;
        try (Stream<Path> entries = Files.list(dir).sorted()) {
            int subCount = 0;
            for (Path entry : entries.toList()) {
                if (subCount >= 10) { sb.append("    … (more)\n"); break; }
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (!IGNORED_DIRS.contains(name)) {
                        sb.append("    ").append(name).append("/\n");
                        subCount++;
                    }
                } else {
                    sb.append("    ").append(name).append("\n");
                    subCount++;
                }
            }
        } catch (IOException ignored) {}
    }

    private String formatProjectFile(String idWithPath) {
        int slash = idWithPath.indexOf('/');
        if (slash < 0) return "Invalid format. Expected: <project-id>/<filePath>\n";
        String projectId = idWithPath.substring(0, slash);
        String relativePath = idWithPath.substring(slash + 1);

        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Project '" + projectId + "' not found.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Project '" + project.getName() + "' has no path configured.\n";

            Path root = Path.of(project.getPath()).toAbsolutePath().normalize();
            Path resolved = root.resolve(relativePath).normalize();

            // Path traversal protection
            if (!resolved.startsWith(root))
                return "Access denied: path is outside the project.\n";

            if (!Files.exists(resolved))
                return "File not found: " + relativePath + "\n";

            if (Files.isDirectory(resolved))
                return "'" + relativePath + "' is a directory. Use `<fetch type=\"project_files\">` for listings.\n";

            // File type check
            String fileName = resolved.getFileName().toString().toLowerCase();
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
            boolean isTextFile = TEXT_EXTENSIONS.contains(ext)
                    || fileName.equals("makefile") || fileName.equals("dockerfile")
                    || fileName.startsWith(".");
            if (!isTextFile)
                return "File '" + relativePath + "' is not a text file (." + ext + ").\n";

            long fileSize = Files.size(resolved);
            if (fileSize > 500_000)
                return "File '" + relativePath + "' is too large (" + formatSize(fileSize) + "). Max 500 KB.\n";

            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            var sb = new StringBuilder();
            sb.append("**File: ").append(relativePath).append("** (").append(formatSize(fileSize)).append(")\n\n");
            if (content.length() > 8000) {
                sb.append(content, 0, 8000);
                sb.append("\n\n[File truncated: 8000 of ").append(content.length()).append(" characters]\n");
            } else {
                sb.append(content).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not read project file {}", idWithPath, e);
            return "File could not be read: " + e.getMessage() + "\n";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ── Block config summary ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void appendBlockConfigSummary(StringBuilder sb, String type, Map<String, Object> cfg) {
        switch (type) {
            case "simple", "agentic" -> {
                Object prompt = cfg.get("prompt");
                if (prompt != null) sb.append("     prompt: ").append(contextBuilder.truncate(String.valueOf(prompt).trim(), 200)).append("\n");
                Object model = cfg.get("model");
                if (model != null && !String.valueOf(model).isBlank()) sb.append("     model: ").append(model).append("\n");
            }
            case "shell" -> {
                Object command = cfg.get("command");
                if (command != null) sb.append("     command: ").append(contextBuilder.truncate(String.valueOf(command).trim(), 300)).append("\n");
            }
            case "conditional" -> {
                Object condition = cfg.get("condition");
                if (condition != null) sb.append("     condition: ").append(condition).append("\n");
                Object trueBlocks = cfg.get("true_blocks");
                Object falseBlocks = cfg.get("false_blocks");
                int trueCount  = trueBlocks  instanceof List<?> l ? l.size() : 0;
                int falseCount = falseBlocks instanceof List<?> l ? l.size() : 0;
                sb.append("     if true: ").append(trueCount).append(" block(s), if false: ").append(falseCount).append(" block(s)\n");
            }
            case "forEach" -> {
                Object items = cfg.get("items");
                if (items != null) sb.append("     items: ").append(items).append("\n");
                Object nested = cfg.get("blocks");
                int count = nested instanceof List<?> l ? l.size() : 0;
                sb.append("     nested blocks: ").append(count).append("\n");
            }
            case "switch" -> {
                Object cases = cfg.get("cases");
                int count = cases instanceof List<?> l ? l.size() : 0;
                sb.append("     cases: ").append(count).append("\n");
            }
            case "approval" -> {
                Object question = cfg.get("question");
                if (question != null) sb.append("     question: ").append(question).append("\n");
            }
            case "retry" -> {
                Object maxAttempts = cfg.get("max_attempts");
                if (maxAttempts != null) sb.append("     max_attempts: ").append(maxAttempts).append("\n");
                Object innerBlock = cfg.get("block");
                if (innerBlock instanceof Map<?, ?> inner) {
                    sb.append("     inner block type: ").append(inner.get("type")).append("\n");
                }
            }
            case "sub_workflow" -> {
                Object wfId = cfg.get("workflow_id");
                if (wfId != null) {
                    String name = workflowRepository.findById(String.valueOf(wfId))
                            .map(w -> w.getName()).orElse(String.valueOf(wfId));
                    sb.append("     sub-workflow: ").append(name).append("\n");
                }
            }
            case "human_input" -> {
                Object prompt = cfg.get("prompt");
                if (prompt != null) sb.append("     prompt: ").append(prompt).append("\n");
                Object inputType = cfg.get("input_type");
                if (inputType != null) sb.append("     input_type: ").append(inputType).append("\n");
            }
            default -> {
                cfg.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isBlank()) {
                        sb.append("     ").append(k).append(": ").append(contextBuilder.truncate(String.valueOf(v), 80)).append("\n");
                    }
                });
            }
        }
    }

    // ── Skill / Integration / Chain / Block-Spec / Workflow-Full / Workflow-Inputs ──

    private String formatSkillDetail(String skillId) {
        try {
            return skillRepository.findByIdWithCapabilities(skillId).map(s -> {
                var sb = new StringBuilder();
                sb.append("**Skill: ").append(s.getName()).append("** (id: ").append(s.getId()).append(")\n");
                sb.append("Category: ").append(s.getCategory()).append("\n");
                if (s.getDescription() != null && !s.getDescription().isBlank()) {
                    sb.append("Description: ").append(contextBuilder.truncate(s.getDescription().trim(), 200)).append("\n");
                }
                if (s.getAiContext() != null && !s.getAiContext().isBlank()) {
                    sb.append("AI context: ").append(contextBuilder.truncate(s.getAiContext().trim(), 400)).append("\n");
                }
                var caps = s.getCapabilities();
                if (caps != null && !caps.isEmpty()) {
                    sb.append("\nCapabilities (").append(caps.size()).append("):\n");
                    caps.forEach(c -> {
                        sb.append("  - ").append(c.getName());
                        if (c.getDescription() != null && !c.getDescription().isBlank()) {
                            sb.append(" — ").append(contextBuilder.truncate(c.getDescription().trim(), 100));
                        }
                        sb.append("\n");
                        if (c.getInputSchema() != null && !c.getInputSchema().isEmpty()) {
                            sb.append("    inputSchema keys: ").append(c.getInputSchema().keySet()).append("\n");
                        }
                    });
                }
                return sb.toString();
            }).orElse("Skill '" + skillId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch skill detail for {}", skillId, e);
            return "";
        }
    }

    private String formatIntegrationDetail(String providerId) {
        try {
            if (!integrationRegistry.hasProvider(providerId)) {
                return "Integration '" + providerId + "' not found.\n";
            }
            IntegrationProvider p = integrationRegistry.getProvider(providerId);
            IntegrationConnection.ConnectionStatus status = integrationConnectionRepository.findByProviderId(providerId)
                    .map(IntegrationConnection::getStatus)
                    .orElse(IntegrationConnection.ConnectionStatus.DISCONNECTED);
            String accountInfo = integrationConnectionRepository.findByProviderId(providerId)
                    .map(IntegrationConnection::getAccountInfo)
                    .orElse(null);
            var sb = new StringBuilder();
            sb.append("**Integration: ").append(p.getDisplayName()).append("** (id: ").append(p.getProviderId()).append(")\n");
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                sb.append("Description: ").append(contextBuilder.truncate(p.getDescription().trim(), 200)).append("\n");
            }
            sb.append("Status: ").append(status);
            if (accountInfo != null && !accountInfo.isBlank()) {
                sb.append(" (").append(contextBuilder.truncate(accountInfo, 80)).append(")");
            }
            sb.append("\n");
            sb.append("OAuth flow required: ").append(p.requiresOAuthFlow() ? "yes" : "no (API key/token)").append("\n");
            List<IntegrationAction> actions = p.getAvailableActions();
            if (actions != null && !actions.isEmpty()) {
                sb.append("\nAvailable actions (").append(actions.size()).append("):\n");
                actions.forEach(a -> {
                    sb.append("  - ").append(a.name());
                    if (a.description() != null && !a.description().isBlank()) {
                        sb.append(" — ").append(contextBuilder.truncate(a.description().trim(), 100));
                    }
                    sb.append("\n");
                    if (a.inputSchema() != null && !a.inputSchema().isEmpty()) {
                        sb.append("    inputSchema keys: ").append(a.inputSchema().keySet()).append("\n");
                    }
                    if (a.requiredScopes() != null && !a.requiredScopes().isEmpty()) {
                        sb.append("    scopes: ").append(a.requiredScopes()).append("\n");
                    }
                });
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not fetch integration detail for {}", providerId, e);
            return "";
        }
    }

    private String formatChainDetail(String chainId) {
        try {
            return workflowChainRepository.findById(chainId).map(c -> {
                var sb = new StringBuilder();
                sb.append("**Chain: ").append(c.getName()).append("** (id: ").append(c.getId()).append(")\n");
                if (c.getDescription() != null && !c.getDescription().isBlank()) {
                    sb.append("Description: ").append(contextBuilder.truncate(c.getDescription().trim(), 200)).append("\n");
                }
                sb.append("Stop on failure: ").append(c.isStopOnFailure() ? "yes" : "no").append("\n");
                var entries = c.getEntries();
                if (entries != null && !entries.isEmpty()) {
                    sb.append("\nWorkflows in Chain (").append(entries.size()).append("):\n");
                    for (int i = 0; i < entries.size(); i++) {
                        var entry = entries.get(i);
                        String wfName = workflowRepository.findById(entry.getWorkflowId())
                                .map(w -> w.getName())
                                .orElse(entry.getWorkflowId());
                        sb.append("  ").append(i + 1).append(". ").append(wfName)
                          .append(" (id: ").append(entry.getWorkflowId()).append(")");
                        if (entry.getLabel() != null && !entry.getLabel().isBlank()) {
                            sb.append(" [Label: ").append(entry.getLabel()).append("]");
                        }
                        sb.append("\n");
                        if (entry.getInputContext() != null && !entry.getInputContext().isEmpty()) {
                            sb.append("    inputs: ").append(entry.getInputContext().keySet()).append("\n");
                        }
                    }
                }
                return sb.toString();
            }).orElse("Chain '" + chainId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch chain detail for {}", chainId, e);
            return "";
        }
    }

    private static final Map<String, String> BLOCK_SPECS = Map.ofEntries(
            Map.entry("simple", """
                    **Block type: simple** — Simple AI prompt, one response.
                    Required config: command (string) — prompt text with template variables ({{var}}).
                    Optional: model (string), timeout_seconds (int), label (string).
                    Example config: { "command": "Analyze {{input}} and return JSON" }
                    """),
            Map.entry("shell", """
                    **Block type: shell** — Run a shell command. NO API/DB access allowed.
                    Required config: command (string) — bash command, template variables supported.
                    Optional: timeout_seconds (int), label (string).
                    Example config: { "command": "ls -la /tmp/{{run_id}}" }
                    Note: set -euo pipefail recommended, use actuator/health instead of direct DB.
                    """),
            Map.entry("agentic", """
                    **Block type: agentic** — Multi-stage agent with REASON+ACT phases, long-running.
                    Required config: prompt (string), max_total_tokens (int) — cumulative budget.
                    Optional: model, max_turns (int), timeout_seconds, label.
                    Example config: { "prompt": "Refactor X until tests pass", "max_total_tokens": 50000 }
                    Note: REASON uses --system-prompt, ACT uses --append-system-prompt.
                    """),
            Map.entry("conditional", """
                    **Block type: conditional** — Branch based on a condition.
                    Required config: condition (string expression, JSONPath/template).
                    Required sub-blocks: true_blocks (list), false_blocks (list).
                    Example: { "condition": "{{score}} > 0.8", "true_blocks": [...], "false_blocks": [...] }
                    """),
            Map.entry("foreach", """
                    **Block type: foreach** — Iterate over a list.
                    Required config: items (template variable that resolves to a list).
                    Required sub-blocks: blocks (list).
                    Example: { "items": "{{file_list}}", "blocks": [...] }
                    Note: the iterator variable in the sub-block is called {{item}}.
                    """),
            Map.entry("while", """
                    **Block type: while** — Loop with a stop condition and max_iterations.
                    Required config: condition (string), max_iterations (int).
                    Required sub-blocks: blocks (list).
                    Example: { "condition": "{{retries}} < 3", "max_iterations": 10, "blocks": [...] }
                    """),
            Map.entry("switch", """
                    **Block type: switch** — Multi-way branch based on a match value.
                    Required config: value (string), cases (list of { "match": ..., "blocks": [...] }).
                    Optional: default_blocks (list).
                    """),
            Map.entry("retry", """
                    **Block type: retry** — Retries a sub-block on failure.
                    Required config: max_attempts (int), block (single block definition).
                    Optional: backoff_ms (int), retry_on (list of error-type strings).
                    """),
            Map.entry("approval", """
                    **Block type: approval** — Pauses until user approval.
                    Required config: question (string).
                    Optional: options (list of string answers — e.g. ["Yes","No","Skip"]), timeout_seconds.
                    Approve via POST /api/v1/executions/{id}/approve with { "answer": "Yes" } (field: answer, NOT approved).
                    """),
            Map.entry("sub-workflow", """
                    **Block type: sub-workflow** — Run another workflow as a block.
                    Required config: workflow_id (string).
                    Optional: input_context (Map) — passed as context to the sub-workflow.
                    """),
            Map.entry("skill-capability", """
                    **Block type: skill-capability** — Run a capability of a skill (declarative).
                    Required config: skill_id (string), capability_name (string).
                    Optional: inputs (Map) — validated against capability.inputSchema.
                    """),
            Map.entry("human-input", """
                    **Block type: human-input** — Ask the user for a value, pauses until answered.
                    Required config: prompt (string).
                    Optional: input_type (text/number/boolean/json), default (any).
                    """),
            Map.entry("integration", """
                    **Block type: integration** — Action of an integration provider.
                    Required config: provider_id (string), action (string).
                    Optional: inputs (Map) — validated against action.inputSchema.
                    Example: { "provider_id": "github", "action": "create_issue", "inputs": {...} }
                    Fetch available providers via <fetch type="integration" id="<provider-id>"/>
                    """),
            Map.entry("document", """
                    **Block type: document** — OCR/document processing (PDF, images).
                    Required config: file_path (string or template variable).
                    Optional: extract_mode (text/structured), language (hint for OCR).
                    """)
    );

    private String formatBlockSpec(String blockType) {
        String spec = BLOCK_SPECS.get(blockType);
        if (spec == null) {
            return "Unknown block type '" + blockType + "'. Available: " + BLOCK_SPECS.keySet() + "\n";
        }
        return spec;
    }

    private String formatWorkflowFull(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                sb.append("**Workflow (full): ").append(wf.getName()).append("** (").append(projectName).append(")\n");
                sb.append("ID: ").append(wf.getId()).append("\n");
                if (wf.getModel() != null && !wf.getModel().isBlank()) {
                    sb.append("Model: ").append(wf.getModel()).append("\n");
                }
                if (wf.getInstructions() != null && !wf.getInstructions().isBlank()) {
                    sb.append("Instructions: ").append(contextBuilder.truncate(wf.getInstructions().trim(), 600)).append("\n");
                }
                if (wf.getDefaultContext() != null && !wf.getDefaultContext().isEmpty()) {
                    sb.append("Default inputs: ").append(wf.getDefaultContext()).append("\n");
                }
                var blocks = wf.getBlocks();
                if (blocks != null && !blocks.isEmpty()) {
                    sb.append("\nBlocks (").append(blocks.size()).append(", full config):\n");
                    for (int i = 0; i < blocks.size(); i++) {
                        BlockDefinition block = blocks.get(i);
                        Map<String, Object> cfg = block.getConfig() != null ? block.getConfig() : Map.of();
                        sb.append("  ").append(i + 1).append(". type: ").append(block.getType()).append("\n");
                        cfg.forEach((k, v) -> {
                            String valStr = v == null ? "null" : String.valueOf(v);
                            sb.append("       ").append(k).append(": ")
                              .append(contextBuilder.truncate(valStr, 300)).append("\n");
                        });
                    }
                }
                return sb.toString();
            }).orElse("Workflow '" + workflowId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow-full for {}", workflowId, e);
            return "";
        }
    }

    private String formatFindWorkflow(String query) {
        if (query == null || query.isBlank()) {
            return "Search term missing. Expected: <fetch type=\"find_workflow\" id=\"<name-or-substring>\"/>\n";
        }
        try {
            List<com.automation.entity.Workflow> matches = workflowRepository.findByNameContainingIgnoreCase(query.trim());
            if (matches.isEmpty()) {
                return "No workflow found whose name contains '" + query + "'.\n";
            }
            var sb = new StringBuilder();
            sb.append("**Workflow search for '").append(query).append("':** ")
                    .append(matches.size()).append(" matches\n");
            int limit = Math.min(matches.size(), 20);
            for (int i = 0; i < limit; i++) {
                var wf = matches.get(i);
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                sb.append("  - **").append(wf.getName()).append("** (id: `").append(wf.getId()).append("`, project: ")
                        .append(projectName).append(")\n");
            }
            if (matches.size() > limit) {
                sb.append("  … ").append(matches.size() - limit).append(" more matches (refine the search).\n");
            }
            sb.append("\nUse `<fetch type=\"workflow_detail\" id=\"<id>\"/>` for details.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not search workflows for '{}'", query, e);
            return "Workflow search failed.\n";
        }
    }

    private String formatWorkflowInputs(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                sb.append("**Inputs for workflow: ").append(wf.getName()).append("** (id: ").append(wf.getId()).append(")\n");
                Map<String, Object> ctx = wf.getDefaultContext();
                if (ctx == null || ctx.isEmpty()) {
                    sb.append("(no default inputs defined)\n");
                } else {
                    ctx.forEach((k, v) -> {
                        String valStr = v == null ? "null" : String.valueOf(v);
                        sb.append("  - ").append(k).append(" = ")
                          .append(contextBuilder.truncate(valStr, 100)).append("\n");
                    });
                }
                return sb.toString();
            }).orElse("Workflow '" + workflowId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow-inputs for {}", workflowId, e);
            return "";
        }
    }
}
