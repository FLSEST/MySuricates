package com.automation.service.todo;

import com.automation.entity.Todo;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Haelt ein TODO nach einem Projektwechsel mit seinem Ziel stimmig (TODO c60bf914): Das neue Projekt
 * kommt zum Ziel dazu, und ein Teilziel eines anderen Projekts verliert das TODO.
 *
 * <p>Nutzt nur Repositories, keine Klasse aus {@code service.goal}: Das Paket haengt schon von
 * {@code service.todo} ab, der umgekehrte Import waere ein Paketzyklus.
 */
@Component
@RequiredArgsConstructor
public class TodoGoalConsistency {

    private final GoalProjectRepository goalProjectRepository;
    private final GoalMilestoneRepository milestoneRepository;

    /** Nach dem Setzen des neuen Projekts, vor dem Speichern; aendert hoechstens {@code milestoneId}. */
    public void afterProjectChange(Todo todo) {
        if (todo.getGoalId() == null) {
            return;
        }
        if (todo.getProjectId() != null) {
            goalProjectRepository.linkIfAbsent(todo.getGoalId(), todo.getProjectId());
        }
        if (todo.getMilestoneId() != null) {
            milestoneRepository.findById(todo.getMilestoneId())
                    .filter(m -> m.getProjectId() != null && !m.getProjectId().equals(todo.getProjectId()))
                    .ifPresent(m -> todo.setMilestoneId(null));
        }
    }
}
