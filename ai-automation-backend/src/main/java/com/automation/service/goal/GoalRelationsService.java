package com.automation.service.goal;

import com.automation.dto.projection.GoalRelationRow;
import com.automation.dto.projection.RunStateRow;
import com.automation.dto.projection.TodoTriggerMatchRow;
import com.automation.dto.response.GoalRelationsResponse;
import com.automation.entity.AuditFindingTodo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Relationskarte eines Ziels (TODO c6ea8f5c): je TODO Herkunft, Bearbeitung und Ergebnis.
 *
 * <p>Alles gebuendelt: die TODOs (und angefragten Kandidaten) als schlanke Projektion, dann je Art
 * eine Abfrage – Projektnamen, Laeufe, Workflow-Namen, aktive Trigger, Audit-Verknuepfungen. Die
 * Zahl der Statements haengt nicht von der Zahl der TODOs ab ({@code RequestCostRatchetTest}).
 * Nur Lesezugriffe in der Transaktion; die Zuordnung selbst ist reine Rechnung
 * ({@link GoalRelationAssembler}).
 */
@Service
@RequiredArgsConstructor
public class GoalRelationsService {

    /** Hoechstens so viele Kandidaten kommen mit in die Karte. */
    static final int MAX_CANDIDATES = 20;

    private final GoalRepository goalRepository;
    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final TodoTriggerRepository triggerRepository;
    private final AuditFindingTodoRepository findingTodoRepository;

    /**
     * @param include IDs noch nicht zugeordneter TODOs, die als Kandidaten mitkommen sollen;
     *                TODOs eines Ziels (auch dieses) werden dabei uebergangen
     */
    @Transactional(readOnly = true)
    public GoalRelationsResponse relations(String goalId, List<String> include) {
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        List<GoalRelationRow> members = todoRepository.findGoalRelationRows(goalId).stream()
                .sorted(GoalRelationAssembler.WORK_ORDER).toList();
        List<GoalRelationRow> candidates = candidates(include, members);
        List<GoalRelationRow> all = Stream.concat(members.stream(), candidates.stream()).toList();
        List<TodoTriggerMatchRow> triggers = triggerRepository.findActiveMatchRows();
        if (all.isEmpty()) {
            return new GoalRelationsResponse(goalId, triggers.size(), List.of());
        }

        Map<String, RunStateRow> runs = runStates(all);
        GoalRelationAssembler.Lookups lookups = new GoalRelationAssembler.Lookups(
                names(ids(all, GoalRelationRow::projectId), projectRepository::findIdAndNameByIdIn),
                names(workflowIds(all, runs.values(), triggers), workflowRepository::findIdAndNameByIdIn),
                runs, findings(all), triggers);

        List<GoalRelationsResponse.TodoRelation> todos = new ArrayList<>(all.size());
        members.forEach(row -> todos.add(GoalRelationAssembler.toRelation(row, false, lookups)));
        candidates.forEach(row -> todos.add(GoalRelationAssembler.toRelation(row, true, lookups)));
        return new GoalRelationsResponse(goalId, triggers.size(), todos);
    }

    /** Angefragte Kandidaten in Anfragereihenfolge, ohne Leerwerte und eigene TODOs, gedeckelt. */
    private List<GoalRelationRow> candidates(List<String> include, List<GoalRelationRow> members) {
        if (include == null || include.isEmpty()) {
            return List.of();
        }
        Set<String> own = members.stream().map(GoalRelationRow::id).collect(Collectors.toSet());
        List<String> wanted = include.stream()
                .filter(Objects::nonNull).map(String::trim)
                .filter(id -> !id.isEmpty() && !own.contains(id))
                .distinct().limit(MAX_CANDIDATES).toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<String, GoalRelationRow> found = todoRepository.findUnassignedRelationRows(wanted).stream()
                .collect(Collectors.toMap(GoalRelationRow::id, Function.identity(), (a, b) -> a));
        return wanted.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    private Map<String, RunStateRow> runStates(List<GoalRelationRow> rows) {
        Set<String> runIds = new LinkedHashSet<>(ids(rows, GoalRelationRow::workflowRunId));
        runIds.addAll(ids(rows, GoalRelationRow::lastFixRunId));
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return runRepository.findRunStates(runIds).stream()
                .collect(Collectors.toMap(RunStateRow::id, Function.identity(), (a, b) -> a));
    }

    /** Workflows der Laeufe, der Workflow-Referenzen, der Fix-Workflows und der Trigger-Ziele. */
    private static Set<String> workflowIds(List<GoalRelationRow> rows, Collection<RunStateRow> runs,
                                           List<TodoTriggerMatchRow> triggers) {
        Set<String> ids = new LinkedHashSet<>(ids(rows, GoalRelationRow::linkedWorkflowId));
        rows.stream().map(GoalRelationAssembler::referencedWorkflowId).filter(Objects::nonNull).forEach(ids::add);
        runs.stream().map(RunStateRow::workflowId).filter(Objects::nonNull).forEach(ids::add);
        triggers.stream().map(TodoTriggerMatchRow::targetWorkflowId).filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    private Map<String, List<String>> findings(List<GoalRelationRow> rows) {
        return findingTodoRepository.findByTodoIdIn(rows.stream().map(GoalRelationRow::id).toList()).stream()
                .collect(Collectors.groupingBy(AuditFindingTodo::getTodoId,
                        Collectors.mapping(AuditFindingTodo::getFindingId, Collectors.toList())));
    }

    /** Id → Name aus einer {@code SELECT id, name}-Abfrage; ohne IDs keine Abfrage. */
    private static Map<String, String> names(Set<String> ids, Function<Collection<String>, List<Object[]>> query) {
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            query.apply(ids).forEach(row -> names.put((String) row[0], (String) row[1]));
        }
        return names;
    }

    private static Set<String> ids(List<GoalRelationRow> rows, Function<GoalRelationRow, String> field) {
        return rows.stream().map(field).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
