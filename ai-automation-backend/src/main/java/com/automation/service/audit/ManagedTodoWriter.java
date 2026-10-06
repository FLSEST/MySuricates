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
 * Creates the managed TODO of an audit finding and keeps its status aligned with the linked
 * findings.
 *
 * <p>Moved from {@code AuditIngestionService} (TODO f57945e2) — the ingestion decides which
 * findings deserve a TODO; how this TODO comes into being and when it closes again is a topic
 * of its own. Distinction from {@link ManagedTodoReconciler}: that one reconciles individual links
 * on request, here the reconciliation runs as part of an ingest over the findings just touched.
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

    /** Does an open finding of this severity deserve a managed TODO? Only CRITICAL and HIGH. */
    static boolean needsTodo(AuditSeverity severity) {
        return severity == AuditSeverity.CRITICAL || severity == AuditSeverity.HIGH;
    }

    /**
     * The managed TODO per finding — reuse an existing one, otherwise create one; a managed
     * link to a deleted TODO is replaced. Links and TODOs of all findings come from
     * one query each instead of findByFindingId and findById per finding (follow-up finding to b2d65a20).
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
     * Creates a new managed TODO for the finding, links it and reports it. Whether one already
     * exists is checked by the caller ({@link #ensureAll} on ingest, {@link AuditFindingTodoLinker} on button press).
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
     * Closes managed TODOs whose findings are all terminal, and reopens them as soon as
     * one comes back — a finding that reappears needs an open task again.
     * If none of the open findings is above the TODO threshold any more ({@link #needsTodo}), the
     * TODO is released ({@link #release}) — unless it was created by hand: that was a decision about the finding,
     * not the threshold's, so it follows the findings like a HIGH one (f2ce6c50).
     */
    void reconcile(List<AuditFinding> touched) {
        List<String> ids = touched.stream().map(AuditFinding::getId).distinct().toList();
        if (ids.isEmpty()) return;
        // One query per level instead of three per link (TODO be14db43): TODOs, their links
        // and their findings are loaded in bulk. A TODO with several managed
        // links is reconciled once.
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
        // Writing is collected (b2d65a20): changed TODOs via saveAll, resolved links via deleteAll.
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
     * A finding below the TODO threshold (e.g. after changed audit criteria HIGH → MEDIUM) would
     * get no TODO today — so its old one is released: unlink and archive, not DONE,
     * because nothing was fixed (TODO ba0dcb3a). If the severity rises again, {@link #ensureAll} creates a
     * new one. Someone is working on an {@code IN_PROGRESS} TODO; it stays untouched.
     * The managed links end up in {@code released}, deleting and saving happens in the caller.
     *
     * @return whether the TODO was released
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
