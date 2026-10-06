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
 * Which TODOs belong to a goal and in which order they come up (TODO dc0566f8).
 */
@Service
@RequiredArgsConstructor
public class GoalMembershipService {

    private final GoalService goalService;
    private final TodoRepository todoRepository;
    private final GoalProjectRepository goalProjectRepository;

    /**
     * Assigns TODOs to the goal. A TODO from another goal moves here (a TODO belongs to
     * at most one goal). Newly assigned TODOs get no rank – they stand behind the
     * ranked ones until someone sets the order. If their project is not yet assigned
     * to the goal, it is added (TODO c60bf914).
     */
    @Transactional
    public GoalDetailResponse assign(String goalId, List<String> todoIds) {
        Goal goal = goalService.find(goalId);
        Set<String> projects = new LinkedHashSet<>();
        for (Todo todo : load(todoIds)) {
            if (!goal.getId().equals(todo.getGoalId())) {
                todo.setGoalId(goal.getId());
                todo.setGoalRank(null);
                todo.setWorkPackageId(null); // a work package only applies in the old goal (TODO 259f4f66, c60bf914)
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
     * Sets the order: the named TODOs get rank 1..n in this sequence, all other
     * TODOs of the goal lose their rank and stand behind them. Every named ID must already belong
     * to the goal – assigning is {@link #assign}.
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
        // Reload the TODOs whose rank is dropped in one query instead of one findById per TODO (TODO f83dcc48)
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
