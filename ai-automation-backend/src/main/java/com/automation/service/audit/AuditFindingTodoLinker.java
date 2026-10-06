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

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
  * The TODO links of an audit finding in the Audit Center: managed TODO at the push of a button,
  * link or unlink an existing TODO, read links. Split out of {@link AuditCenterService}
  * (a95c1c0e); runs in its transactions, the creation itself shares its path
  * with the ingest via {@link ManagedTodoWriter#create}.
 */
@Service
@RequiredArgsConstructor
class AuditFindingTodoLinker {

    private final AuditFindingRepository findingRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;
    private final ManagedTodoWriter managedTodoWriter;

    /** Still being worked on; DONE is completed, ARCHIVED released. */
    private static final Set<Todo.Status> STILL_OPEN = EnumSet.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS, Todo.Status.BACKLOG);

    /** Creates the managed TODO as long as the finding has no managed link yet. */
    void createManagedTodo(AuditFinding finding) {
        if (findingTodoRepository.findByFindingId(finding.getId()).stream().noneMatch(AuditFindingTodo::isManaged)) {
            managedTodoWriter.create(finding, managedTodoDescription(finding), finding.getLastSeenRunId(), true);
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
     * Open findings whose TODO is reported done wait for the next audit run — whether the
     * TODO is managed or manually linked (ab1ed481: otherwise a finding stayed OPEN whose
     * managed TODO was released and whose manual one was done). If another linked TODO
     * is still open ({@link #STILL_OPEN}), the work on the finding is not done; it stays OPEN.
     * Links, TODOs and findings come from one query each, not one per link
     * (5fb7b1e9), saving is done collectively (e8ca26e9).
     */
    void markAwaitingVerification(String todoId) {
        markAwaitingVerification(List.of(todoId));
    }

    /** The same for several TODOs completed at the same time (bulk UPDATE); none counts as open for the other. */
    void markAwaitingVerification(Collection<String> todoIds) {
        Set<String> completed = Set.copyOf(todoIds);
        if (completed.isEmpty()) return;
        List<String> findingIds = findingTodoRepository.findByTodoIdIn(completed).stream()
                .map(AuditFindingTodo::getFindingId)
                .distinct()
                .toList();
        if (findingIds.isEmpty()) return;
        Set<String> busy = findingsWithOtherOpenTodo(findingIds, completed);
        List<AuditFinding> awaiting = findingRepository.findAllById(findingIds).stream()
                .filter(finding -> finding.getStatus() == AuditFindingStatus.OPEN)
                .filter(finding -> !busy.contains(finding.getId()))
                .toList();
        if (awaiting.isEmpty()) return;
        awaiting.forEach(finding -> finding.setStatus(AuditFindingStatus.AWAITING_VERIFICATION));
        findingRepository.saveAll(awaiting);
    }

    /** Findings that, besides the completed TODOs, still have an open one attached. */
    private Set<String> findingsWithOtherOpenTodo(List<String> findingIds, Set<String> completed) {
        List<AuditFindingTodo> others = findingTodoRepository.findByFindingIdIn(findingIds).stream()
                .filter(link -> !completed.contains(link.getTodoId()))
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

    /** Otherwise {@code todo.referenceId} (deep link {@code /reports?finding=…}) points to a finding that is no longer linked. */
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
