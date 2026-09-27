package com.automation.service.goal;

import com.automation.dto.response.GoalDetailResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalProject;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Die Projekte eines Ziels (TODO c60bf914): zuordnen, entfernen, ordnen. Dass ein TODO aus einem
 * neuen Projekt sein Projekt automatisch mitbringt, erledigen die Schreibwege der TODOs selbst
 * ({@link GoalMembershipService}, {@link TodoPlanningService}, {@code TodoGoalConsistency}).
 */
@Service
@RequiredArgsConstructor
public class GoalProjectService {

    private final GoalService goalService;
    private final GoalProjectRepository goalProjectRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    /** Haengt das Projekt ans Ende der Zielprojekte; schon zugeordnet = nichts passiert. */
    @Transactional
    public GoalDetailResponse add(String goalId, String projectId) {
        Goal goal = goalService.find(goalId);
        String id = projectId == null ? "" : projectId.trim();
        if (id.isEmpty() || !projectRepository.existsById(id)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        goalProjectRepository.linkIfAbsent(goal.getId(), id);
        return goalService.detailOf(goal);
    }

    /**
     * Loest das Projekt vom Ziel. Haengen daran im Ziel noch Teilziele oder TODOs, ist das ohne
     * {@code cascade} ein Konflikt (409, die Meldung nennt die Anzahlen). Mit {@code cascade} werden
     * die Teilziele des Projekts in diesem Ziel geloescht und seine TODOs vom Ziel geloest – die TODOs
     * selbst bleiben.
     */
    @Transactional
    public void remove(String goalId, String projectId, boolean cascade) {
        Goal goal = goalService.find(goalId);
        long milestones = milestoneRepository.countByGoalIdAndProjectId(goal.getId(), projectId);
        long todos = todoRepository.countByGoalIdAndProjectId(goal.getId(), projectId);
        if (!cascade && (milestones > 0 || todos > 0)) {
            throw new IllegalStateException("Am Projekt haengen in diesem Ziel noch " + milestones + " Teilziel(e) und "
                    + todos + " TODO(s) – mit cascade=true werden die Teilziele geloescht und die TODOs vom Ziel geloest");
        }
        if (cascade) {
            Instant now = clock.instant();
            for (String milestoneId : milestoneRepository.findIdsByGoalAndProject(goal.getId(), projectId)) {
                todoRepository.detachFromMilestone(milestoneId, now); // auch TODOs anderer Projekte darin (Altdaten)
            }
            milestoneRepository.deleteByGoalAndProject(goal.getId(), projectId);
            todoRepository.detachProjectFromGoal(goal.getId(), projectId, now);
        }
        goalProjectRepository.unlink(goal.getId(), projectId);
    }

    /**
     * Neue Reihenfolge: die genannten Projekte in dieser Folge vorn, nicht genannte Zielprojekte in
     * ihrer bisherigen Folge dahinter. Ein Projekt, das dem Ziel nicht zugeordnet ist, ist ein 400.
     */
    @Transactional
    public GoalDetailResponse reorder(String goalId, List<String> projectIds) {
        Goal goal = goalService.find(goalId);
        List<GoalProject> links = goalProjectRepository.findByGoalIdOrderByPositionAscProjectIdAsc(goal.getId());
        Map<String, GoalProject> byProject = links.stream()
                .collect(Collectors.toMap(GoalProject::getProjectId, Function.identity()));
        List<GoalProject> ordered = new ArrayList<>();
        for (String id : new LinkedHashSet<>(projectIds)) {
            GoalProject link = byProject.get(id);
            if (link == null) {
                throw new IllegalArgumentException("Projekt " + id + " ist dem Ziel nicht zugeordnet");
            }
            ordered.add(link);
        }
        links.stream().filter(link -> !ordered.contains(link)).forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
        goalProjectRepository.saveAll(ordered);
        return goalService.detailOf(goal);
    }
}
