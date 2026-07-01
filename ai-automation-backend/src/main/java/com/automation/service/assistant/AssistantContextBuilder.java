package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.AssistantSession;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowMemory;
import com.automation.entity.WorkflowRun;
import com.automation.repository.AssistantSessionRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.automation.service.assistant.context.ChainsIndexSection;
import com.automation.service.assistant.context.FailedWorkflowsContextSection;
import com.automation.service.assistant.context.IntegrationsIndexSection;
import com.automation.service.assistant.context.RunsContextSection;
import com.automation.service.assistant.context.SchedulesContextSection;
import com.automation.service.assistant.context.SkillsIndexSection;
import com.automation.service.assistant.context.TodosContextSection;
import com.automation.service.assistant.context.WishesContextSection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the system prompt and user prompt for the assistant chat.
 * Extracted from AssistantService to separate context-building concerns.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantContextBuilder {

    private final WorkflowRunRepository workflowRunRepository;
    private final ProjectRepository projectRepository;
    private final WishRepository wishRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowMemoryRepository workflowMemoryRepository;
    private final AssistantSessionRepository assistantSessionRepository;
    private final ObjectMapper objectMapper;

    // Extracted context sections
    private final RunsContextSection runsSection;
    private final TodosContextSection todosSection;
    private final WishesContextSection wishesSection;
    private final SchedulesContextSection schedulesSection;
    private final SkillsIndexSection skillsSection;
    private final IntegrationsIndexSection integrationsSection;
    private final ChainsIndexSection chainsSection;
    private final BlockTypesIndexSection blockTypesSection;
    private final FailedWorkflowsContextSection failedWorkflowsSection;

    // ── Page classification ───────────────────────────────────────────────────

    public enum PageCategory { RUNS, WORKFLOWS, TASKS, WISHES, SCHEDULES, DEBUG, OVERVIEW }

    public PageCategory classifyPage(String page) {
        if (page == null) return PageCategory.OVERVIEW;
        return switch (page) {
            case "run", "runs"                     -> PageCategory.RUNS;
            case "workflow", "workflows", "chains" -> PageCategory.WORKFLOWS;
            case "todos", "project", "projects"    -> PageCategory.TASKS;
            case "wishes", "factory"               -> PageCategory.WISHES;
            case "schedules"                       -> PageCategory.SCHEDULES;
            case "debug"                           -> PageCategory.DEBUG;
            default                                -> PageCategory.OVERVIEW;
        };
    }

    // ── System prompt ────────────────────────────────────────────────────────

    /**
     * Resolves the UI language code ("de"/"en") to a display name used in the
     * "Always answer in X." instruction. Null/blank/unknown falls back to German,
     * the platform default, preserving legacy behaviour for requests without a language.
     */
    String resolveAnswerLanguageName(String languageCode) {
        return "en".equalsIgnoreCase(languageCode) ? "English" : "German";
    }

    public String buildSystemPrompt(AssistantChatRequest.PageContext ctx, String baseInstructions) {
        return buildSystemPrompt(ctx, baseInstructions, null);
    }

    public String buildSystemPrompt(AssistantChatRequest.PageContext ctx, String baseInstructions, String language) {
        String page = ctx != null ? ctx.page() : null;
        PageCategory cat = classifyPage(page);

        if (cat == PageCategory.RUNS && resolveRunId(ctx) != null) {
            return buildRunFocusedSystemPrompt(ctx, baseInstructions, language);
        }

        var sb = new StringBuilder(4000);

        // 1. Base instructions from resource file
        if (baseInstructions != null && !baseInstructions.isBlank()) {
            sb.append(baseInstructions).append("\n\n");
        }
        sb.append("Always answer in ").append(resolveAnswerLanguageName(language)).append(".\n\n");

        // 2. Dynamic platform context (default: lean, only page-relevant sections)
        boolean includeRunsSection = cat == PageCategory.OVERVIEW;
        boolean includeFailedWorkflowsSection = cat == PageCategory.DEBUG;
        boolean includeTodosSection = cat == PageCategory.TASKS || cat == PageCategory.OVERVIEW;
        boolean includeWishesSection = cat == PageCategory.WISHES || cat == PageCategory.OVERVIEW;
        boolean includeSchedulesSection = cat == PageCategory.SCHEDULES || cat == PageCategory.OVERVIEW;
        boolean includeProjects = cat == PageCategory.WORKFLOWS || cat == PageCategory.TASKS || cat == PageCategory.OVERVIEW;
        boolean compactProjects = cat == PageCategory.TASKS;
        boolean includeWorkflows = cat == PageCategory.WORKFLOWS || cat == PageCategory.TASKS || cat == PageCategory.OVERVIEW;
        boolean compactWorkflows = cat == PageCategory.TASKS;
        boolean includeRepertoire = cat == PageCategory.WORKFLOWS || cat == PageCategory.OVERVIEW;
        boolean compactRepertoire = cat != PageCategory.OVERVIEW;
        boolean includeMemoryAndSessions = cat == PageCategory.OVERVIEW;

        List<Workflow> allWorkflows = includeProjects || includeWorkflows || includeMemoryAndSessions
                ? safeLoadWorkflows()
                : List.of();
        Map<String, Workflow> workflowById = allWorkflows.stream()
                .collect(Collectors.toMap(Workflow::getId, Function.identity(), (a, b) -> a));

        if (includeRunsSection) {
            runsSection.append(sb, false);
        }
        if (includeFailedWorkflowsSection) {
            failedWorkflowsSection.append(sb, false);
        }
        if (includeTodosSection) {
            todosSection.append(sb, cat != PageCategory.OVERVIEW);
        }
        if (includeWishesSection) {
            wishesSection.append(sb, cat != PageCategory.OVERVIEW);
        }
        if (includeSchedulesSection) {
            schedulesSection.append(sb, cat != PageCategory.OVERVIEW);
        }
        if (includeProjects) {
            appendProjects(sb, compactProjects, allWorkflows);
        }
        if (includeWorkflows) {
            appendWorkflows(sb, compactWorkflows, allWorkflows, ctx);
        }
        if (includeRepertoire) {
            skillsSection.append(sb, compactRepertoire);
            integrationsSection.append(sb, compactRepertoire);
            chainsSection.append(sb, compactRepertoire);
            blockTypesSection.append(sb, compactRepertoire);
        }
        if (includeMemoryAndSessions) {
            appendWorkflowMemory(sb, workflowById);
            appendRecentSessions(sb);
        }

        // 3. Page-specific context (enriched with step outputs for run pages)
        if (ctx != null) appendPageContext(sb, ctx);

        return sb.toString();
    }

    private String buildRunFocusedSystemPrompt(AssistantChatRequest.PageContext ctx, String baseInstructions, String language) {
        var sb = new StringBuilder(2000);
        if (baseInstructions != null && !baseInstructions.isBlank()) {
            sb.append(baseInstructions).append("\n\n");
        }
        sb.append("Always answer in ").append(resolveAnswerLanguageName(language)).append(".\n\n");

        sb.append("## Current User Context\n");
        if (ctx.page() != null) sb.append("Page: /").append(ctx.page()).append("\n");
        if (ctx.pathname() != null) sb.append("Path: ").append(ctx.pathname()).append("\n");
        if (ctx.workflowId() != null) sb.append("Workflow ID: ").append(ctx.workflowId()).append("\n");
        if (ctx.runId() != null) sb.append("Run ID: ").append(ctx.runId()).append("\n");
        if (ctx.entityId() != null) sb.append("Displayed element: ID ").append(ctx.entityId()).append("\n");

        String runId = resolveRunId(ctx);
        if (runId != null) {
            try {
                workflowRunRepository.findByIdWithSteps(runId).ifPresent(run -> {
                    sb.append("### Currently displayed run\n");
                    appendRunSummary(sb, run);
                });
            } catch (Exception ignored) {}
        }

        String workflowId = resolveWorkflowId(ctx);
        appendWorkflowContext(sb, workflowId, false);
        sb.append("\n");
        return sb.toString();
    }

    public String buildPrompt(List<AssistantChatRequest.MessageEntry> history, String currentMessage) {
        if (history == null || history.isEmpty()) {
            return currentMessage;
        }
        var sb = new StringBuilder();
        sb.append("Previous conversation:\n");
        history.forEach(entry -> {
            String speaker = "user".equals(entry.role()) ? "User" : "MySuricates";
            sb.append(speaker).append(": ").append(entry.content()).append("\n");
        });
        sb.append("\nCurrent message:\n").append(currentMessage);
        return sb.toString();
    }

    public String resolveWorkingDir(AssistantChatRequest.PageContext ctx) {
        if (ctx != null && ctx.projectId() != null) {
            try {
                return projectRepository.findById(ctx.projectId())
                        .map(p -> p.getPath() != null ? p.getPath() : System.getProperty("user.home"))
                        .orElse(System.getProperty("user.home"));
            } catch (Exception ignored) {}
        }
        if (ctx != null && ctx.workflowId() != null) {
            try {
                return workflowRepository.findByIdWithSkills(ctx.workflowId())
                        .map(Workflow::getProject)
                        .map(project -> project != null && project.getPath() != null ? project.getPath() : System.getProperty("user.home"))
                        .orElse(System.getProperty("user.home"));
            } catch (Exception ignored) {}
        }
        return System.getProperty("user.home");
    }

    // ── Internal context sections ────────────────────────────────────────────

    private List<Workflow> safeLoadWorkflows() {
        try {
            return workflowRepository.findAllWithActiveProject();
        } catch (Exception e) {
            log.debug("Could not load workflows for assistant context", e);
            return List.of();
        }
    }

    private void appendProjects(StringBuilder sb, boolean compact, List<Workflow> allWorkflows) {
        try {
            var projects = projectRepository.findByArchivedAtIsNull();
            if (compact) {
                if (!projects.isEmpty()) {
                    sb.append("**Projects:** ").append(projects.size()).append("\n");
                }
                return;
            }
            if (!projects.isEmpty()) {
                Map<String, Long> wfCountByProject = allWorkflows.stream()
                        .filter(w -> w.getProject() != null)
                        .collect(Collectors.groupingBy(w -> w.getProject().getId(), Collectors.counting()));
                sb.append("**My Projects (").append(projects.size()).append("):**\n");
                projects.forEach(p -> sb.append("  - ").append(p.getName())
                        .append(" (ID: ").append(p.getId()).append(", ")
                        .append(wfCountByProject.getOrDefault(p.getId(), 0L)).append(" workflows)\n"));
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load projects for assistant context", e);
        }
    }

    private void appendWorkflows(StringBuilder sb, boolean compact, List<Workflow> allWorkflows,
                                 AssistantChatRequest.PageContext ctx) {
        if (allWorkflows.isEmpty()) return;
        try {
            if (compact) {
                long projectCount = allWorkflows.stream()
                        .map(w -> w.getProject() != null ? w.getProject().getId() : "")
                        .distinct().count();
                sb.append("**Workflows:** ").append(allWorkflows.size()).append(" total (")
                        .append(projectCount).append(" projects)\n");
                return;
            }

            String focusProjectId = resolveFocusProjectId(ctx);
            List<Workflow> toShow;
            if (focusProjectId != null) {
                toShow = allWorkflows.stream()
                        .filter(w -> w.getProject() != null && focusProjectId.equals(w.getProject().getId()))
                        .sorted(Comparator.comparing(Workflow::getName))
                        .collect(Collectors.toList());
                List<Workflow> others = allWorkflows.stream()
                        .filter(w -> w.getProject() == null || !focusProjectId.equals(w.getProject().getId()))
                        .sorted(Comparator.comparing(Workflow::getName))
                        .limit(Math.max(0, 25 - toShow.size()))
                        .collect(Collectors.toList());
                toShow = new ArrayList<>(toShow);
                toShow.addAll(others);
            } else {
                toShow = allWorkflows.stream()
                        .sorted(Comparator.comparing(Workflow::getName))
                        .limit(25)
                        .collect(Collectors.toList());
            }

            sb.append("**My Workflows (").append(allWorkflows.size()).append("):**\n");
            toShow.forEach(w -> {
                String projectName = w.getProject() != null ? w.getProject().getName() : "—";
                String projectId = w.getProject() != null ? w.getProject().getId() : "—";
                sb.append("  - [").append(w.getName()).append("] ")
                        .append(projectName).append(" (project: ").append(projectId).append(")")
                        .append(" — /workflows/").append(w.getId());
                if (w.getDescription() != null && !w.getDescription().isBlank()) {
                    sb.append(" (").append(truncate(w.getDescription().trim(), 60)).append(")");
                }
                int inputCount = w.getDefaultContext() != null ? w.getDefaultContext().size() : 0;
                if (inputCount > 0) {
                    sb.append(" — ").append(inputCount).append(" inputs");
                }
                sb.append("\n");
            });
            if (allWorkflows.size() > 25) {
                sb.append("  … and ").append(allWorkflows.size() - 25)
                  .append(" more. A workflow's inputs via <fetch type=\"workflow-inputs\" id=\"<workflow-id>\"/>\n");
            } else if (toShow.stream().anyMatch(w -> w.getDefaultContext() != null && !w.getDefaultContext().isEmpty())) {
                sb.append("  A workflow's inputs via <fetch type=\"workflow-inputs\" id=\"<workflow-id>\"/>\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not build workflow list for assistant context", e);
        }
    }

    private void appendWorkflowMemory(StringBuilder sb, Map<String, Workflow> workflowById) {
        try {
            List<WorkflowMemory> memories = workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc();
            if (memories.isEmpty()) return;
            sb.append("## Learnings & Pitfalls\n");
            memories.stream().limit(8).forEach(m -> {
                String wfName = workflowById.containsKey(m.getWorkflowId())
                        ? workflowById.get(m.getWorkflowId()).getName()
                        : m.getWorkflowId().substring(0, Math.min(8, m.getWorkflowId().length())) + "…";
                sb.append("  - [").append(wfName).append("]: ").append(truncate(m.getContent(), 300)).append("\n");
            });
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load workflow memory for assistant context", e);
        }
    }

    private void appendRecentSessions(StringBuilder sb) {
        try {
            List<AssistantSession> sessions = assistantSessionRepository.findTop5ByOrderByStartedAtDesc();
            if (sessions.isEmpty()) return;
            sb.append("## Recent Conversations (Context)\n");
            sessions.forEach(sess -> {
                String[] firstExchange = extractFirstExchange(sess.getMessages());
                if (firstExchange != null) {
                    sb.append("  - [").append(fmt(sess.getStartedAt())).append("] ")
                            .append("User: \"").append(truncate(firstExchange[0], 150)).append("\"");
                    if (firstExchange[1] != null) {
                        sb.append(" → Response: \"").append(truncate(firstExchange[1], 100)).append("\"");
                    }
                    sb.append("\n");
                }
            });
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load recent sessions for assistant context", e);
        }
    }

    /**
     * Extracts the first user message and assistant response from a session's messages JSON.
     * Returns [userMessage, assistantResponse] or null if no user message found.
     */
    private String[] extractFirstExchange(String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank() || "[]".equals(messagesJson)) return null;
        try {
            List<Map<String, String>> messages = objectMapper.readValue(messagesJson, new TypeReference<>() {});
            String userMsg = null;
            String assistantMsg = null;
            for (Map<String, String> m : messages) {
                if (userMsg == null && "user".equals(m.get("role"))) {
                    userMsg = m.get("content");
                } else if (userMsg != null && assistantMsg == null && "assistant".equals(m.get("role"))) {
                    assistantMsg = m.get("content");
                    break;
                }
            }
            if (userMsg == null || userMsg.isBlank()) return null;
            return new String[]{ userMsg, assistantMsg };
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveFocusProjectId(AssistantChatRequest.PageContext ctx) {
        if (ctx == null) return null;
        if (ctx.projectId() != null) return ctx.projectId();
        if (ctx.workflowId() == null) return null;
        try {
            return workflowRepository.findByIdWithSkills(ctx.workflowId())
                    .map(Workflow::getProject)
                    .map(project -> project != null ? project.getId() : null)
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveRunId(AssistantChatRequest.PageContext ctx) {
        if (ctx == null) return null;
        if (ctx.runId() != null) return ctx.runId();
        if ("run".equals(ctx.page()) || "runs".equals(ctx.page())) return ctx.entityId();
        return null;
    }

    private String resolveWorkflowId(AssistantChatRequest.PageContext ctx) {
        if (ctx == null) return null;
        if (ctx.workflowId() != null) return ctx.workflowId();
        if ("workflow".equals(ctx.page()) || "workflows".equals(ctx.page())) return ctx.entityId();
        return null;
    }

    private void appendPageContext(StringBuilder sb, AssistantChatRequest.PageContext ctx) {
        sb.append("## Current User Context\n");
        if (ctx.page() != null) sb.append("Page: /").append(ctx.page()).append("\n");
        if (ctx.pathname() != null) sb.append("Path: ").append(ctx.pathname()).append("\n");
        if (ctx.workflowId() != null) sb.append("Workflow ID: ").append(ctx.workflowId()).append("\n");
        if (ctx.runId() != null) sb.append("Run ID: ").append(ctx.runId()).append("\n");
        if (ctx.entityId() != null) {
            sb.append("Displayed element: ID ").append(ctx.entityId()).append("\n");
        }

        String runId = resolveRunId(ctx);
        if (runId != null) {
            try {
                workflowRunRepository.findByIdWithSteps(runId).ifPresent(run -> {
                    sb.append("### Aktuell angezeigter Run\n");
                    appendRunSummary(sb, run);
                });
            } catch (Exception ignored) {}
        }

        String workflowId = resolveWorkflowId(ctx);
        appendWorkflowContext(sb, workflowId, true);

        if ("wishes".equals(ctx.page()) && ctx.entityId() != null) {
            try {
                wishRepository.findById(ctx.entityId()).ifPresent(w ->
                        sb.append("Wish details: [").append(w.getStatus()).append("] ")
                                .append(truncate(w.getOriginalText(), 100))
                                .append(", iterations: ").append(w.getIterationCount())
                                .append("/").append(w.getMaxIterations()).append("\n")
                );
            } catch (Exception ignored) {}
        }
        if (ctx.projectId() != null) {
            try {
                projectRepository.findById(ctx.projectId()).ifPresent(p -> {
                    sb.append("Active project: ").append(p.getName())
                            .append(" (ID: ").append(p.getId()).append(")\n");
                    appendProjectFileListing(sb, p.getPath(), p.getId());
                });
            } catch (Exception ignored) {}
        } else if (workflowId != null) {
            try {
                workflowRepository.findByIdWithSkills(workflowId)
                        .map(Workflow::getProject)
                        .filter(project -> project != null)
                        .ifPresent(project -> {
                            sb.append("Active project: ").append(project.getName())
                                    .append(" (ID: ").append(project.getId()).append(")\n");
                            appendProjectFileListing(sb, project.getPath(), project.getId());
                        });
            } catch (Exception ignored) {}
        }
        sb.append("\n");
    }

    private void appendRunSummary(StringBuilder sb, WorkflowRun run) {
        sb.append("Workflow: ").append(runLabel(run)).append("\n");
        if (run.getWorkflow() != null) {
            sb.append("Workflow ID: ").append(run.getWorkflow().getId()).append("\n");
            if (run.getWorkflow().getProject() != null) {
                sb.append("Workflow project: ").append(run.getWorkflow().getProject().getName())
                        .append(" (ID: ").append(run.getWorkflow().getProject().getId()).append(")\n");
            }
        }
        sb.append("Status: ").append(run.getStatus());
        if (run.getDurationMs() != null) {
            sb.append(" (").append(run.getDurationMs() / 1000).append("s)");
        }
        sb.append("\n");
        String runErr = extractError(run);
        if (runErr != null) {
            sb.append("Run error: ").append(truncate(runErr, 200)).append("\n");
        }

        List<StepResult> steps = run.getSteps();
        if (!steps.isEmpty()) {
            sb.append("Steps: ").append(steps.size());
            String counts = summarizeStepStatuses(steps);
            if (!counts.isBlank()) {
                sb.append(" (").append(counts).append(")");
            }
            sb.append("\n");
            steps.stream()
                    .filter(step -> step.getStatus() == StepStatus.FAILED)
                    .findFirst()
                    .ifPresent(step -> sb.append("First failed step: ")
                            .append(step.getStepIndex() + 1).append("\n"));
        }
    }

    private String summarizeStepStatuses(List<StepResult> steps) {
        if (steps == null || steps.isEmpty()) return "";
        return java.util.Arrays.stream(StepStatus.values())
                .map(status -> Map.entry(status, steps.stream().filter(step -> step.getStatus() == status).count()))
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private void appendWorkflowContext(StringBuilder sb, String workflowId, boolean includeProjectFiles) {
        if (workflowId == null) return;
        try {
            workflowRepository.findByIdWithSkills(workflowId).ifPresent(workflow -> {
                sb.append("Active workflow: ").append(workflow.getName())
                        .append(" (ID: ").append(workflow.getId()).append(")\n");
                if (workflow.getProject() != null) {
                    sb.append("Workflow project: ").append(workflow.getProject().getName())
                            .append(" (ID: ").append(workflow.getProject().getId()).append(")\n");
                    if (includeProjectFiles) {
                        sb.append("Active project: ").append(workflow.getProject().getName())
                                .append(" (ID: ").append(workflow.getProject().getId()).append(")\n");
                        appendProjectFileListing(sb, workflow.getProject().getPath(), workflow.getProject().getId());
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    // ── Project file listing ──────────────────────────────────────────────

    private static final Set<String> LISTING_IGNORED_DIRS = Set.of(
            "node_modules", ".git", "target", "build", "dist", "__pycache__",
            ".gradle", ".idea", ".vscode", ".next", "venv", ".venv", ".cache"
    );

    private void appendProjectFileListing(StringBuilder sb, String projectPath, String projectId) {
        if (projectPath == null || projectPath.isBlank()) return;
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) return;

        try (Stream<Path> topLevel = Files.list(root).sorted()) {
            var files = new ArrayList<String>();
            for (Path entry : topLevel.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (!LISTING_IGNORED_DIRS.contains(name) && name.equals("docs")) {
                        try (Stream<Path> docs = Files.list(entry).sorted()) {
                            docs.filter(Files::isRegularFile)
                                    .limit(5)
                                    .forEach(f -> files.add("docs/" + f.getFileName()));
                        }
                    }
                } else {
                    files.add(name);
                }
            }
            if (!files.isEmpty()) {
                String listing = String.join(", ", files);
                if (listing.length() > 300) listing = listing.substring(0, 300) + "…";
                sb.append("Project files: ").append(listing).append("\n");
                sb.append("_(Use `<fetch type=\"project_file\" id=\"").append(projectId)
                        .append("/<path>\"/>` to read a file)_\n");
            }
        } catch (IOException e) {
            log.debug("Could not list project files for {}", projectPath, e);
        }
    }

    // ── Shared helpers (delegate to RunsContextSection) ──────────────────

    public String runLink(WorkflowRun run) {
        return runsSection.runLink(run);
    }

    public String runLabel(WorkflowRun run) {
        return runsSection.runLabel(run);
    }

    public String fmt(java.time.Instant instant) {
        return runsSection.fmt(instant);
    }

    public String extractError(WorkflowRun run) {
        return runsSection.extractError(run);
    }

    public String truncate(String s, int max) {
        return runsSection.truncate(s, max);
    }
}
