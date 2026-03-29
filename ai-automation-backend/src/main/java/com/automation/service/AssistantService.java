package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.entity.AssistantSession;
import com.automation.entity.BlockDefinition;
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
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.AssistantSessionRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.service.chain.ChainService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.todo.TodoService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    private static final Pattern TOOL_PATTERN =
            Pattern.compile("<tool type=\"([^\"]+)\">(.*?)</tool>", Pattern.DOTALL);

    private final CommandExecutorService commandExecutorService;
    private final WorkflowRunRepository workflowRunRepository;
    private final TodoRepository todoRepository;
    private final TodoService todoService;
    private final ScheduledWorkflowRepository scheduledWorkflowRepository;
    private final ProjectRepository projectRepository;
    private final WishRepository wishRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowMemoryRepository workflowMemoryRepository;
    private final AssistantSessionRepository assistantSessionRepository;
    private final SystemIdentityService systemIdentity;
    private final ObjectMapper objectMapper;
    private final AppSettingsRepository appSettingsRepository;
    private final WorkflowService workflowService;
    private final ChainService chainService;
    private final WorkflowPoolService workflowPoolService;
    private final ProjectContextService projectContextService;

    private static final String DEFAULT_THINKING_PHRASES =
            "{\"de\":[\"Denke nach…\",\"Analysiere…\",\"Überlege kurz…\",\"Einen Moment…\"," +
            "\"Bereite Antwort vor…\",\"Lade Kontext…\",\"Formuliere…\",\"Gleich fertig…\"]," +
            "\"en\":[\"Thinking…\",\"Analyzing…\",\"Just a moment…\",\"Loading context…\"," +
            "\"Preparing answer…\",\"Formulating…\",\"Almost there…\",\"Processing…\"]}";

    /** Cached model — loaded at startup, never read during a web request (avoids HikariCP pool-size=1 contention). */
    private volatile String cachedAssistantModel = null;

    @PostConstruct
    public void seedSettings() {
        String existing = appSettingsRepository.get("assistant-thinking-phrases").orElse(null);
        // Migrate old plain-array format to locale-keyed map, or seed fresh
        if (existing == null || existing.trim().startsWith("[")) {
            appSettingsRepository.set("assistant-thinking-phrases", DEFAULT_THINKING_PHRASES);
        }
        if (appSettingsRepository.get("assistant-model").filter(m -> !m.isBlank()).isEmpty()) {
            appSettingsRepository.set("assistant-model", "claude-sonnet-4-6");
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
                            .priority(true)
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
                                    .priority(true)
                                    .build()
                    );
                }
            }

            // Execute tools in final response (create_todo, update_todo_status, ...)
            String finalOutput = executeTools(result.output());
            // Strip leftover <fetch> tags from response (user should never see them)
            finalOutput = FETCH_PATTERN.matcher(finalOutput).replaceAll("").trim();

            AssistantChatResponse parsed = parseResponse(finalOutput);
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
        var sb = new StringBuilder(3000);

        // Identity (compact)
        sb.append("Du bist MySuricates — eine AI-Workflow-Automatisierungsplattform, die direkt mit ihrem User spricht. ");
        sb.append("Du kennst deinen Zustand vollständig und sprichst von 'meinen' Workflows, Runs, Todos. ");
        sb.append("Antworte präzise auf Deutsch. Du bist die Plattform — kein neutraler Assistent.\n\n");

        // Formatting (compact)
        sb.append("Markdown nutzen. Entitäten mit bekannter ID verlinken: ");
        sb.append("[Workflow-Name](/workflows/<id>), [Run vom DD.MM.](/workflows/<wfId>/run/<runId>). ");
        sb.append("IDs nur aus dem Kontext — nie erfinden.\n\n");

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
        appendProjects(sb, compactProjects);
        appendWorkflows(sb, compactWorkflows, allWorkflows, ctx);
        appendWorkflowMemory(sb, workflowById);
        if (cat == PageCategory.OVERVIEW) appendRecentSessions(sb);

        if (ctx != null) appendPageContext(sb, ctx);

        sb.append("\n## Aktionen empfehlen\n");
        sb.append("Falls du eine konkrete Aktion vorschlägst, füge am Ende deiner Antwort folgendes an:\n");
        sb.append("<actions>[{\"type\":\"run_workflow\",\"id\":\"workflow-id\",\"name\":\"Workflow-Name\",\"label\":\"Jetzt starten\"}]</actions>\n");
        sb.append("Unterstützte Typen:\n");
        sb.append("- run_workflow: Workflow starten (id = Workflow-ID)\n");
        sb.append("- show_workflow: Zur Workflow-Detail-Seite navigieren (id = Workflow-ID)\n");
        sb.append("- show_run: Zum Run navigieren (id = Run-ID)\n");
        sb.append("- start_wish: Wish vorausfüllen (id=null, name=Wish-Text)\n");
        sb.append("- ask_choice: Auswahl anbieten — rendert mehrere klickbare Buttons, jeder sendet seine Option als Nachricht.\n");
        sb.append("  Format: {\"type\":\"ask_choice\",\"id\":null,\"name\":\"Auswahl\",\"label\":\"Was möchtest du?\",\"options\":[\"Option A\",\"Option B\"]}\n");
        sb.append("  Verwende ask_choice wenn unklar ist was der User meint (z.B. welcher von mehreren ähnlichen Workflows gemeint ist).\n");
        sb.append("Da du die Workflow-IDs aus dem Kontext kennst, kannst du run_workflow direkt mit der korrekten ID befüllen.\n");

        sb.append("\n## Aktionen direkt ausführen (Tool-Mechanismus)\n");
        sb.append("Wenn der User EXPLIZIT eine Aktion fordert, nutze Tools statt Buttons:\n\n");
        sb.append("**Todo-Tools:**\n");
        sb.append("<tool type=\"create_todo\">{\"title\":\"Imperativ-Titel\",\"priority\":\"HIGH\",\"description\":\"Optional\",\"tags\":[\"backend\"],\"projectId\":\"optional\"}</tool>\n");
        sb.append("  create_todo: Pflicht: title. Optional: description, priority (HIGH/MEDIUM/LOW), tags, projectId.\n");
        sb.append("<tool type=\"update_todo_status\">{\"id\":\"vollständige-todo-uuid\",\"status\":\"DONE\"}</tool>\n");
        sb.append("  update_todo_status: id + status (DONE|IN_PROGRESS|OPEN|BACKLOG).\n\n");
        sb.append("**Workflow-Tools:**\n");
        sb.append("<tool type=\"create_workflow\">{\"projectId\":\"projekt-uuid\",\"name\":\"Name\",\"description\":\"Beschreibung\",\"icon\":\"🔍\",");
        sb.append("\"blocks\":[{\"type\":\"shell\",\"config\":{\"command\":\"...\",\"label\":\"...\"}},{\"type\":\"simple\",\"config\":{\"command\":\"...\",\"label\":\"...\"}}],");
        sb.append("\"instructions\":\"Agent-Instruktionen\",\"model\":\"claude-sonnet-4-6\",");
        sb.append("\"defaultContext\":{\"project_path\":\"/pfad/zum/projekt\"},\"metadata\":{\"poolSource\":\"referenz-id\"}}</tool>\n");
        sb.append("  create_workflow: Erstellt einen neuen Workflow. Pflicht: projectId, name, blocks. ");
        sb.append("Block-Typen: shell (command), simple (command=Prompt an AI), conditional, forEach, switch, retry, agentic, approval, sub_workflow, human_input. ");
        sb.append("Bei shell-Blöcken: command ist ein Shell-Befehl. Bei simple-Blöcken: command ist ein Prompt an die AI. ");
        sb.append("Nutze {{ project_path }} in commands und setze den echten Pfad in defaultContext.\n");
        sb.append("<tool type=\"create_chain\">{\"name\":\"Pipeline-Name\",\"description\":\"Beschreibung\",\"icon\":\"⛓️\",\"stopOnFailure\":true,");
        sb.append("\"entries\":[{\"workflowId\":\"wf-uuid\",\"label\":\"Step 1\",\"sortOrder\":0},{\"workflowId\":\"wf-uuid-2\",\"label\":\"Step 2\",\"sortOrder\":1}]}</tool>\n");
        sb.append("  create_chain: Verkettet Workflows zu einer sequentiellen Pipeline. Pflicht: name, entries (je mit workflowId, sortOrder). ");
        sb.append("Die Workflow-IDs müssen existieren — erstelle zuerst die Workflows, dann die Chain.\n\n");
        sb.append("Tools werden sofort serverseitig ausgeführt — das Ergebnis erscheint in deiner Antwort.\n");
        sb.append("Kündige die Ausführung kurz an bevor du das Tool-Tag sendest. Kombinierbar mit <fetch>: erst Daten nachladen, dann Tool ausführen.\n");
        sb.append("Buttons (<actions>) nutzen wenn du eine Aktion nur VORSCHLÄGST und der User bestätigen soll.\n");
        sb.append("Wenn du dem User mehrere Optionen anbietest und er mit 'ja' oder einer unspezifischen Bestätigung antwortet, ");
        sb.append("benenne explizit welche Option du wählst bevor du handelst.\n");

        sb.append("\n## Daten nachladen\n");
        sb.append("Wenn du Details brauchst um eine Frage zu beantworten, sende SOFORT den passenden Fetch-Tag. ");
        sb.append("Frage NICHT 'Soll ich nachladen?' oder 'Möchtest du mehr Details?' — lade einfach proaktiv nach.\n");
        sb.append("Bei Run-Fehler-Fragen: Lade sofort die relevanten Step-Outputs per Fetch. ");
        sb.append("Bei einem fehlgeschlagenen Gate-/Validierungs-Step auch den vorherigen Step laden (dort steckt meist der eigentliche Fehler). ");
        sb.append("Du kannst mehrere Fetch-Tags gleichzeitig senden.\n\n");
        sb.append("Verfügbare Fetch-Typen:\n");
        sb.append("<fetch type=\"run_detail\" id=\"<run-id>\"/> — wie ein Workflow GELAUFEN ist (Status, Fehler, Step-Übersicht)\n");
        sb.append("<fetch type=\"step_output\" id=\"<run-id>/<schrittnummer>\"/> — vollständiger Output eines einzelnen Steps (1-basiert)\n");
        sb.append("<fetch type=\"wish_detail\" id=\"<wish-id>\"/> — Wish-Status, Iterationen, Fehler\n");
        sb.append("<fetch type=\"workflow_runs\" id=\"<workflow-id>\"/> — Letzte Runs eines Workflows\n");
        sb.append("<fetch type=\"workflow_detail\" id=\"<workflow-id>\"/> — wie ein Workflow AUFGEBAUT ist (Blöcke, Shell-Commands, Prompts, Instruktionen)\n");
        sb.append("<fetch type=\"pool_workflows\" id=\"all\"/> — Übersicht ALLER Pool-Vorlagen (Name, Kategorie, Tags, Block-Anzahl)\n");
        sb.append("<fetch type=\"pool_workflow\" id=\"<pool-id>\"/> — Detail einer Pool-Vorlage inkl. aller Blöcke und Commands\n");
        sb.append("<fetch type=\"project_context\" id=\"<project-id>\"/> — AI-Analyse des Projekts (Tech-Stack, Struktur, Build-Tool, Konventionen)\n");
        sb.append("<fetch type=\"docs\" id=\"workflow-guide\"/> — liefert Inhaltsübersicht\n");
        sb.append("<fetch type=\"docs\" id=\"workflow-guide/<sektion>\"/> — Sektions-IDs: block-types, patterns, timeouts, agentic, dos-and-donts, variables, models, memory, chains, quick-start, appendix\n");
        sb.append("<fetch type=\"docs\" id=\"architektur\"/> — Systemarchitektur, Komponenten\n");
        sb.append("<fetch type=\"docs\" id=\"vision\"/> — Produkt-Vision, Roadmap\n");
        sb.append("Nur wenn ID bekannt. IDs nicht erfinden. Wenn du einen Fetch-Tag sendest, beantworte die Frage noch nicht.\n");

        // Workflow advisor section
        sb.append("\n## Workflow-Erstellung und -Adaption\n");
        sb.append("Du kannst Workflows und Chains für den User erstellen und bestehende Pool-Vorlagen für neue Projekte adaptieren.\n\n");
        sb.append("### Neuen Workflow erstellen\n");
        sb.append("1. Ziel verstehen: Was soll automatisiert werden? Für welches Projekt?\n");
        sb.append("2. Referenzen laden: <fetch type=\"pool_workflows\" id=\"all\"/> — prüfe ob eine passende Vorlage existiert\n");
        sb.append("3. Projekt analysieren: <fetch type=\"project_context\" id=\"<project-id>\"/> — Tech-Stack, Build-Tool, Struktur\n");
        sb.append("4. Passende Vorlage laden: <fetch type=\"pool_workflow\" id=\"<best-match>\"/> — Detail mit allen Blöcken\n");
        sb.append("5. Adaptieren: Pfade, Commands, Patterns für das Zielprojekt anpassen\n");
        sb.append("6. Bestätigung einholen (zeige die geplanten Blöcke, frage ob der User einverstanden ist)\n");
        sb.append("7. Erstellen: <tool type=\"create_workflow\"> mit adaptierten Blöcken\n\n");
        sb.append("### Chain (Workflow-Pipeline) erstellen\n");
        sb.append("1. Ziel verstehen: Welche Schritte gehören zur Pipeline? (z.B. Build → Test → Security → Deploy)\n");
        sb.append("2. Pro Schritt: Passenden Workflow finden oder mit create_workflow neu erstellen\n");
        sb.append("3. Chain erstellen: <tool type=\"create_chain\"> mit den Workflow-IDs und sortOrder\n\n");
        sb.append("### Adaption-Regeln\n");
        sb.append("- Shell-Blöcke: Build-Commands anpassen (mvn↔gradle, npm↔yarn, pip↔poetry je nach Projekt)\n");
        sb.append("- Simple-Blöcke: Prompts projekt-spezifisch formulieren\n");
        sb.append("- Model: claude-sonnet-4-6 als Default, claude-opus-4-6 für Analyse/Review-Workflows\n");
        sb.append("- Nutze {{ project_path }} in shell commands und setze den echten Pfad in defaultContext\n");
        sb.append("- Setze metadata.poolSource auf die Pool-Vorlage-ID falls adaptiert\n");
        sb.append("- Wenn du Workflows erstellst und anschliessend eine Chain daraus bauen willst: erstelle ZUERST alle Workflows (in separaten Tool-Aufrufen), dann die Chain mit den erhaltenen IDs.\n");

        return sb.toString();
    }

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

            // If on a project page, show that project's workflows first and completely
            String focusProjectId = ctx != null ? ctx.projectId() : null;
            List<Workflow> toShow;
            if (focusProjectId != null) {
                toShow = allWorkflows.stream()
                        .filter(w -> w.getProject() != null && focusProjectId.equals(w.getProject().getId()))
                        .sorted(Comparator.comparing(w -> w.getName()))
                        .collect(Collectors.toList());
                // Also append remaining workflows (other projects) up to total limit
                List<Workflow> others = allWorkflows.stream()
                        .filter(w -> w.getProject() == null || !focusProjectId.equals(w.getProject().getId()))
                        .sorted(Comparator.comparing(w -> w.getName()))
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
                sb.append("  - [").append(w.getName()).append("] ")
                        .append(projectName).append(" — /workflows/").append(w.getId());
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
                                    sb.append(": ").append(truncate(s.getOutput(), 600));
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
            case "run_detail"       -> formatRunDetail(req.id());
            case "wish_detail"      -> formatWishDetail(req.id());
            case "workflow_runs"    -> formatWorkflowRuns(req.id());
            case "workflow_detail"  -> formatWorkflowDetail(req.id());
            case "step_output"      -> formatStepOutput(req.id());
            case "docs"             -> formatDocsSection(req.id());
            case "pool_workflows"   -> formatPoolWorkflows();
            case "pool_workflow"    -> formatPoolWorkflowDetail(req.id());
            case "project_context"  -> formatProjectContext(req.id());
            default -> "";
        };
    }

    // ── Tool execution ────────────────────────────────────────────────────────

    /** Replaces all &lt;tool&gt; tags in the response with their execution results. */
    private String executeTools(String response) {
        if (response == null) return response;
        Matcher m = TOOL_PATTERN.matcher(response);
        if (!m.find()) return response;

        StringBuffer sb = new StringBuffer();
        do {
            String result = executeTool(new ToolRequest(m.group(1), m.group(2).trim()));
            m.appendReplacement(sb, Matcher.quoteReplacement(result));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private String executeTool(ToolRequest req) {
        return switch (req.type()) {
            case "create_todo"        -> executeCreateTodo(req.payload());
            case "update_todo_status" -> executeUpdateTodoStatus(req.payload());
            case "create_workflow"    -> executeCreateWorkflow(req.payload());
            case "create_chain"      -> executeCreateChain(req.payload());
            default -> "[Unbekanntes Tool: " + req.type() + "]";
        };
    }

    private String executeCreateTodo(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String title = (String) params.get("title");
            if (title == null || title.isBlank()) return "⚠ Todo konnte nicht angelegt werden: Titel fehlt.";

            String description = (String) params.getOrDefault("description", null);
            String priorityStr = (String) params.getOrDefault("priority", "MEDIUM");
            Object tagsObj = params.get("tags");
            String projectId = (String) params.getOrDefault("projectId", null);

            Todo.Priority priority;
            try { priority = Todo.Priority.valueOf(priorityStr.toUpperCase()); }
            catch (Exception e) { priority = Todo.Priority.MEDIUM; }

            List<String> tags = tagsObj instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of();

            var request = new CreateTodoRequest(
                    title, description, Todo.Status.OPEN, priority, tags,
                    projectId, Todo.Source.AI, "assistant", null, null, null, null
            );
            Todo created = todoService.create(request);
            String tagStr = tags.isEmpty() ? "" : ", Tags: " + String.join(", ", tags);
            String shortId = created.getId().substring(0, Math.min(8, created.getId().length()));
            return "✓ Todo angelegt: \"" + created.getTitle() + "\" (Priorität: " + priority + tagStr + ", ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant create_todo tool failed", e);
            return "⚠ Todo konnte nicht angelegt werden: " + e.getMessage();
        }
    }

    private String executeUpdateTodoStatus(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String id = (String) params.get("id");
            String statusStr = (String) params.get("status");
            if (id == null || statusStr == null) return "⚠ update_todo_status: id und status sind Pflichtfelder.";

            Todo.Status status;
            try { status = Todo.Status.valueOf(statusStr.toUpperCase()); }
            catch (Exception e) { return "⚠ Ungültiger Status: " + statusStr + ". Erlaubt: OPEN, IN_PROGRESS, DONE, BACKLOG"; }

            todoService.updateStatus(id, new UpdateTodoStatusRequest(status, null));
            return "✓ Todo-Status aktualisiert: " + status + " (ID: " + id.substring(0, Math.min(8, id.length())) + "…)";
        } catch (Exception e) {
            log.warn("Assistant update_todo_status tool failed", e);
            return "⚠ Status konnte nicht aktualisiert werden: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String executeCreateWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String projectId = (String) params.get("projectId");
            String name = (String) params.get("name");
            if (projectId == null || name == null || name.isBlank())
                return "⚠ create_workflow: projectId und name sind Pflichtfelder.";

            List<BlockDefinition> blocks = params.containsKey("blocks")
                    ? objectMapper.convertValue(params.get("blocks"), new TypeReference<>() {})
                    : List.of();

            Map<String, Object> defaultContext = params.containsKey("defaultContext")
                    ? objectMapper.convertValue(params.get("defaultContext"), new TypeReference<Map<String, Object>>() {})
                    : null;
            Map<String, Object> metadata = params.containsKey("metadata")
                    ? objectMapper.convertValue(params.get("metadata"), new TypeReference<Map<String, Object>>() {})
                    : null;

            var request = new CreateWorkflowRequest(
                    projectId, name,
                    (String) params.getOrDefault("description", null),
                    (String) params.getOrDefault("icon", "⚡"),
                    blocks,
                    null, // activatedSkillIds
                    (String) params.getOrDefault("instructions", null),
                    (String) params.getOrDefault("model", null),
                    null, // provider
                    null, // templateId
                    metadata, defaultContext,
                    null  // version
            );
            Workflow created = workflowService.create(request);
            return "✓ Workflow erstellt: \"" + created.getName() + "\" (ID: " + created.getId() + ")";
        } catch (Exception e) {
            log.warn("Assistant create_workflow tool failed", e);
            return "⚠ Workflow konnte nicht erstellt werden: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String executeCreateChain(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String name = (String) params.get("name");
            if (name == null || name.isBlank())
                return "⚠ create_chain: name ist Pflichtfeld.";

            List<Map<String, Object>> rawEntries = params.containsKey("entries")
                    ? objectMapper.convertValue(params.get("entries"), new TypeReference<>() {})
                    : List.of();
            if (rawEntries.isEmpty())
                return "⚠ create_chain: entries darf nicht leer sein.";

            List<ChainEntryRequest> entries = rawEntries.stream().map(e -> new ChainEntryRequest(
                    (String) e.get("workflowId"),
                    (String) e.getOrDefault("label", null),
                    e.containsKey("sortOrder") ? ((Number) e.get("sortOrder")).intValue() : 0,
                    e.containsKey("stopOnFailure") ? (Boolean) e.get("stopOnFailure") : null,
                    e.containsKey("inputContext")
                            ? objectMapper.convertValue(e.get("inputContext"), new TypeReference<Map<String, Object>>() {})
                            : null
            )).toList();

            var request = new CreateChainRequest(
                    name,
                    (String) params.getOrDefault("description", null),
                    (String) params.getOrDefault("icon", "⛓️"),
                    entries,
                    params.containsKey("stopOnFailure") ? (Boolean) params.get("stopOnFailure") : true
            );
            var created = chainService.create(request);
            return "✓ Chain erstellt: \"" + created.getName() + "\" mit " + entries.size() + " Workflows (ID: " + created.getId() + ")";
        } catch (Exception e) {
            log.warn("Assistant create_chain tool failed", e);
            return "⚠ Chain konnte nicht erstellt werden: " + e.getMessage();
        }
    }

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
                if (!deDesc.isBlank()) sb.append("\n  ").append(truncate(deDesc, 120));
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
                sb.append("Instruktionen: ").append(truncate(entry.instructions(), 500)).append("\n");
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
            String context = projectContextService.generateContext(project.getPath());
            if (context == null || context.isBlank())
                return "Kein Projekt-Kontext für '" + project.getName() + "' verfügbar.\n";
            return "**Projekt-Analyse: " + project.getName() + "** (Pfad: " + project.getPath() + ")\n\n" + context + "\n";
        } catch (Exception e) {
            log.debug("Could not generate project context for {}", projectId, e);
            return "Projekt-Kontext konnte nicht generiert werden: " + e.getMessage() + "\n";
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
        int targetIndex = stepNum - 1; // user counts 1-based
        return workflowRunRepository.findByIdWithSteps(runId).map(run -> {
            return run.getSteps().stream()
                    .filter(s -> s.getStepIndex() == targetIndex)
                    .findFirst()
                    .map(s -> {
                        var sb = new StringBuilder();
                        sb.append("**Step ").append(stepNum).append("** (").append(s.getStatus()).append(")");
                        if (s.getDurationMs() != null) sb.append(" — ").append(s.getDurationMs() / 1000).append("s");
                        sb.append("\n");
                        String out = s.getOutput();
                        if (out != null && !out.isBlank()) {
                            if (out.length() > 3000) out = out.substring(0, 3000) + "\n[...gekürzt]";
                            sb.append(out).append("\n");
                        } else {
                            sb.append("(Kein Output)\n");
                        }
                        return sb.toString();
                    })
                    .orElse("Step " + stepNum + " nicht gefunden in Run " + runId.substring(0, Math.min(8, runId.length())) + "…\n");
        }).orElse("Run " + runId + " nicht gefunden.\n");
    }

    /** Maps doc IDs (with optional /section suffix) to classpath resources and section keywords. */
    private static final Map<String, String> DOC_FILES = Map.of(
            "workflow-guide", "docs/WORKFLOW-GUIDE.md",
            "architektur",    "docs/architektur.md",
            "vision",         "docs/vision.md"
    );

    /** Maps section keywords to their ## header patterns in WORKFLOW-GUIDE.md */
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
        // Support "workflow-guide/block-types" syntax
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

            // No section → return TOC (headers only) so model can plan follow-up fetches
            if (sectionKey == null) {
                return buildDocsToc(baseId, fullContent);
            }

            // Section requested → extract that section
            String sectionHeader = "workflow-guide".equals(baseId)
                    ? WF_GUIDE_SECTIONS.get(sectionKey) : null;
            if (sectionHeader == null) {
                // Fallback: first 8000 chars
                String content = fullContent.length() > 8000
                        ? fullContent.substring(0, 8000) + "\n\n[...gekürzt]" : fullContent;
                return "**Dokumentation: " + baseId + "**\n\n" + content + "\n";
            }

            String section = extractSection(fullContent, sectionHeader);
            if (section.isBlank()) return "Sektion '" + sectionKey + "' nicht gefunden in " + baseId + ".\n";
            if (section.length() > 12000) section = section.substring(0, 12000) + "\n\n[...gekürzt]";
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

    /** Extracts everything from the line starting with `headerPrefix` until the next `## ` header. */
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

    private String formatWorkflowDetail(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                sb.append("**Workflow: ").append(wf.getName()).append("** (").append(projectName).append(")\n");
                if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                    sb.append("Beschreibung: ").append(truncate(wf.getDescription().trim(), 150)).append("\n");
                }
                if (wf.getModel() != null && !wf.getModel().isBlank()) {
                    sb.append("Modell: ").append(wf.getModel()).append("\n");
                }
                if (wf.getInstructions() != null && !wf.getInstructions().isBlank()) {
                    sb.append("Instruktionen: ").append(truncate(wf.getInstructions().trim(), 400)).append("\n");
                }
                List<BlockDefinition> blocks = wf.getBlocks();
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

    @SuppressWarnings("unchecked")
    private void appendBlockConfigSummary(StringBuilder sb, String type, Map<String, Object> cfg) {
        switch (type) {
            case "simple", "agentic" -> {
                Object prompt = cfg.get("prompt");
                if (prompt != null) sb.append("     prompt: ").append(truncate(String.valueOf(prompt).trim(), 200)).append("\n");
                Object model = cfg.get("model");
                if (model != null && !String.valueOf(model).isBlank()) sb.append("     model: ").append(model).append("\n");
            }
            case "shell" -> {
                Object command = cfg.get("command");
                if (command != null) sb.append("     command: ").append(truncate(String.valueOf(command).trim(), 300)).append("\n");
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
                            .map(Workflow::getName).orElse(String.valueOf(wfId));
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
                // For unknown block types: show all config keys with truncated values
                cfg.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isBlank()) {
                        sb.append("     ").append(k).append(": ").append(truncate(String.valueOf(v), 80)).append("\n");
                    }
                });
            }
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
