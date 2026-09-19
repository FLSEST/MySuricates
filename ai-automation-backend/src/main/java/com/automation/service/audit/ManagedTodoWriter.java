package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditReport;
import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Legt das verwaltete TODO eines Audit-Findings an und hält seinen Status an den verknüpften
 * Findings ausgerichtet.
 *
 * <p>Umzug aus {@code AuditIngestionService} (TODO f57945e2) — die Ingestion entscheidet, welche
 * Findings ein TODO verdienen; wie dieses TODO entsteht und wann es sich wieder schließt, ist ein
 * eigenes Thema. Abgrenzung zu {@link ManagedTodoReconciler}: Der gleicht auf Anfrage einzelne
 * Verknüpfungen ab, hier läuft der Abgleich als Teil eines Ingest über die eben berührten Findings.
 */
@Service
@RequiredArgsConstructor
class ManagedTodoWriter {

    static final String REFERENCE_TYPE = "audit_finding";
    static final String SOURCE_AGENT = "audit-center";

    private final AuditFindingRepository findingRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Das verwaltete TODO dieses Findings — vorhandenes wiederverwenden, sonst anlegen. */
    Todo ensure(AuditFinding finding, AuditReport report) {
        Optional<AuditFindingTodo> existing = findingTodoRepository.findByFindingId(finding.getId()).stream()
                .filter(AuditFindingTodo::isManaged).findFirst();
        if (existing.isPresent()) {
            Todo linkedTodo = todoRepository.findById(existing.get().getTodoId()).orElse(null);
            if (linkedTodo != null) return linkedTodo;
            findingTodoRepository.delete(existing.get());
        }
        Todo todo = todoRepository.save(Todo.builder()
                .title(finding.getTitle())
                .description(describe(finding, report))
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.valueOf(finding.getSeverity().name()))
                .tags(TodoMapper.listToJson(List.of("audit", "audit:" + finding.getAuditKey(), "auto:workflow")))
                .projectId(finding.getProjectId()).source(Todo.Source.WORKFLOW).sourceAgent(SOURCE_AGENT)
                .workflowRunId(report.getWorkflowRunId()).referenceType(REFERENCE_TYPE).referenceId(finding.getId())
                .build());
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(finding.getId()).todoId(todo.getId()).managed(true).build());
        eventPublisher.publishEvent(new TodoCreatedEvent(this, todo));
        return todo;
    }

    /**
     * Schließt verwaltete TODOs, deren Findings alle terminal sind, und öffnet sie wieder, sobald
     * eines zurückkommt — ein Befund, der erneut auftaucht, braucht wieder eine offene Aufgabe.
     */
    void reconcile(List<AuditFinding> touched) {
        List<String> ids = touched.stream().map(AuditFinding::getId).distinct().toList();
        if (ids.isEmpty()) return;
        for (AuditFindingTodo link : findingTodoRepository.findByFindingIdIn(ids)) {
            if (!link.isManaged()) continue;
            Todo todo = todoRepository.findById(link.getTodoId()).orElse(null);
            if (todo == null) continue;
            List<AuditFinding> linked = findingTodoRepository.findByTodoId(todo.getId()).stream()
                    .map(l -> findingRepository.findById(l.getFindingId()).orElse(null))
                    .filter(Objects::nonNull).toList();
            boolean allTerminal = !linked.isEmpty() && linked.stream().allMatch(f -> f.getStatus().isTerminal());
            if (allTerminal && todo.getStatus() != Todo.Status.DONE) {
                todo.setStatus(Todo.Status.DONE);
                todoRepository.save(todo);
            } else if (!allTerminal && todo.getStatus() == Todo.Status.DONE) {
                todo.setStatus(Todo.Status.OPEN);
                todo.setWorkflowRunId(linked.stream().map(AuditFinding::getLastSeenRunId)
                        .filter(Objects::nonNull).findFirst().orElse(null));
                todoRepository.save(todo);
            }
        }
    }

    private static String describe(AuditFinding finding, AuditReport report) {
        String detail = finding.getDescription() != null ? finding.getDescription() : "Audit finding requires remediation.";
        return detail + "\n\nEvidence: " + (finding.getEvidence() != null ? finding.getEvidence() : "not provided")
                + "\n\n[Audit finding](/reports?finding=" + finding.getId() + ")"
                + " · [Evidence run](/workflows/" + report.getWorkflowId() + "/run/" + report.getWorkflowRunId() + ")";
    }
}
