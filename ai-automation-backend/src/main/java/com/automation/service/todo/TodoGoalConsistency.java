package com.automation.service.todo;

import com.automation.entity.Todo;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Keeps a TODO consistent with its goal after a project change (TODO c60bf914): the new project
 * is added to the goal, and a work package of another project loses the TODO.
 *
 * <p>Uses only repositories, no class from {@code service.goal}: that package already depends on
 * {@code service.todo}, the reverse import would be a package cycle.
 */
@Component
@RequiredArgsConstructor
public class TodoGoalConsistency {

    private final GoalProjectRepository goalProjectRepository;
    private final GoalWorkPackageRepository workPackageRepository;

    /**
     * After setting the new project, before saving; changes at most {@code workPackageId}.
     *
     * <p>Deliberately also runs with module {@code goals} switched off: the goal data is kept and
     * should be consistent after switching back on - a TODO that has meanwhile changed project must
     * not then hang in a work package of the old project.
     */
    public void afterProjectChange(Todo todo) {
        if (todo.getGoalId() == null) {
            return;
        }
        if (todo.getProjectId() != null) {
            goalProjectRepository.linkIfAbsent(todo.getGoalId(), todo.getProjectId());
        }
        if (todo.getWorkPackageId() != null) {
            workPackageRepository.findById(todo.getWorkPackageId())
                    .filter(p -> p.getProjectId() != null && !p.getProjectId().equals(todo.getProjectId()))
                    .ifPresent(p -> todo.setWorkPackageId(null));
        }
    }
}
