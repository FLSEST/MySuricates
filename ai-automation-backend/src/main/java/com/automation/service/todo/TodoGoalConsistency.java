package com.automation.service.todo;

import com.automation.entity.Todo;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Haelt ein TODO nach einem Projektwechsel mit seinem Ziel stimmig (TODO c60bf914): Das neue Projekt
 * kommt zum Ziel dazu, und ein Arbeitspaket eines anderen Projekts verliert das TODO.
 *
 * <p>Nutzt nur Repositories, keine Klasse aus {@code service.goal}: Das Paket haengt schon von
 * {@code service.todo} ab, der umgekehrte Import waere ein Paketzyklus.
 */
@Component
@RequiredArgsConstructor
public class TodoGoalConsistency {

    private final GoalProjectRepository goalProjectRepository;
    private final GoalWorkPackageRepository workPackageRepository;

    /** Nach dem Setzen des neuen Projekts, vor dem Speichern; aendert hoechstens {@code workPackageId}. */
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
