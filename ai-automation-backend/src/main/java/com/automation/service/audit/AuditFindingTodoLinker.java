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

import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Offene Findings, deren verwaltetes TODO erledigt gemeldet wird, warten auf den nächsten Audit-Lauf. */
    void markAwaitingVerification(String todoId) {
        for (AuditFindingTodo link : findingTodoRepository.findByTodoId(todoId)) {
            if (!link.isManaged()) continue;
            findingRepository.findById(link.getFindingId()).ifPresent(finding -> {
                if (finding.getStatus() == AuditFindingStatus.OPEN) {
                    finding.setStatus(AuditFindingStatus.AWAITING_VERIFICATION);
                    findingRepository.save(finding);
                }
            });
        }
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
