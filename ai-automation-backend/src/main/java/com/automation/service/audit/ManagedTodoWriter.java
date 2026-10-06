package com.automation.service.audit;

import com.automation.entity.ActivityLog;
import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Legt das verwaltete TODO eines Audit-Findings an und hält seinen Status an den verknüpften
 * Findings ausgerichtet.
 *
 * <p>Umzug aus {@code AuditIngestionService} (TODO f57945e2) — die Ingestion entscheidet, welche
 * Findings ein TODO verdienen; wie dieses TODO entsteht und wann es sich wieder schließt, ist ein
 * eigenes Thema. Abgrenzung zu {@link ManagedTodoReconciler}: Der gleicht auf Anfrage einzelne
 * Verknüpfungen ab, hier läuft der Abgleich als Teil eines Ingest über die eben berührten Findings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ManagedTodoWriter {

    static final String REFERENCE_TYPE = "audit_finding";
    static final String SOURCE_AGENT = "audit-center";

    private final AuditFindingRepository findingRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ActivityLogService activityLog;

    /** Verdient ein offenes Finding dieser Schwere ein verwaltetes TODO? Nur CRITICAL und HIGH. */
    static boolean needsTodo(AuditSeverity severity) {
        return severity == AuditSeverity.CRITICAL || severity == AuditSeverity.HIGH;
    }

    /**
     * Das verwaltete TODO je Finding — vorhandenes wiederverwenden, sonst anlegen; eine verwaltete
     * Verknüpfung auf ein gelöschtes TODO wird ersetzt. Verknüpfungen und TODOs aller Findings kommen aus
     * je einer Abfrage statt aus findByFindingId und findById je Finding (Folgebefund zu b2d65a20).
     */
    void ensureAll(List<AuditFinding> findings, AuditReport report) {
        if (findings.isEmpty()) return;
        Map<String, AuditFindingTodo> managedByFinding = findingTodoRepository
                .findByFindingIdIn(findings.stream().map(AuditFinding::getId).toList()).stream()
                .filter(AuditFindingTodo::isManaged)
                .collect(Collectors.toMap(AuditFindingTodo::getFindingId, Function.identity(), (first, second) -> first));
        Set<String> existingTodos = managedByFinding.isEmpty() ? Set.of()
                : todoRepository.findAllById(findings.stream().map(f -> managedByFinding.get(f.getId()))
                        .filter(Objects::nonNull).map(AuditFindingTodo::getTodoId).distinct().toList())
                        .stream().map(Todo::getId).collect(Collectors.toSet());
        List<AuditFinding> missing = new ArrayList<>();
        List<AuditFindingTodo> dangling = new ArrayList<>();
        for (AuditFinding finding : findings) {
            AuditFindingTodo link = managedByFinding.get(finding.getId());
            if (link != null && existingTodos.contains(link.getTodoId())) continue;
            if (link != null) dangling.add(link);
            missing.add(finding);
        }
        if (!dangling.isEmpty()) findingTodoRepository.deleteAll(dangling);
        missing.forEach(finding -> create(finding, describe(finding, report), report.getWorkflowRunId(), false));
    }

    /**
     * Legt ein neues verwaltetes TODO für das Finding an, verknüpft es und meldet es. Ob schon eines
     * existiert, prüft der Aufrufer ({@link #ensureAll} beim Ingest, {@link AuditFindingTodoLinker} auf Knopfdruck).
     *
     * @param manual created by hand rather than by the ingest threshold — such a TODO is never released (f2ce6c50)
     */
    Todo create(AuditFinding finding, String description, String workflowRunId, boolean manual) {
        Todo todo = todoRepository.save(Todo.builder()
                .title(finding.getTitle())
                .description(description)
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.valueOf(finding.getSeverity().name()))
                .tags(TodoMapper.listToJson(List.of("audit", "audit:" + finding.getAuditKey(), "auto:workflow")))
                .projectId(finding.getProjectId()).source(Todo.Source.WORKFLOW).sourceAgent(SOURCE_AGENT)
                .workflowRunId(workflowRunId).referenceType(REFERENCE_TYPE).referenceId(finding.getId())
                .build());
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(finding.getId()).todoId(todo.getId()).managed(true).manual(manual).build());
        eventPublisher.publishEvent(new TodoCreatedEvent(this, todo));
        return todo;
    }

    /**
     * Schließt verwaltete TODOs, deren Findings alle terminal sind, und öffnet sie wieder, sobald
     * eines zurückkommt — ein Befund, der erneut auftaucht, braucht wieder eine offene Aufgabe.
     * Liegt keines der offenen Findings mehr über der TODO-Schwelle ({@link #needsTodo}), wird das
     * TODO freigegeben ({@link #release}) — unless it was created by hand: that was a decision about the finding,
     * not the threshold's, so it follows the findings like a HIGH one (f2ce6c50).
     */
    void reconcile(List<AuditFinding> touched) {
        List<String> ids = touched.stream().map(AuditFinding::getId).distinct().toList();
        if (ids.isEmpty()) return;
        // Je Stufe eine Abfrage statt drei je Verknüpfung (TODO be14db43): TODOs, ihre Verknüpfungen
        // und deren Findings werden gebündelt geladen. Ein TODO mit mehreren verwalteten
        // Verknüpfungen wird einmal abgeglichen.
        List<String> todoIds = findingTodoRepository.findByFindingIdIn(ids).stream()
                .filter(AuditFindingTodo::isManaged).map(AuditFindingTodo::getTodoId).distinct().toList();
        if (todoIds.isEmpty()) return;
        Map<String, Todo> todos = todoRepository.findAllById(todoIds).stream()
                .collect(Collectors.toMap(Todo::getId, Function.identity()));
        if (todos.isEmpty()) return;
        Map<String, List<AuditFindingTodo>> linksByTodo = findingTodoRepository.findByTodoIdIn(todos.keySet()).stream()
                .collect(Collectors.groupingBy(AuditFindingTodo::getTodoId));
        List<String> linkedFindingIds = linksByTodo.values().stream().flatMap(List::stream)
                .map(AuditFindingTodo::getFindingId).distinct().toList();
        Map<String, AuditFinding> findings = linkedFindingIds.isEmpty() ? Map.of()
                : findingRepository.findAllById(linkedFindingIds).stream()
                        .collect(Collectors.toMap(AuditFinding::getId, Function.identity()));
        // Geschrieben wird gesammelt (b2d65a20): geänderte TODOs per saveAll, gelöste Verknüpfungen per deleteAll.
        List<Todo> changed = new ArrayList<>();
        List<AuditFindingTodo> released = new ArrayList<>();
        for (String todoId : todoIds) {
            Todo todo = todos.get(todoId);
            if (todo == null) continue;
            List<AuditFindingTodo> todoLinks = linksByTodo.getOrDefault(todoId, List.of());
            List<AuditFinding> linked = todoLinks.stream()
                    .map(l -> findings.get(l.getFindingId()))
                    .filter(Objects::nonNull).toList();
            boolean allTerminal = !linked.isEmpty() && linked.stream().allMatch(f -> f.getStatus().isTerminal());
            boolean manual = todoLinks.stream().anyMatch(l -> l.isManaged() && l.isManual());
            boolean belowThreshold = !manual && !allTerminal && linked.stream()
                    .filter(f -> !f.getStatus().isTerminal()).noneMatch(f -> needsTodo(f.getSeverity()));
            if (belowThreshold) {
                if (release(todo, todoLinks, released)) changed.add(todo);
            } else if (allTerminal && todo.getStatus() != Todo.Status.DONE) {
                todo.setStatus(Todo.Status.DONE);
                changed.add(todo);
            } else if (!allTerminal && todo.getStatus() == Todo.Status.DONE) {
                todo.setStatus(Todo.Status.OPEN);
                todo.setWorkflowRunId(linked.stream().map(AuditFinding::getLastSeenRunId)
                        .filter(Objects::nonNull).findFirst().orElse(null));
                changed.add(todo);
            }
        }
        if (!released.isEmpty()) findingTodoRepository.deleteAll(released);
        if (!changed.isEmpty()) todoRepository.saveAll(changed);
    }

    /**
     * Ein Finding unter der TODO-Schwelle (etwa nach geänderten Audit-Kriterien HIGH → MEDIUM) bekäme
     * heute kein TODO — also gibt es sein altes frei: Verknüpfung lösen und archivieren, nicht DONE,
     * denn behoben ist nichts (TODO ba0dcb3a). Steigt die Schwere wieder, legt {@link #ensureAll} ein
     * neues an. An einem {@code IN_PROGRESS}-TODO arbeitet jemand; das bleibt unangetastet.
     * Die verwalteten Verknüpfungen landen in {@code released}, gelöscht und gespeichert wird im Aufrufer.
     *
     * @return ob das TODO freigegeben wurde
     */
    private boolean release(Todo todo, List<AuditFindingTodo> todoLinks, List<AuditFindingTodo> released) {
        if (todo.getStatus() == Todo.Status.IN_PROGRESS) return false;
        todoLinks.stream().filter(AuditFindingTodo::isManaged).forEach(released::add);
        if (REFERENCE_TYPE.equals(todo.getReferenceType())) {
            todo.setReferenceType(null);
            todo.setReferenceId(null);
        }
        log.info("Managed audit TODO {} ({}) released: no linked open finding is CRITICAL/HIGH any more",
                todo.getId(), todo.getTitle());
        // Visible in the activity log, so a released TODO does not just vanish from the audit center (f2ce6c50)
        activityLog.info(ActivityLog.LogCategory.EXECUTION,
                "Audit-TODO freigegeben: " + todo.getTitle() + " (" + todo.getId() + ") – kein verknüpftes offenes "
                        + "Finding mehr HIGH/CRITICAL, Verknüpfung gelöst, Status ARCHIVED",
                "AuditIngestionService");
        todo.setStatus(Todo.Status.ARCHIVED);
        return true;
    }

    private static String describe(AuditFinding finding, AuditReport report) {
        String detail = finding.getDescription() != null ? finding.getDescription() : "Audit finding requires remediation.";
        return detail + "\n\nEvidence: " + (finding.getEvidence() != null ? finding.getEvidence() : "not provided")
                + "\n\n[Audit finding](/reports?finding=" + finding.getId() + ")"
                + " · [Evidence run](/workflows/" + report.getWorkflowId() + "/run/" + report.getWorkflowRunId() + ")";
    }
}
