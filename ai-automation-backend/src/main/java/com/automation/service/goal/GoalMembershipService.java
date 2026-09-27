package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.entity.Goal;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Welche TODOs zu einem Ziel gehoeren und in welcher Reihenfolge sie drankommen (TODO dc0566f8).
 */
@Service
@RequiredArgsConstructor
public class GoalMembershipService {

    private final GoalService goalService;
    private final TodoRepository todoRepository;
    private final GoalProjectRepository goalProjectRepository;

    /**
     * Ordnet TODOs dem Ziel zu. Ein TODO aus einem anderen Ziel wechselt hierher (ein TODO gehoert
     * hoechstens einem Ziel). Neu zugeordnete TODOs bekommen keinen Rang – sie stehen hinter den
     * eingeordneten, bis jemand die Reihenfolge setzt. Ist ihr Projekt dem Ziel noch nicht
     * zugeordnet, kommt es dazu (TODO c60bf914).
     */
    @Transactional
    public GoalDetailResponse assign(String goalId, List<String> todoIds) {
        Goal goal = goalService.find(goalId);
        Set<String> projects = new LinkedHashSet<>();
        for (Todo todo : load(todoIds)) {
            if (!goal.getId().equals(todo.getGoalId())) {
                todo.setGoalId(goal.getId());
                todo.setGoalRank(null);
                todo.setMilestoneId(null); // ein Meilenstein gilt nur im alten Ziel (TODO 259f4f66)
                todoRepository.save(todo);
            }
            if (todo.getProjectId() != null) {
                projects.add(todo.getProjectId());
            }
        }
        projects.forEach(projectId -> goalProjectRepository.linkIfAbsent(goal.getId(), projectId));
        return goalService.detailOf(goal);
    }

    /**
     * Setzt die Reihenfolge: die genannten TODOs bekommen Rang 1..n in dieser Folge, alle anderen
     * TODOs des Ziels verlieren ihren Rang und stehen dahinter. Jede genannte ID muss schon zum
     * Ziel gehoeren – Zuordnen ist {@link #assign}.
     */
    @Transactional
    public GoalDetailResponse reorder(String goalId, List<String> todoIds) {
        Goal goal = goalService.find(goalId);
        List<String> ordered = List.copyOf(new LinkedHashSet<>(todoIds));
        Map<String, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            rankOf.put(ordered.get(i), i + 1);
        }
        Map<String, Todo> byId = load(ordered).stream()
                .collect(Collectors.toMap(Todo::getId, Function.identity(), (a, b) -> a, HashMap::new));
        for (String id : ordered) {
            if (!goal.getId().equals(byId.get(id).getGoalId())) {
                throw new IllegalArgumentException("TODO " + id + " gehoert nicht zum Ziel " + goal.getId());
            }
        }
        List<GoalTodoRow> changed = todoRepository.findGoalRows(goal.getId()).stream()
                .filter(row -> !Objects.equals(rankOf.get(row.id()), row.goalRank()))
                .toList();
        // Die TODOs, deren Rang wegfaellt, in einer Abfrage nachladen statt je TODO ein findById (TODO f83dcc48)
        List<String> dropped = changed.stream().map(GoalTodoRow::id).filter(id -> !byId.containsKey(id)).toList();
        if (!dropped.isEmpty()) {
            todoRepository.findAllById(dropped).forEach(todo -> byId.put(todo.getId(), todo));
        }
        for (GoalTodoRow row : changed) {
            Todo todo = Optional.ofNullable(byId.get(row.id())).orElseThrow();
            todo.setGoalRank(rankOf.get(row.id()));
            todoRepository.save(todo);
        }
        return goalService.detailOf(goal);
    }

    private List<Todo> load(List<String> todoIds) {
        List<String> ids = List.copyOf(new LinkedHashSet<>(todoIds));
        List<Todo> todos = todoRepository.findAllById(ids);
        if (todos.size() != ids.size()) {
            var found = todos.stream().map(Todo::getId).collect(Collectors.toSet());
            String missing = ids.stream().filter(id -> !found.contains(id)).findFirst().orElse("?");
            throw new ResourceNotFoundException("Todo", missing);
        }
        return todos;
    }
}
