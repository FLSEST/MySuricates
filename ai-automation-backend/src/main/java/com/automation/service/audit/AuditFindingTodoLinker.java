package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.audit.AuditCenterService.TodoLink;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Die TODO-Verknüpfungen eines Audit-Findings im Audit Center: verwaltetes TODO auf Knopfdruck,
 * vorhandenes TODO verknüpfen oder lösen, Verknüpfungen lesen. Aus {@link AuditCenterService}
 * herausgelöst (a95c1c0e); läuft in dessen Transaktionen, das Anlegen selbst teilt sich den Weg
 * mit dem Ingest über {@link ManagedTodoWriter#create}.
 */
@Service
@RequiredArgsConstructor
class AuditFindingTodoLinker {

    private final AuditFindingRepository findingRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;
    private final ManagedTodoWriter managedTodoWriter;

    /** Daran wird noch gearbeitet; DONE ist erledigt, ARCHIVED freigegeben. */
    private static final Set<Todo.Status> STILL_OPEN = EnumSet.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS, Todo.Status.BACKLOG);

    /** Legt das verwaltete TODO an, solange das Finding noch keine verwaltete Verknüpfung hat. */
    void createManagedTodo(AuditFinding finding) {
        if (findingTodoRepository.findByFindingId(finding.getId()).stream().noneMatch(AuditFindingTodo::isManaged)) {
            managedTodoWriter.create(finding, managedTodoDescription(finding), finding.getLastSeenRunId());
        }
    }

    void linkTodo(AuditFinding finding, String todoId) {
        Todo todo = todoRepository.findById(todoId).orElseThrow(() -> new ResourceNotFoundException("Todo", todoId));
        if (!Objects.equals(finding.getProjectId(), todo.getProjectId())) {
            throw new IllegalArgumentException("Finding and TODO must belong to the same project");
        }
        findingTodoRepository.findByFindingIdAndTodoId(finding.getId(), todoId)
                .orElseGet(() -> findingTodoRepository.save(AuditFindingTodo.builder()
                        .findingId(finding.getId()).todoId(todoId).managed(false).build()));
    }

    void unlinkTodo(String findingId, String todoId) {
        findingTodoRepository.findByFindingIdAndTodoId(findingId, todoId).ifPresent(findingTodoRepository::delete);
        clearAuditReference(findingId, todoId);
    }

    /**
     * Offene Findings, deren TODO erledigt gemeldet wird, warten auf den nächsten Audit-Lauf — ob das
     * TODO verwaltet oder manuell verknüpft ist (ab1ed481: sonst blieb ein Befund OPEN, dessen
     * verwaltetes TODO freigegeben und dessen manuelles erledigt war). Ist ein anderes verknüpftes TODO
     * noch offen ({@link #STILL_OPEN}), ist die Arbeit am Befund nicht erledigt; er bleibt OPEN.
     * Verknüpfungen, TODOs und Findings kommen aus je einer Abfrage, nicht aus einer je Verknüpfung
     * (5fb7b1e9), gespeichert wird gesammelt (e8ca26e9).
     */
    void markAwaitingVerification(String todoId) {
        List<String> findingIds = findingTodoRepository.findByTodoId(todoId).stream()
                .map(AuditFindingTodo::getFindingId)
                .distinct()
                .toList();
        if (findingIds.isEmpty()) return;
        Set<String> busy = findingsWithOtherOpenTodo(findingIds, todoId);
        List<AuditFinding> awaiting = findingRepository.findAllById(findingIds).stream()
                .filter(finding -> finding.getStatus() == AuditFindingStatus.OPEN)
                .filter(finding -> !busy.contains(finding.getId()))
                .toList();
        if (awaiting.isEmpty()) return;
        awaiting.forEach(finding -> finding.setStatus(AuditFindingStatus.AWAITING_VERIFICATION));
        findingRepository.saveAll(awaiting);
    }

    /** Findings, an denen außer {@code todoId} noch ein offenes TODO hängt. */
    private Set<String> findingsWithOtherOpenTodo(List<String> findingIds, String todoId) {
        List<AuditFindingTodo> others = findingTodoRepository.findByFindingIdIn(findingIds).stream()
                .filter(link -> !todoId.equals(link.getTodoId()))
                .toList();
        if (others.isEmpty()) return Set.of();
        Set<String> openTodoIds = todoRepository.findAllById(others.stream().map(AuditFindingTodo::getTodoId).distinct().toList())
                .stream().filter(todo -> STILL_OPEN.contains(todo.getStatus())).map(Todo::getId)
                .collect(Collectors.toSet());
        return others.stream().filter(link -> openTodoIds.contains(link.getTodoId()))
                .map(AuditFindingTodo::getFindingId).collect(Collectors.toSet());
    }

    Map<String, List<TodoLink>> todoLinks(List<String> findingIds) {
        if (findingIds.isEmpty()) return Map.of();
        List<AuditFindingTodo> links = findingTodoRepository.findByFindingIdIn(findingIds);
        Map<String, Todo> todos = todoRepository.findAllById(links.stream().map(AuditFindingTodo::getTodoId).toList())
                .stream().collect(Collectors.toMap(Todo::getId, Function.identity()));
        return links.stream().filter(link -> todos.containsKey(link.getTodoId())).collect(Collectors.groupingBy(
                AuditFindingTodo::getFindingId, Collectors.mapping(link -> {
                    Todo todo = todos.get(link.getTodoId());
                    return new TodoLink(todo.getId(), todo.getTitle(), todo.getStatus(), link.isManaged());
                }, Collectors.toList())));
    }

    /** Sonst zeigt {@code todo.referenceId} (Deep-Link {@code /reports?finding=…}) auf ein Finding, das nicht mehr verknuepft ist. */
    private void clearAuditReference(String findingId, String todoId) {
        todoRepository.findById(todoId).ifPresent(todo -> {
            if (ManagedTodoWriter.REFERENCE_TYPE.equals(todo.getReferenceType())
                    && findingId.equals(todo.getReferenceId())) {
                todo.setReferenceType(null);
                todo.setReferenceId(null);
                todoRepository.save(todo);
            }
        });
    }

    private static String managedTodoDescription(AuditFinding finding) {
        return (finding.getDescription() != null ? finding.getDescription() : "Audit finding requires remediation.")
                + "\n\nEvidence: " + (finding.getEvidence() != null ? finding.getEvidence() : "not provided")
                + "\n\n[Audit finding](/reports?finding=" + finding.getId() + ")"
                + " · [Evidence run](/runs/" + finding.getLastSeenRunId() + ")";
    }
}
