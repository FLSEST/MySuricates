package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.AssistantSession;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowMemory;
import com.automation.entity.WorkflowRun;
import com.automation.repository.AssistantSessionRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the system prompt and user prompt for the assistant chat.
 * Extracted from AssistantService to separate context-building concerns.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantContextBuilder {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private final WorkflowRunRepository workflowRunRepository;
    private final TodoRepository todoRepository;
    private final ScheduledWorkflowRepository scheduledWorkflowRepository;
    private final ProjectRepository projectRepository;
    private final WishRepository wishRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowMemoryRepository workflowMemoryRepository;
    private final AssistantSessionRepository assistantSessionRepository;
    private final SystemIdentityService systemIdentity;
    private final ObjectMapper objectMapper;

    // ── Page classification ───────────────────────────────────────────────────

    public enum PageCategory { RUNS, WORKFLOWS, TASKS, WISHES, SCHEDULES, OVERVIEW }

    public PageCategory classifyPage(String page) {
        if (page == null) return PageCategory.OVERVIEW;
        return switch (page) {
            case "run", "runs"                     -> PageCategory.RUNS;
            case "workflow", "workflows", "chains" -> PageCategory.WORKFLOWS;
            case "todos", "project", "projects"    -> PageCategory.TASKS;
            case "wishes", "factory"               -> PageCategory.WISHES;
            case "schedules"                       -> PageCategory.SCHEDULES;
            default                                -> PageCategory.OVERVIEW;
        };
    }

    // ── System prompt ────────────────────────────────────────────────────────

    public String buildSystemPrompt(AssistantChatRequest.PageContext ctx, String baseInstructions) {
        var sb = new StringBuilder(4000);

        // 1. Base instructions from resource file
        if (baseInstructions != null && !baseInstructions.isBlank()) {
            sb.append(baseInstructions).append("\n\n");
        }

        // 2. Dynamic platform context
        String page = ctx != null ? ctx.page() : null;
        PageCategory cat = classifyPage(page);

        boolean compactRuns      = cat == PageCategory.TASKS || cat == PageCategory.WISHES || cat == PageCategory.SCHEDULES;
        boolean compactTodos     = cat != PageCategory.TASKS     && cat != PageCategory.OVERVIEW;
        boolean compactWishes    = cat != PageCategory.WISHES    && cat != PageCategory.OVERVIEW;
        boolean compactSchedules = cat != PageCategory.SCHEDULES && cat != PageCategory.WORKFLOWS && cat != PageCategory.OVERVIEW;
        boolean compactProjects  = cat == PageCategory.RUNS || cat == PageCategory.SCHEDULES;
        boolean compactWorkflows = cat == PageCategory.RUNS || cat == PageCategory.SCHEDULES;

        // Load workflows once — reused for appendWorkflows + appendWorkflowMemory
        List<Workflow> allWorkflows = safeLoadWorkflows();
        Map<String, Workflow> workflowById = allWorkflows.stream()
                .collect(Collectors.toMap(Workflow::getId, Function.identity(), (a, b) -> a));

        appendPlatformState(sb, compactRuns);
        appendTodos(sb, compactTodos);
        appendWishes(sb, compactWishes);
        appendUpcomingSchedules(sb, compactSchedules);
        appendProjects(sb, compactProjects, allWorkflows);
        appendWorkflows(sb, compactWorkflows, allWorkflows, ctx);
        appendWorkflowMemory(sb, workflowById);
        if (cat == PageCategory.OVERVIEW) appendRecentSessions(sb);

        // 3. Page-specific context (enriched with step outputs for run pages)
        if (ctx != null) appendPageContext(sb, ctx);

        return sb.toString();
    }

    public String buildPrompt(List<AssistantChatRequest.MessageEntry> history, String currentMessage) {
        if (history == null || history.isEmpty()) {
            return currentMessage;
        }
        var sb = new StringBuilder();
        sb.append("Bisherige Unterhaltung:\n");
        history.forEach(entry -> {
            String speaker = "user".equals(entry.role()) ? "User" : "MySuricates";
            sb.append(speaker).append(": ").append(entry.content()).append("\n");
        });
        sb.append("\nAktuelle Nachricht:\n").append(currentMessage);
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

    private void appendPlatformState(StringBuilder sb, boolean compact) {
        sb.append("## Mein aktueller Zustand\n");
        sb.append("Version: ").append(systemIdentity.getVersion()).append("\n\n");

        if (compact) {
            try {
                long running = workflowRunRepository.countByStatus(RunStatus.RUNNING);
                List<WorkflowRun> recent = workflowRunRepository.findTop10ByOrderByStartedAtDesc();
                List<WorkflowRun> failed = recent.stream()
                        .filter(r -> r.getStatus() == RunStatus.FAILED).toList();
                String failedIds = failed.stream()
                        .map(r -> r.getId().substring(0, Math.min(8, r.getId().length())))
                        .collect(Collectors.joining(", "));
                sb.append("**Runs:** ").append(recent.size()).append(" zuletzt, ")
                        .append(running).append(" laufend, ").append(failed.size()).append(" FAILED");
                if (!failedIds.isEmpty()) sb.append(" (ids: ").append(failedIds).append(")");
                sb.append("\n\n");
            } catch (Exception e) {
                log.debug("Could not load runs for compact context", e);
            }
            return;
        }

        try {
            List<WorkflowRun> active = workflowRunRepository.findByStatus(RunStatus.RUNNING);
            sb.append("**Aktive Ausführungen:** ").append(active.size()).append("\n");
            active.forEach(r -> sb.append("  - ").append(runLabel(r))
                    .append(" (link:").append(runLink(r)).append(")\n"));
        } catch (Exception e) {
            log.debug("Could not load active runs for assistant context", e);
        }

        sb.append("\n**Letzte 10 Ausführungen:**\n");
        try {
            workflowRunRepository.findTop10ByOrderByStartedAtDesc().forEach(r -> {
                sb.append("  - [").append(fmt(r.getStartedAt())).append("] ")
                        .append(runLabel(r)).append(" — ").append(r.getStatus())
                        .append(" (link:").append(runLink(r)).append(")");
                if (r.getStatus() == RunStatus.FAILED) {
                    String err = extractError(r);
                    if (err != null) sb.append(" — Fehler: ").append(truncate(err, 120));
                }
                sb.append("\n");
            });
        } catch (Exception e) {
            log.debug("Could not load recent runs for assistant context", e);
        }
        sb.append("\n");
    }

    private void appendTodos(StringBuilder sb, boolean compact) {
        try {
            List<Todo> open = todoRepository.findAll().stream()
                    .filter(t -> t.getStatus() != Todo.Status.DONE)
                    .collect(Collectors.toList());
            if (compact) {
                if (!open.isEmpty()) {
                    long high   = open.stream().filter(t -> t.getPriority() == Todo.Priority.HIGH).count();
                    long medium = open.stream().filter(t -> t.getPriority() == Todo.Priority.MEDIUM).count();
                    long low    = open.stream().filter(t -> t.getPriority() == Todo.Priority.LOW).count();
                    sb.append("**Todos:** ").append(open.size()).append(" offen (")
                            .append(high).append(" HIGH, ").append(medium).append(" MEDIUM, ")
                            .append(low).append(" LOW)\n");
                }
                return;
            }
            List<Todo> top = open.stream()
                    .sorted((a, b) -> b.getPriority().compareTo(a.getPriority()))
                    .limit(10)
                    .collect(Collectors.toList());
            if (!top.isEmpty()) {
                sb.append("**Offene Todos (").append(top.size()).append("):**\n");
                top.forEach(t -> sb.append("  - [").append(t.getPriority()).append("] ")
                        .append(t.getTitle()).append("\n"));
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load todos for assistant context", e);
        }
    }

    private void appendUpcomingSchedules(StringBuilder sb, boolean compact) {
        try {
            List<ScheduledWorkflow> upcoming = scheduledWorkflowRepository
                    .findSchedulesInRange(Instant.now(), Instant.now().plusSeconds(86400));
            if (compact) {
                if (!upcoming.isEmpty()) {
                    sb.append("**Schedules:** ").append(upcoming.size()).append(" anstehend (nächste 24h)\n");
                }
                return;
            }
            if (!upcoming.isEmpty()) {
                sb.append("**Anstehende Schedules (nächste 24h):**\n");
                upcoming.forEach(s -> {
                    String name = s.getWorkflow() != null ? s.getWorkflow().getName() : s.getTargetName();
                    sb.append("  - ").append(name).append(": ").append(fmt(s.getNextRunAt())).append("\n");
                });
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load schedules for assistant context", e);
        }
    }

    private void appendProjects(StringBuilder sb, boolean compact, List<Workflow> allWorkflows) {
        try {
            var projects = projectRepository.findByArchivedAtIsNull();
            if (compact) {
                if (!projects.isEmpty()) {
                    sb.append("**Projekte:** ").append(projects.size()).append("\n");
                }
                return;
            }
            if (!projects.isEmpty()) {
                Map<String, Long> wfCountByProject = allWorkflows.stream()
                        .filter(w -> w.getProject() != null)
                        .collect(Collectors.groupingBy(w -> w.getProject().getId(), Collectors.counting()));
                sb.append("**Meine Projekte (").append(projects.size()).append("):**\n");
                projects.forEach(p -> sb.append("  - ").append(p.getName())
                        .append(" (ID: ").append(p.getId()).append(", ")
                        .append(wfCountByProject.getOrDefault(p.getId(), 0L)).append(" Workflows)\n"));
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
                sb.append("**Workflows:** ").append(allWorkflows.size()).append(" gesamt (")
                        .append(projectCount).append(" Projekte)\n");
                return;
            }

            String focusProjectId = ctx != null ? ctx.projectId() : null;
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

            sb.append("**Meine Workflows (").append(allWorkflows.size()).append("):**\n");
            toShow.forEach(w -> {
                String projectName = w.getProject() != null ? w.getProject().getName() : "—";
                String projectId = w.getProject() != null ? w.getProject().getId() : "—";
                sb.append("  - [").append(w.getName()).append("] ")
                        .append(projectName).append(" (projekt: ").append(projectId).append(")")
                        .append(" — /workflows/").append(w.getId());
                if (w.getDescription() != null && !w.getDescription().isBlank()) {
                    sb.append(" (").append(truncate(w.getDescription().trim(), 60)).append(")");
                }
                sb.append("\n");
            });
            if (allWorkflows.size() > 25) {
                sb.append("  … und ").append(allWorkflows.size() - 25).append(" weitere\n");
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
            sb.append("## Gelerntes & Fallstricke\n");
            memories.stream().limit(8).forEach(m -> {
                String wfName = workflowById.containsKey(m.getWorkflowId())
                        ? workflowById.get(m.getWorkflowId()).getName()
                        : m.getWorkflowId().substring(0, Math.min(8, m.getWorkflowId().length())) + "…";
                sb.append("  - [").append(wfName).append("]: ").append(truncate(m.getContent(), 150)).append("\n");
            });
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load workflow memory for assistant context", e);
        }
    }

    private void appendRecentSessions(StringBuilder sb) {
        try {
            List<AssistantSession> sessions = assistantSessionRepository.findTop3ByOrderByStartedAtDesc();
            if (sessions.isEmpty()) return;
            sb.append("## Letzte Gespräche (Orientierung)\n");
            sessions.forEach(sess -> {
                String firstUserMsg = extractFirstUserMessage(sess.getMessages());
                if (firstUserMsg != null) {
                    sb.append("  - [").append(fmt(sess.getStartedAt())).append("] ")
                            .append("User fragte: \"").append(truncate(firstUserMsg, 80)).append("\"\n");
                }
            });
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load recent sessions for assistant context", e);
        }
    }

    private String extractFirstUserMessage(String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank() || "[]".equals(messagesJson)) return null;
        try {
            List<Map<String, String>> messages = objectMapper.readValue(messagesJson, new TypeReference<>() {});
            return messages.stream()
                    .filter(m -> "user".equals(m.get("role")))
                    .map(m -> m.get("content"))
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void appendWishes(StringBuilder sb, boolean compact) {
        try {
            List<WishStatus> activeStatuses = List.of(
                    WishStatus.RECEIVED, WishStatus.DECOMPOSING,
                    WishStatus.AWAITING_DECOMPOSE_APPROVAL, WishStatus.AWAITING_CLARIFICATION,
                    WishStatus.AWAITING_APPROVAL, WishStatus.EXECUTING,
                    WishStatus.EVALUATING, WishStatus.REFINING
            );
            List<Wish> allWishes = wishRepository.findAllByOrderByCreatedAtDesc();
            List<Wish> activeWishes = allWishes.stream()
                    .filter(w -> activeStatuses.contains(w.getStatus()))
                    .limit(5).toList();
            List<Wish> failedWishes = allWishes.stream()
                    .filter(w -> w.getStatus() == WishStatus.FAILED || w.getStatus() == WishStatus.CANCELLED)
                    .limit(5).toList();

            if (compact) {
                boolean hasAny = !activeWishes.isEmpty() || !failedWishes.isEmpty();
                if (hasAny) {
                    String failedIds = failedWishes.stream()
                            .map(w -> w.getId().substring(0, Math.min(8, w.getId().length())))
                            .collect(Collectors.joining(", "));
                    sb.append("**Wishes:** ").append(activeWishes.size()).append(" aktiv, ")
                            .append(failedWishes.size()).append(" fehlgeschlagen");
                    if (!failedIds.isEmpty()) sb.append(" (ids: ").append(failedIds).append(")");
                    sb.append("\n");
                }
                return;
            }

            if (!activeWishes.isEmpty()) {
                sb.append("**Aktive Wishes (").append(activeWishes.size()).append("):**\n");
                activeWishes.forEach(w -> sb.append("  - [").append(w.getStatus()).append("] ")
                        .append(truncate(w.getOriginalText(), 80)).append("\n"));
                sb.append("\n");
            }

            if (!failedWishes.isEmpty()) {
                sb.append("**Letzte fehlgeschlagene Wishes:**\n");
                failedWishes.forEach(w -> {
                    sb.append("  - [").append(w.getStatus()).append("] ")
                            .append(truncate(w.getOriginalText(), 80));
                    if (w.getError() != null) sb.append(" — ").append(truncate(w.getError(), 100));
                    sb.append("\n");
                });
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load wishes for assistant context", e);
        }
    }

    private void appendPageContext(StringBuilder sb, AssistantChatRequest.PageContext ctx) {
        sb.append("## Aktueller Kontext des Users\n");
        if (ctx.page() != null) sb.append("Seite: /").append(ctx.page()).append("\n");
        if (ctx.entityId() != null) {
            sb.append("Angezeigtes Element: ID ").append(ctx.entityId()).append("\n");
            if ("run".equals(ctx.page()) || "runs".equals(ctx.page())) {
                try {
                    workflowRunRepository.findByIdWithSteps(ctx.entityId()).ifPresent(run -> {
                        sb.append("### Aktuell angezeigter Run\n");
                        sb.append("Workflow: ").append(runLabel(run)).append("\n");
                        sb.append("Status: ").append(run.getStatus());
                        if (run.getDurationMs() != null) {
                            sb.append(" (").append(run.getDurationMs() / 1000).append("s)");
                        }
                        sb.append("\n");
                        String runErr = extractError(run);
                        if (runErr != null) sb.append("Run-Fehler: ").append(truncate(runErr, 200)).append("\n");
                        List<StepResult> steps = run.getSteps();
                        if (!steps.isEmpty()) {
                            sb.append("Schritte (").append(steps.size()).append("):\n");
                            int firstFailedIdx = -1;
                            for (int i = 0; i < steps.size(); i++) {
                                if (steps.get(i).getStatus() == StepStatus.FAILED) { firstFailedIdx = i; break; }
                            }
                            for (int i = 0; i < steps.size(); i++) {
                                StepResult s = steps.get(i);
                                sb.append("  ").append(s.getStepIndex() + 1).append(". ")
                                        .append(s.getStatus());
                                boolean isFailed = s.getStatus() == StepStatus.FAILED;
                                boolean isBeforeFailed = firstFailedIdx > 0 && i == firstFailedIdx - 1;
                                if (isFailed && s.getOutput() != null) {
                                    sb.append(":\n    ").append(truncate(s.getOutput(), 3000));
                                } else if (isBeforeFailed && s.getOutput() != null) {
                                    sb.append(" (vorheriger Step — Kontext für Fehleranalyse):\n    ")
                                            .append(truncate(s.getOutput(), 1500));
                                }
                                sb.append("\n");
                            }
                        }
                    });
                } catch (Exception ignored) {}
            }
            if ("wishes".equals(ctx.page())) {
                try {
                    wishRepository.findById(ctx.entityId()).ifPresent(w ->
                            sb.append("Wish-Details: [").append(w.getStatus()).append("] ")
                                    .append(truncate(w.getOriginalText(), 100))
                                    .append(", Iterationen: ").append(w.getIterationCount())
                                    .append("/").append(w.getMaxIterations()).append("\n")
                    );
                } catch (Exception ignored) {}
            }
        }
        if (ctx.projectId() != null) {
            try {
                projectRepository.findById(ctx.projectId()).ifPresent(p ->
                        sb.append("Aktives Projekt: ").append(p.getName())
                                .append(" (ID: ").append(p.getId()).append(")\n")
                );
            } catch (Exception ignored) {}
        }
        sb.append("\n");
    }

    // ── Shared helpers (also used by AssistantFetchService) ──────────────────

    public String runLink(WorkflowRun run) {
        try {
            if (run.getWorkflow() == null) return "/runs/" + run.getId();
            return "/workflows/" + run.getWorkflow().getId() + "/run/" + run.getId();
        } catch (Exception e) {
            return "/runs/" + run.getId();
        }
    }

    public String runLabel(WorkflowRun run) {
        try {
            String wfName = run.getWorkflow() != null ? run.getWorkflow().getName() : "Unbekannt";
            String proj = run.getWorkflow() != null && run.getWorkflow().getProject() != null
                    ? " (" + run.getWorkflow().getProject().getName() + ")" : "";
            return wfName + proj;
        } catch (Exception e) {
            return "(gelöschter Workflow)";
        }
    }

    public String fmt(Instant instant) {
        return instant != null ? FMT.format(instant) : "—";
    }

    public String extractError(WorkflowRun run) {
        if (run.getError() == null || run.getError().isEmpty()) return null;
        Object msg = run.getError().get("message");
        return msg != null ? msg.toString() : null;
    }

    public String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max) + "\n[Ausgabe gekürzt: " + max + " von " + s.length() + " Zeichen]";
    }
}
