package com.automation.service.assistant;

import com.automation.entity.BlockDefinition;
import com.automation.entity.RunStatus;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.project.ProjectContextService;
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
            case "step_output"      -> formatStepOutput(req.id());
            case "docs"             -> formatDocsSection(req.id());
            case "pool_workflows"   -> formatPoolWorkflows();
            case "pool_workflow"    -> formatPoolWorkflowDetail(req.id());
            case "project_context"  -> formatProjectContext(req.id());
            case "project_files"    -> formatProjectFiles(req.id());
            case "project_file"     -> formatProjectFile(req.id());
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
                if (err != null) sb.append("Run-Fehler: ").append(contextBuilder.truncate(err, 600)).append("\n");
                var steps = run.getSteps();
                if (!steps.isEmpty()) {
                    sb.append("Schritte:\n");
                    steps.forEach(s -> {
                        sb.append("  ").append(s.getStepIndex() + 1).append(". ").append(s.getStatus());
                        if (s.getStatus() == StepStatus.FAILED && s.getOutput() != null)
                            sb.append(": ").append(contextBuilder.truncate(s.getOutput(), 800));
                        sb.append("\n");
                    });
                }
                return sb.toString();
            }).orElse("Run " + id + " nicht gefunden.\n");
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
                sb.append("Iterationen: ").append(w.getIterationCount())
                        .append("/").append(w.getMaxIterations()).append("\n");
                if (w.getError() != null)
                    sb.append("Fehler: ").append(contextBuilder.truncate(w.getError(), 300)).append("\n");
                return sb.toString();
            }).orElse("Wish " + id + " nicht gefunden.\n");
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
                sb.append("Projekt-ID: ").append(projectId).append("\n");
                if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                    sb.append("Beschreibung: ").append(contextBuilder.truncate(wf.getDescription().trim(), 150)).append("\n");
                }
                if (wf.getModel() != null && !wf.getModel().isBlank()) {
                    sb.append("Modell: ").append(wf.getModel()).append("\n");
                }
                if (wf.getInstructions() != null && !wf.getInstructions().isBlank()) {
                    sb.append("Instruktionen: ").append(contextBuilder.truncate(wf.getInstructions().trim(), 400)).append("\n");
                }
                var blocks = wf.getBlocks();
                if (blocks != null && !blocks.isEmpty()) {
                    sb.append("\nBlöcke (").append(blocks.size()).append("):\n");
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
            }).orElse("Workflow " + workflowId + " nicht gefunden.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow detail for {}", workflowId, e);
            return "";
        }
    }

    private String formatWorkflowRuns(String workflowId) {
        try {
            List<WorkflowRun> runs = workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc(workflowId);
            if (runs.isEmpty()) return "Keine Runs für Workflow " + workflowId + ".\n";
            var sb = new StringBuilder();
            sb.append("**Letzte Runs für Workflow ").append(workflowId, 0, Math.min(8, workflowId.length()))
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
        int slash = idAndStep.lastIndexOf('/');
        if (slash < 0) return "Ungültiges Format. Erwartet: <runId>/<schrittnummer>\n";
        String runId = idAndStep.substring(0, slash);
        int stepNum;
        try {
            stepNum = Integer.parseInt(idAndStep.substring(slash + 1));
        } catch (NumberFormatException e) {
            return "Schrittnummer ungültig: " + idAndStep.substring(slash + 1) + "\n";
        }
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
                                if (out.length() > 8000) out = out.substring(0, 8000) + "\n[Ausgabe gekürzt: 8000 von " + s.getOutput().length() + " Zeichen]";
                                sb.append(out).append("\n");
                            } else {
                                sb.append("(Kein Output)\n");
                            }
                            return sb.toString();
                        })
                        .orElse("Step " + stepNum + " nicht gefunden in Run " + runId.substring(0, Math.min(8, runId.length())) + "…\n")
        ).orElse("Run " + runId + " nicht gefunden.\n");
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
        if (filename == null) return "Unbekannte Dokumentation: " + baseId + "\n";

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
                        ? fullContent.substring(0, 8000) + "\n\n[Ausgabe gekürzt: 8000 von " + fullContent.length() + " Zeichen]" : fullContent;
                return "**Dokumentation: " + baseId + "**\n\n" + content + "\n";
            }

            String section = extractSection(fullContent, sectionHeader);
            if (section.isBlank()) return "Sektion '" + sectionKey + "' nicht gefunden in " + baseId + ".\n";
            if (section.length() > 12000) section = section.substring(0, 12000) + "\n\n[Ausgabe gekürzt: 12000 von " + section.length() + " Zeichen]";
            return "**" + baseId + " / " + sectionKey + ":**\n\n" + section + "\n";

        } catch (Exception e) {
            log.debug("Could not load doc '{}': {}", docId, e.getMessage());
            return "Dokumentation '" + docId + "' nicht verfügbar.\n";
        }
    }

    private String buildDocsToc(String docId, String content) {
        var sb = new StringBuilder();
        sb.append("**Dokumentation: ").append(docId).append("** — Inhaltsübersicht\n\n");
        content.lines()
                .filter(l -> l.startsWith("## "))
                .forEach(l -> sb.append(l).append("\n"));
        if ("workflow-guide".equals(docId)) {
            sb.append("\nVerfügbare Sektions-IDs für `<fetch type=\"docs\" id=\"workflow-guide/<id>\"/>`:\n");
            WF_GUIDE_SECTIONS.forEach((k, v) -> sb.append("  ").append(k).append("\n"));
        }
        sb.append("\nLade eine Sektion nach um Details zu erhalten.\n");
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
            if (entries.isEmpty()) return "Keine Pool-Workflows verfügbar.\n";
            var sb = new StringBuilder();
            sb.append("**Workflow-Pool (").append(entries.size()).append(" Vorlagen):**\n\n");
            entries.forEach(e -> {
                String deName = e.name() != null ? e.name().getOrDefault("de", e.name().getOrDefault("en", e.id())) : e.id();
                sb.append("- **").append(deName).append("** (ID: `").append(e.id()).append("`");
                if (e.category() != null) sb.append(", Kat: ").append(e.category());
                if (e.blocks() != null) sb.append(", ").append(e.blocks().size()).append(" Blöcke");
                sb.append(")");
                if (e.tags() != null && !e.tags().isEmpty()) sb.append(" Tags: ").append(String.join(", ", e.tags()));
                String deDesc = e.description() != null ? e.description().getOrDefault("de", e.description().getOrDefault("en", "")) : "";
                if (!deDesc.isBlank()) sb.append("\n  ").append(contextBuilder.truncate(deDesc, 120));
                sb.append("\n");
            });
            sb.append("\nNutze `<fetch type=\"pool_workflow\" id=\"<pool-id>\"/>` für Details inkl. Blöcke.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load pool workflows for assistant", e);
            return "Pool-Workflows konnten nicht geladen werden.\n";
        }
    }

    private String formatPoolWorkflowDetail(String poolId) {
        try {
            PoolWorkflowEntry entry = workflowPoolService.findById(poolId);
            if (entry == null) return "Pool-Workflow '" + poolId + "' nicht gefunden.\n";
            var sb = new StringBuilder();
            String deName = entry.name() != null ? entry.name().getOrDefault("de", poolId) : poolId;
            sb.append("**Pool-Workflow: ").append(deName).append("** (").append(poolId).append(")\n");
            String deDesc = entry.description() != null ? entry.description().getOrDefault("de", "") : "";
            if (!deDesc.isBlank()) sb.append("Beschreibung: ").append(deDesc).append("\n");
            if (entry.model() != null) sb.append("Modell: ").append(entry.model()).append("\n");
            if (entry.instructions() != null && !entry.instructions().isBlank())
                sb.append("Instruktionen: ").append(contextBuilder.truncate(entry.instructions(), 500)).append("\n");
            if (entry.defaultContext() != null && !entry.defaultContext().isEmpty())
                sb.append("Default-Variablen: ").append(entry.defaultContext()).append("\n");
            if (entry.blocks() != null && !entry.blocks().isEmpty()) {
                sb.append("\nBlöcke (").append(entry.blocks().size()).append("):\n");
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
            return "Pool-Workflow '" + poolId + "' nicht gefunden.\n";
        }
    }

    private String formatProjectContext(String projectId) {
        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Projekt '" + projectId + "' nicht gefunden.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Projekt '" + project.getName() + "' hat keinen Pfad konfiguriert.\n";

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
                return "Kein Projekt-Kontext für '" + project.getName() + "' verfügbar.\n";
            return "**Projekt-Analyse: " + project.getName() + "** (Pfad: " + project.getPath() + ")\n\n" + context + "\n";
        } catch (Exception e) {
            log.debug("Could not generate project context for {}", projectId, e);
            return "Projekt-Kontext konnte nicht generiert werden: " + e.getMessage() + "\n";
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
            if (project == null) return "Projekt '" + projectId + "' nicht gefunden.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Projekt '" + project.getName() + "' hat keinen Pfad konfiguriert.\n";

            Path root = Path.of(project.getPath());
            if (!Files.isDirectory(root))
                return "Projekt-Pfad existiert nicht: " + project.getPath() + "\n";

            var sb = new StringBuilder();
            sb.append("**Projekt-Dateien: ").append(project.getName()).append("** (").append(project.getPath()).append(")\n\n");

            // Top-level files
            int count = 0;
            try (Stream<Path> topLevel = Files.list(root).sorted()) {
                for (Path entry : topLevel.toList()) {
                    if (count >= 50) { sb.append("  … (weitere Dateien)\n"); break; }
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

            sb.append("\nNutze `<fetch type=\"project_file\" id=\"").append(projectId)
                    .append("/<pfad>\"/>` um eine Datei zu lesen.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not list project files for {}", projectId, e);
            return "Projekt-Dateien konnten nicht geladen werden: " + e.getMessage() + "\n";
        }
    }

    private void appendSubdirectory(StringBuilder sb, Path dir, int depth, int parentCount) {
        if (depth > 1 || parentCount > 40) return;
        try (Stream<Path> entries = Files.list(dir).sorted()) {
            int subCount = 0;
            for (Path entry : entries.toList()) {
                if (subCount >= 10) { sb.append("    … (weitere)\n"); break; }
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
        if (slash < 0) return "Ungültiges Format. Erwartet: <project-id>/<dateipfad>\n";
        String projectId = idWithPath.substring(0, slash);
        String relativePath = idWithPath.substring(slash + 1);

        try {
            var project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return "Projekt '" + projectId + "' nicht gefunden.\n";
            if (project.getPath() == null || project.getPath().isBlank())
                return "Projekt '" + project.getName() + "' hat keinen Pfad konfiguriert.\n";

            Path root = Path.of(project.getPath()).toAbsolutePath().normalize();
            Path resolved = root.resolve(relativePath).normalize();

            // Path traversal protection
            if (!resolved.startsWith(root))
                return "Zugriff verweigert: Pfad liegt außerhalb des Projekts.\n";

            if (!Files.exists(resolved))
                return "Datei nicht gefunden: " + relativePath + "\n";

            if (Files.isDirectory(resolved))
                return "'" + relativePath + "' ist ein Verzeichnis. Nutze `<fetch type=\"project_files\">` für Listings.\n";

            // File type check
            String fileName = resolved.getFileName().toString().toLowerCase();
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
            boolean isTextFile = TEXT_EXTENSIONS.contains(ext)
                    || fileName.equals("makefile") || fileName.equals("dockerfile")
                    || fileName.startsWith(".");
            if (!isTextFile)
                return "Datei '" + relativePath + "' ist keine Text-Datei (." + ext + ").\n";

            long fileSize = Files.size(resolved);
            if (fileSize > 500_000)
                return "Datei '" + relativePath + "' ist zu groß (" + formatSize(fileSize) + "). Max 500 KB.\n";

            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            var sb = new StringBuilder();
            sb.append("**Datei: ").append(relativePath).append("** (").append(formatSize(fileSize)).append(")\n\n");
            if (content.length() > 8000) {
                sb.append(content, 0, 8000);
                sb.append("\n\n[Datei gekürzt: 8000 von ").append(content.length()).append(" Zeichen]\n");
            } else {
                sb.append(content).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not read project file {}", idWithPath, e);
            return "Datei konnte nicht gelesen werden: " + e.getMessage() + "\n";
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
                sb.append("     wenn true: ").append(trueCount).append(" Block(s), wenn false: ").append(falseCount).append(" Block(s)\n");
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
}
