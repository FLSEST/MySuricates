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

    // ── Skill / Integration / Chain / Block-Spec / Workflow-Full / Workflow-Inputs ──

    private String formatSkillDetail(String skillId) {
        try {
            return skillRepository.findByIdWithCapabilities(skillId).map(s -> {
                var sb = new StringBuilder();
                sb.append("**Skill: ").append(s.getName()).append("** (id: ").append(s.getId()).append(")\n");
                sb.append("Kategorie: ").append(s.getCategory()).append("\n");
                if (s.getDescription() != null && !s.getDescription().isBlank()) {
                    sb.append("Beschreibung: ").append(contextBuilder.truncate(s.getDescription().trim(), 200)).append("\n");
                }
                if (s.getAiContext() != null && !s.getAiContext().isBlank()) {
                    sb.append("AI-Kontext: ").append(contextBuilder.truncate(s.getAiContext().trim(), 400)).append("\n");
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
            }).orElse("Skill '" + skillId + "' nicht gefunden.\n");
        } catch (Exception e) {
            log.debug("Could not fetch skill detail for {}", skillId, e);
            return "";
        }
    }

    private String formatIntegrationDetail(String providerId) {
        try {
            if (!integrationRegistry.hasProvider(providerId)) {
                return "Integration '" + providerId + "' nicht gefunden.\n";
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
                sb.append("Beschreibung: ").append(contextBuilder.truncate(p.getDescription().trim(), 200)).append("\n");
            }
            sb.append("Status: ").append(status);
            if (accountInfo != null && !accountInfo.isBlank()) {
                sb.append(" (").append(contextBuilder.truncate(accountInfo, 80)).append(")");
            }
            sb.append("\n");
            sb.append("OAuth-Flow erforderlich: ").append(p.requiresOAuthFlow() ? "ja" : "nein (API-Key/Token)").append("\n");
            List<IntegrationAction> actions = p.getAvailableActions();
            if (actions != null && !actions.isEmpty()) {
                sb.append("\nVerfügbare Actions (").append(actions.size()).append("):\n");
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
                    sb.append("Beschreibung: ").append(contextBuilder.truncate(c.getDescription().trim(), 200)).append("\n");
                }
                sb.append("Stop on failure: ").append(c.isStopOnFailure() ? "ja" : "nein").append("\n");
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
            }).orElse("Chain '" + chainId + "' nicht gefunden.\n");
        } catch (Exception e) {
            log.debug("Could not fetch chain detail for {}", chainId, e);
            return "";
        }
    }

    private static final Map<String, String> BLOCK_SPECS = Map.ofEntries(
            Map.entry("simple", """
                    **Block-Typ: simple** — Einfacher KI-Prompt, eine Antwort.
                    Pflicht-Config: command (string) — Prompt-Text mit Template-Variablen ({{var}}).
                    Optional: model (string), timeout_seconds (int), label (string).
                    Beispiel-Config: { "command": "Analysiere {{input}} und gib JSON zurück" }
                    """),
            Map.entry("shell", """
                    **Block-Typ: shell** — Shell-Kommando ausführen. KEIN API-/DB-Zugriff erlaubt.
                    Pflicht-Config: command (string) — Bash-Kommando, Template-Variablen unterstützt.
                    Optional: timeout_seconds (int), label (string).
                    Beispiel-Config: { "command": "ls -la /tmp/{{run_id}}" }
                    Hinweis: set -euo pipefail empfohlen, nutze actuator/health statt direkter DB.
                    """),
            Map.entry("agentic", """
                    **Block-Typ: agentic** — Mehrstufiger Agent mit REASON+ACT-Phasen, langlaufend.
                    Pflicht-Config: prompt (string), max_total_tokens (int) — kumulatives Budget.
                    Optional: model, max_turns (int), timeout_seconds, label.
                    Beispiel-Config: { "prompt": "Refaktoriere X bis Tests grün", "max_total_tokens": 50000 }
                    Hinweis: REASON nutzt --system-prompt, ACT nutzt --append-system-prompt.
                    """),
            Map.entry("conditional", """
                    **Block-Typ: conditional** — Verzweigung anhand Bedingung.
                    Pflicht-Config: condition (string-Expression, JSONPath/Template).
                    Pflicht-Sub-Blocks: true_blocks (list), false_blocks (list).
                    Beispiel: { "condition": "{{score}} > 0.8", "true_blocks": [...], "false_blocks": [...] }
                    """),
            Map.entry("foreach", """
                    **Block-Typ: foreach** — Iteration über Liste.
                    Pflicht-Config: items (Template-Variable, die eine Liste auflöst).
                    Pflicht-Sub-Blocks: blocks (list).
                    Beispiel: { "items": "{{file_list}}", "blocks": [...] }
                    Hinweis: Iterator-Variable im Sub-Block heisst {{item}}.
                    """),
            Map.entry("while", """
                    **Block-Typ: while** — Schleife mit Abbruchbedingung und max_iterations.
                    Pflicht-Config: condition (string), max_iterations (int).
                    Pflicht-Sub-Blocks: blocks (list).
                    Beispiel: { "condition": "{{retries}} < 3", "max_iterations": 10, "blocks": [...] }
                    """),
            Map.entry("switch", """
                    **Block-Typ: switch** — Multi-Way-Verzweigung anhand Match-Wert.
                    Pflicht-Config: value (string), cases (list von { "match": ..., "blocks": [...] }).
                    Optional: default_blocks (list).
                    """),
            Map.entry("retry", """
                    **Block-Typ: retry** — Wiederholt einen Sub-Block bei Fehler.
                    Pflicht-Config: max_attempts (int), block (single block definition).
                    Optional: backoff_ms (int), retry_on (list von Fehlertyp-Strings).
                    """),
            Map.entry("approval", """
                    **Block-Typ: approval** — Pausiert bis User-Approval.
                    Pflicht-Config: question (string).
                    Optional: options (list von String-Antworten — z.B. ["Yes","No","Skip"]), timeout_seconds.
                    Approve via POST /api/v1/executions/{id}/approve mit { "answer": "Yes" } (Feld: answer, NICHT approved).
                    """),
            Map.entry("sub-workflow", """
                    **Block-Typ: sub-workflow** — Anderen Workflow als Block ausführen.
                    Pflicht-Config: workflow_id (string).
                    Optional: input_context (Map) — wird als context an den Sub-Workflow übergeben.
                    """),
            Map.entry("skill-capability", """
                    **Block-Typ: skill-capability** — Capability eines Skills ausführen (deklarativ).
                    Pflicht-Config: skill_id (string), capability_name (string).
                    Optional: inputs (Map) — wird gegen capability.inputSchema validiert.
                    """),
            Map.entry("human-input", """
                    **Block-Typ: human-input** — Wert vom User abfragen, pausiert bis Antwort.
                    Pflicht-Config: prompt (string).
                    Optional: input_type (text/number/boolean/json), default (any).
                    """),
            Map.entry("integration", """
                    **Block-Typ: integration** — Aktion eines Integration-Providers.
                    Pflicht-Config: provider_id (string), action (string).
                    Optional: inputs (Map) — gegen action.inputSchema validiert.
                    Beispiel: { "provider_id": "github", "action": "create_issue", "inputs": {...} }
                    Lade verfügbare Provider via <fetch type="integration" id="<provider-id>"/>
                    """),
            Map.entry("document", """
                    **Block-Typ: document** — OCR/Dokumenten-Verarbeitung (PDF, Bilder).
                    Pflicht-Config: file_path (string oder Template-Variable).
                    Optional: extract_mode (text/structured), language (Hint für OCR).
                    """)
    );

    private String formatBlockSpec(String blockType) {
        String spec = BLOCK_SPECS.get(blockType);
        if (spec == null) {
            return "Unbekannter Block-Typ '" + blockType + "'. Verfügbare: " + BLOCK_SPECS.keySet() + "\n";
        }
        return spec;
    }

    private String formatWorkflowFull(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                sb.append("**Workflow (Vollständig): ").append(wf.getName()).append("** (").append(projectName).append(")\n");
                sb.append("ID: ").append(wf.getId()).append("\n");
                if (wf.getModel() != null && !wf.getModel().isBlank()) {
                    sb.append("Modell: ").append(wf.getModel()).append("\n");
                }
                if (wf.getInstructions() != null && !wf.getInstructions().isBlank()) {
                    sb.append("Instruktionen: ").append(contextBuilder.truncate(wf.getInstructions().trim(), 600)).append("\n");
                }
                if (wf.getDefaultContext() != null && !wf.getDefaultContext().isEmpty()) {
                    sb.append("Default-Inputs: ").append(wf.getDefaultContext()).append("\n");
                }
                var blocks = wf.getBlocks();
                if (blocks != null && !blocks.isEmpty()) {
                    sb.append("\nBlöcke (").append(blocks.size()).append(", vollständige Config):\n");
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
            }).orElse("Workflow '" + workflowId + "' nicht gefunden.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow-full for {}", workflowId, e);
            return "";
        }
    }

    private String formatFindWorkflow(String query) {
        if (query == null || query.isBlank()) {
            return "Suchbegriff fehlt. Erwartet: <fetch type=\"find_workflow\" id=\"<name-oder-substring>\"/>\n";
        }
        try {
            List<com.automation.entity.Workflow> matches = workflowRepository.findByNameContainingIgnoreCase(query.trim());
            if (matches.isEmpty()) {
                return "Kein Workflow gefunden, dessen Name '" + query + "' enthält.\n";
            }
            var sb = new StringBuilder();
            sb.append("**Workflow-Suche für '").append(query).append("':** ")
                    .append(matches.size()).append(" Treffer\n");
            int limit = Math.min(matches.size(), 20);
            for (int i = 0; i < limit; i++) {
                var wf = matches.get(i);
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                sb.append("  - **").append(wf.getName()).append("** (id: `").append(wf.getId()).append("`, Projekt: ")
                        .append(projectName).append(")\n");
            }
            if (matches.size() > limit) {
                sb.append("  … weitere ").append(matches.size() - limit).append(" Treffer (Suche verfeinern).\n");
            }
            sb.append("\nNutze `<fetch type=\"workflow_detail\" id=\"<id>\"/>` für Details.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not search workflows for '{}'", query, e);
            return "Workflow-Suche fehlgeschlagen.\n";
        }
    }

    private String formatWorkflowInputs(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                sb.append("**Inputs für Workflow: ").append(wf.getName()).append("** (id: ").append(wf.getId()).append(")\n");
                Map<String, Object> ctx = wf.getDefaultContext();
                if (ctx == null || ctx.isEmpty()) {
                    sb.append("(keine default Inputs definiert)\n");
                } else {
                    ctx.forEach((k, v) -> {
                        String valStr = v == null ? "null" : String.valueOf(v);
                        sb.append("  - ").append(k).append(" = ")
                          .append(contextBuilder.truncate(valStr, 100)).append("\n");
                    });
                }
                return sb.toString();
            }).orElse("Workflow '" + workflowId + "' nicht gefunden.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow-inputs for {}", workflowId, e);
            return "";
        }
    }
}
