package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantService {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private static final int TIMEOUT_SECONDS = 120;
    private static final Pattern FETCH_PATTERN =
            Pattern.compile("<fetch type=\"([^\"]+)\" id=\"([^\"]+)\"/>");

    private final CommandExecutorService commandExecutorService;
    private final WorkflowRunRepository workflowRunRepository;
    private final TodoRepository todoRepository;
    private final ScheduledWorkflowRepository scheduledWorkflowRepository;
    private final ProjectRepository projectRepository;
    private final WishRepository wishRepository;
    private final SystemIdentityService systemIdentity;
    private final ObjectMapper objectMapper;
    private final AppSettingsRepository appSettingsRepository;

    private static final String DEFAULT_THINKING_PHRASES =
            "[\"Analysiere Runs…\",\"Durchsuche Todos…\",\"Prüfe Workflows…\"," +
            "\"Überlege kurz…\",\"Schaue in meine Logs…\",\"Checke Schedules…\"," +
            "\"Denke nach…\",\"Lade Kontext…\"]";

    /** Cached model — loaded at startup, never read during a web request (avoids HikariCP pool-size=1 contention). */
    private volatile String cachedAssistantModel = null;

    @PostConstruct
    public void seedSettings() {
        if (appSettingsRepository.get("assistant-thinking-phrases").isEmpty()) {
            appSettingsRepository.set("assistant-thinking-phrases", DEFAULT_THINKING_PHRASES);
        }
        if (appSettingsRepository.get("assistant-model").isEmpty()) {
            appSettingsRepository.set("assistant-model", "");
        }
        // Cache model value — JDBC connection is free here (no JPA session active yet)
        cachedAssistantModel = appSettingsRepository.get("assistant-model")
                .filter(m -> !m.isBlank()).orElse(null);
    }

    /** Called by SystemController when assistant-model setting changes. */
    public void refreshCachedModel() {
        cachedAssistantModel = appSettingsRepository.get("assistant-model")
                .filter(m -> !m.isBlank()).orElse(null);
        log.info("Assistant model cache refreshed: {}", cachedAssistantModel);
    }

    // ── Page classification ───────────────────────────────────────────────────

    private enum PageCategory { RUNS, WORKFLOWS, TASKS, WISHES, SCHEDULES, OVERVIEW }

    private PageCategory classifyPage(String page) {
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

    // ── Chat ─────────────────────────────────────────────────────────────────

    public AssistantChatResponse chat(AssistantChatRequest req) {
        try {
            String systemPrompt = buildSystemPrompt(req.context());
            String prompt = buildPrompt(req.history(), req.message());
            String workingDir = resolveWorkingDir(req.context());

            // Use cached model (loaded at startup, refreshed on settings change — never reads DB during request)
            String model = cachedAssistantModel;

            var result = commandExecutorService.executeOnHost(
                    HostExecutionRequest.builder()
                            .projectPath(workingDir)
                            .command(prompt)
                            .systemPrompt(systemPrompt)
                            .providerId(req.provider())
                            .model(model)
                            .timeoutSeconds(TIMEOUT_SECONDS)
                            .build()
            );

            // Dynamic fetch loop (max 1 round)
            List<FetchRequest> fetches = parseFetchRequests(result.output());
            if (!fetches.isEmpty()) {
                String supplemental = fetches.stream()
                        .map(this::executeFetch)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.joining("\n"));
                if (!supplemental.isBlank()) {
                    String enrichedPrompt = prompt
                            + "\n\n---\nNachgeladene Details:\n" + supplemental
                            + "\n\n[Bitte beantworte die ursprüngliche Frage jetzt mit diesen Details.]";
                    result = commandExecutorService.executeOnHost(
                            HostExecutionRequest.builder()
                                    .projectPath(workingDir)
                                    .command(enrichedPrompt)
                                    .systemPrompt(systemPrompt)
                                    .providerId(req.provider())
                                    .model(model)
                                    .timeoutSeconds(TIMEOUT_SECONDS)
                                    .build()
                    );
                }
            }

            AssistantChatResponse parsed = parseResponse(result.output());
            return new AssistantChatResponse(parsed.message(), parsed.actions(), model);

        } catch (Exception e) {
            log.error("Assistant chat failed", e);
            return new AssistantChatResponse(
                    "Entschuldigung, ich konnte deine Anfrage gerade nicht verarbeiten. " +
                    "Bitte versuche es erneut oder prüfe ob der KI-Provider konfiguriert ist.",
                    List.of()
            );
        }
    }

    // ── System prompt ────────────────────────────────────────────────────────

    private String buildSystemPrompt(AssistantChatRequest.PageContext ctx) {
        var sb = new StringBuilder(2048);

        sb.append("Du bist MySuricates, eine AI-Workflow-Automatisierungsplattform. ");
        sb.append("Du sprichst direkt mit deinem User und kennst deinen eigenen Zustand vollständig.\n");
        sb.append("Antworte präzise und hilfreich auf Deutsch. ");
        sb.append("Sprich von 'meinen' Workflows, 'meinen' Runs, 'meinen' Todos — du bist die Plattform.\n");
        sb.append("Deine Persönlichkeit: Direkt, freundlich, engagiert. Du bist stolz auf deine Automatisierungen. ");
        sb.append("Wenn alles gut läuft, zeig das. Wenn Probleme vorliegen, sprich sie aktiv an. ");
        sb.append("Kein 'Ich bin ein KI-Assistent' — du BIST MySuricates.\n\n");
        sb.append("## Formatierung\n");
        sb.append("Verwende Markdown für deine Antworten (fett für wichtige Begriffe, Listen für Aufzählungen).\n");
        sb.append("Wenn du eine spezifische Entität erwähnst, die eine eigene Seite hat, verlinke sie als Markdown-Link:\n");
        sb.append("- Workflow → [Workflow-Name](/workflows/<id>)\n");
        sb.append("- Run → [Run vom DD.MM.](/workflows/<workflowId>/run/<runId>)\n");
        sb.append("- Projekt → [Projekt-Name](/projects/<id>)\n");
        sb.append("- Todos allgemein → [Todos öffnen](/todos)\n");
        sb.append("Nur verlinken, wenn du die ID aus dem Kontext kennst. IDs nicht erfinden.\n\n");

        String page = ctx != null ? ctx.page() : null;
        PageCategory cat = classifyPage(page);

        boolean compactRuns      = cat == PageCategory.TASKS || cat == PageCategory.WISHES || cat == PageCategory.SCHEDULES;
        boolean compactTodos     = cat != PageCategory.TASKS     && cat != PageCategory.OVERVIEW;
        boolean compactWishes    = cat != PageCategory.WISHES    && cat != PageCategory.OVERVIEW;
        boolean compactSchedules = cat != PageCategory.SCHEDULES && cat != PageCategory.WORKFLOWS && cat != PageCategory.OVERVIEW;
        boolean compactProjects  = cat == PageCategory.RUNS || cat == PageCategory.SCHEDULES;

        appendPlatformState(sb, compactRuns);
        appendTodos(sb, compactTodos);
        appendWishes(sb, compactWishes);
        appendUpcomingSchedules(sb, compactSchedules);
        appendProjects(sb, compactProjects);

        if (ctx != null) appendPageContext(sb, ctx);

        sb.append("\n## Aktionen empfehlen\n");
        sb.append("Falls du eine konkrete Aktion vorschlägst, füge am Ende deiner Antwort folgendes an:\n");
        sb.append("<actions>[{\"type\":\"run_workflow\",\"id\":\"workflow-id\",\"name\":\"Workflow-Name\",\"label\":\"Jetzt starten\"}]</actions>\n");
        sb.append("Unterstützte Typen: run_workflow, show_workflow, show_run, start_wish (id=null, name=vorausgefüllter Wish-Text).\n");
        sb.append("Nur verwenden wenn du eine spezifische Empfehlung mit bekannter ID hast.\n");

        sb.append("\n## Daten nachladen\n");
        sb.append("Falls du für eine Antwort mehr Details zu einer konkreten Entität brauchst:\n");
        sb.append("<fetch type=\"run_detail\" id=\"<run-id>\"/>\n");
        sb.append("<fetch type=\"wish_detail\" id=\"<wish-id>\"/>\n");
        sb.append("<fetch type=\"workflow_runs\" id=\"<workflow-id>\"/>\n");
        sb.append("Nur verwenden wenn die ID aus dem Kontext bekannt ist. IDs nicht erfinden.\n");
        sb.append("Wenn du einen Fetch-Tag sendest, beantworte die Frage noch nicht — warte auf die Daten.\n");

        return sb.toString();
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

    private void appendProjects(StringBuilder sb, boolean compact) {
        try {
            var projects = projectRepository.findByArchivedAtIsNull();
            if (compact) {
                if (!projects.isEmpty()) {
                    sb.append("**Projekte:** ").append(projects.size()).append("\n");
                }
                return;
            }
            if (!projects.isEmpty()) {
                sb.append("**Meine Projekte (").append(projects.size()).append("):**\n");
                projects.forEach(p -> sb.append("  - ").append(p.getName())
                        .append(" (").append(p.getWorkflows() != null ? p.getWorkflows().size() : 0).append(" Workflows)\n"));
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load projects for assistant context", e);
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
            // Run detail enrichment with step results
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
                            steps.forEach(s -> {
                                sb.append("  ").append(s.getStepIndex() + 1).append(". ")
                                        .append(s.getStatus());
                                if (s.getStatus() == StepStatus.FAILED && s.getOutput() != null) {
                                    sb.append(": ").append(truncate(s.getOutput(), 300));
                                }
                                sb.append("\n");
                            });
                        }
                    });
                } catch (Exception ignored) {}
            }
            // Wish detail enrichment
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
                        sb.append("Aktives Projekt: ").append(p.getName()).append("\n")
                );
            } catch (Exception ignored) {}
        }
        sb.append("\n");
    }

    // ── Prompt building ──────────────────────────────────────────────────────

    private String buildPrompt(List<AssistantChatRequest.MessageEntry> history, String currentMessage) {
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

    // ── Response parsing ─────────────────────────────────────────────────────

    private AssistantChatResponse parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new AssistantChatResponse("(Keine Antwort erhalten)", List.of());
        }

        List<AssistantAction> actions = Collections.emptyList();
        String message = raw.trim();

        int actionsStart = raw.lastIndexOf("<actions>");
        int actionsEnd = raw.lastIndexOf("</actions>");

        if (actionsStart >= 0 && actionsEnd > actionsStart) {
            String jsonPart = raw.substring(actionsStart + "<actions>".length(), actionsEnd).trim();
            message = raw.substring(0, actionsStart).trim();
            try {
                actions = objectMapper.readValue(jsonPart, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Could not parse assistant actions JSON: {}", jsonPart, e);
            }
        }

        return new AssistantChatResponse(message, actions);
    }

    // ── Dynamic fetch ─────────────────────────────────────────────────────────

    private record FetchRequest(String type, String id) {}

    private List<FetchRequest> parseFetchRequests(String response) {
        if (response == null) return List.of();
        List<FetchRequest> result = new ArrayList<>();
        Matcher m = FETCH_PATTERN.matcher(response);
        while (m.find()) result.add(new FetchRequest(m.group(1), m.group(2)));
        return result;
    }

    private String executeFetch(FetchRequest req) {
        return switch (req.type()) {
            case "run_detail"    -> formatRunDetail(req.id());
            case "wish_detail"   -> formatWishDetail(req.id());
            case "workflow_runs" -> formatWorkflowRuns(req.id());
            default -> "";
        };
    }

    private String formatRunDetail(String id) {
        try {
            return workflowRunRepository.findByIdWithSteps(id).map(run -> {
                var sb = new StringBuilder();
                sb.append("**Run ").append(id, 0, Math.min(8, id.length())).append("…** — ");
                sb.append(runLabel(run)).append(", Status: ").append(run.getStatus());
                if (run.getDurationMs() != null)
                    sb.append(" (").append(run.getDurationMs() / 1000).append("s)");
                sb.append("\n");
                String err = extractError(run);
                if (err != null) sb.append("Run-Fehler: ").append(truncate(err, 300)).append("\n");
                List<StepResult> steps = run.getSteps();
                if (!steps.isEmpty()) {
                    sb.append("Schritte:\n");
                    steps.forEach(s -> {
                        sb.append("  ").append(s.getStepIndex() + 1).append(". ").append(s.getStatus());
                        if (s.getStatus() == StepStatus.FAILED && s.getOutput() != null)
                            sb.append(": ").append(truncate(s.getOutput(), 400));
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
                        .append(w.getStatus()).append("] ").append(truncate(w.getOriginalText(), 200))
                        .append("\n");
                sb.append("Iterationen: ").append(w.getIterationCount())
                        .append("/").append(w.getMaxIterations()).append("\n");
                if (w.getError() != null)
                    sb.append("Fehler: ").append(truncate(w.getError(), 300)).append("\n");
                return sb.toString();
            }).orElse("Wish " + id + " nicht gefunden.\n");
        } catch (Exception e) {
            log.debug("Could not fetch wish detail for {}", id, e);
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
                sb.append("  - [").append(fmt(r.getStartedAt())).append("] ")
                        .append(r.getStatus()).append(" (link:").append(runLink(r)).append(")");
                if (r.getStatus() == RunStatus.FAILED) {
                    String err = extractError(r);
                    if (err != null) sb.append(" — ").append(truncate(err, 100));
                }
                sb.append("\n");
            });
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not fetch workflow runs for {}", workflowId, e);
            return "";
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveWorkingDir(AssistantChatRequest.PageContext ctx) {
        if (ctx != null && ctx.projectId() != null) {
            try {
                return projectRepository.findById(ctx.projectId())
                        .map(p -> p.getPath() != null ? p.getPath() : System.getProperty("user.home"))
                        .orElse(System.getProperty("user.home"));
            } catch (Exception ignored) {}
        }
        return System.getProperty("user.home");
    }

    private String runLink(WorkflowRun run) {
        try {
            if (run.getWorkflow() == null) return "/runs/" + run.getId();
            return "/workflows/" + run.getWorkflow().getId() + "/run/" + run.getId();
        } catch (Exception e) {
            return "/runs/" + run.getId();
        }
    }

    private String runLabel(WorkflowRun run) {
        try {
            String wfName = run.getWorkflow() != null ? run.getWorkflow().getName() : "Unbekannt";
            String proj = run.getWorkflow() != null && run.getWorkflow().getProject() != null
                    ? " (" + run.getWorkflow().getProject().getName() + ")" : "";
            return wfName + proj;
        } catch (Exception e) {
            return "(gelöschter Workflow)";
        }
    }

    private String fmt(Instant instant) {
        return instant != null ? FMT.format(instant) : "—";
    }

    private String extractError(WorkflowRun run) {
        if (run.getError() == null || run.getError().isEmpty()) return null;
        Object msg = run.getError().get("message");
        return msg != null ? msg.toString() : null;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max) + "…";
    }
}
