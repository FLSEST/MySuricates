package com.automation.service.goal;

import com.automation.dto.response.GoalDetailResponse;
import com.automation.entity.Goal;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * Ordnet TODOs dem Ziel zu. Ein TODO aus einem anderen Ziel wechselt hierher (ein TODO gehoert
     * hoechstens einem Ziel). Neu zugeordnete TODOs bekommen keinen Rang – sie stehen hinter den
     * eingeordneten, bis jemand die Reihenfolge setzt.
     */
    @Transactional
    public GoalDetailResponse assign(String goalId, List<String> todoIds) {
        Goal goal = goalService.find(goalId);
        for (Todo todo : load(todoIds)) {
            if (!goal.getId().equals(todo.getGoalId())) {
                todo.setGoalId(goal.getId());
                todo.setGoalRank(null);
                todo.setMilestoneId(null); // ein Meilenstein gilt nur im alten Ziel (TODO 259f4f66)
                todoRepository.save(todo);
            }
        }
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
        Map<String, Todo> byId = load(ordered).stream().collect(Collectors.toMap(Todo::getId, Function.identity()));
        for (String id : ordered) {
            if (!goal.getId().equals(byId.get(id).getGoalId())) {
                throw new IllegalArgumentException("TODO " + id + " gehoert nicht zum Ziel " + goal.getId());
            }
        }
        for (var row : todoRepository.findGoalRows(goal.getId())) {
            Integer rank = rankOf.get(row.id());
            if (!Objects.equals(rank, row.goalRank())) {
                Todo todo = byId.containsKey(row.id()) ? byId.get(row.id())
                        : todoRepository.findById(row.id()).orElseThrow();
                todo.setGoalRank(rank);
                todoRepository.save(todo);
            }
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
