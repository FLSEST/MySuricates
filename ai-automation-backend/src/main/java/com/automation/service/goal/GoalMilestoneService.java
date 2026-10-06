package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithWorkPackages;
import com.automation.dto.request.CreateGoalMilestoneRequest;
import com.automation.dto.request.UpdateGoalMilestoneRequest;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalWorkPackage;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalMilestoneWorkPackageRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Create, change, delete and read the milestones of a goal with their state (TODO c60bf914): a
 * date for the whole goal, optionally linked to work packages of the same goal. With packages it is
 * reached as soon as all are completed; without packages a human marks it ({@code reached}).
 *
 * <p>Reading is a transactional write: if it recognises a package or milestone as completed or reached
 * for the first time, it stores {@code completedAt}/{@code reachedAt}.
 */
@Service
@RequiredArgsConstructor
public class GoalMilestoneService {

    static final int MAX_TITLE = 200;

    private final GoalRepository goalRepository;
    private final GoalWorkPackageRepository workPackageRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final GoalMilestoneWorkPackageRepository linkRepository;
    private final TodoRepository todoRepository;
    private final Clock clock;

    /** By date, then title; three queries: goal with packages, TODO rows, milestones with their links. */
    @Transactional
    public List<GoalMilestoneResponse> list(String goalId) {
        return snapshot(goalId).milestones();
    }

    @Transactional
    public GoalMilestoneResponse create(String goalId, CreateGoalMilestoneRequest request) {
        String title = GoalDates.title(request.title(), MAX_TITLE, "Ein Meilenstein");
        Instant due = requireDate(request.dueDate());
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        List<String> workPackageIds = packagesOfGoal(goalId, request.workPackageIds());
        GoalMilestone saved = milestoneRepository.save(GoalMilestone.builder()
                .goalId(goalId).title(title).dueDate(due).build());
        workPackageIds.forEach(id -> linkRepository.link(saved.getId(), id));
        return MilestoneProgress.toResponse(saved, workPackageIds, completedOf(workPackageIds), clock.instant());
    }

    /**
     * {@code workPackageIds} replaces the linked packages. {@code reached} marks by hand - only
     * without linked packages (after this change), otherwise 400: with packages it follows from them.
     */
    @Transactional
    public GoalMilestoneResponse update(String goalId, String milestoneId, UpdateGoalMilestoneRequest request) {
        GoalMilestone milestone = find(goalId, milestoneId);
        if (request.title() != null) {
            milestone.setTitle(GoalDates.title(request.title(), MAX_TITLE, "Ein Meilenstein"));
        }
        if (request.dueDate() != null) {
            milestone.setDueDate(requireDate(request.dueDate()));
        }
        List<String> workPackageIds = request.workPackageIds() != null
                ? packagesOfGoal(goalId, request.workPackageIds())
                : linkRepository.findWorkPackageIds(milestone.getId());
        if (request.reached() != null) {
            if (!workPackageIds.isEmpty()) {
                throw new IllegalArgumentException("Der Meilenstein haengt an " + workPackageIds.size()
                        + " Arbeitspaket(en) – erreicht ist er, wenn sie abgeschlossen sind, nicht von Hand");
            }
            milestone.setReachedAt(request.reached()
                    ? Objects.requireNonNullElse(milestone.getReachedAt(), clock.instant()) : null);
        }
        GoalMilestone saved = milestoneRepository.save(milestone);
        if (request.workPackageIds() != null) {
            linkRepository.unlinkAll(saved.getId());
            workPackageIds.forEach(id -> linkRepository.link(saved.getId(), id));
        }
        return MilestoneProgress.toResponse(saved, workPackageIds, completedOf(workPackageIds), clock.instant());
    }

    /** Deletes the milestone with its links; the work packages stay. */
    @Transactional
    public void delete(String goalId, String milestoneId) {
        GoalMilestone milestone = find(goalId, milestoneId);
        linkRepository.unlinkAll(milestone.getId());
        milestoneRepository.deleteById(milestone.getId());
    }

    /**
     * Goal, TODO rows, work packages and milestones with state – for the milestone list and the
     * context variable {@code goal_status}; unknown goal = 404.
     */
    @Transactional
    public GoalSnapshot snapshot(String goalId) {
        GoalWithWorkPackages loaded = goalRepository.findWithWorkPackages(goalId)
                .orElseThrow(() -> new ResourceNotFoundException("Goal", goalId));
        List<GoalTodoRow> rows = todoRepository.findGoalRows(loaded.goal().getId());
        Instant now = clock.instant();
        List<GoalWorkPackageResponse> workPackages = WorkPackageProgress.evaluate(loaded.workPackages(), rows,
                loaded.projectPositions(), now);
        List<GoalMilestoneResponse> milestones = MilestoneProgress.evaluate(
                milestoneRepository.findWithLinks(loaded.goal().getId()), completed(workPackages), now);
        return new GoalSnapshot(loaded.goal(), rows, workPackages, milestones, GoalService.progressOf(rows));
    }

    /** The just-completed packages from already computed package states. */
    static Set<String> completed(List<GoalWorkPackageResponse> workPackages) {
        return workPackages.stream().filter(p -> p.state() == GoalDueState.REACHED)
                .map(GoalWorkPackageResponse::id).collect(Collectors.toSet());
    }

    /** The completed ones among the given packages - one query, only if there are any. */
    private Set<String> completedOf(List<String> workPackageIds) {
        if (workPackageIds.isEmpty()) {
            return Set.of();
        }
        Set<String> done = new HashSet<>();
        todoRepository.countByWorkPackage(workPackageIds).forEach(c -> {
            if (GoalWorkPackage.isComplete(c.total(), c.done())) {
                done.add(c.workPackageId());
            }
        });
        return done;
    }

    /** The given packages without duplicates; each must belong to the goal, otherwise 400. */
    private List<String> packagesOfGoal(String goalId, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<String> ids = requested.stream().filter(Objects::nonNull).map(String::trim).filter(id -> !id.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<String> inGoal = new HashSet<>(workPackageRepository.findIdsInGoal(goalId, ids));
        for (String id : ids) {
            if (!inGoal.contains(id)) {
                throw new IllegalArgumentException("Arbeitspaket " + id + " gehoert nicht zu diesem Ziel");
            }
        }
        return ids.stream().sorted().toList();
    }

    /** A milestone of another goal is as unknown here as a missing one: 404. */
    private GoalMilestone find(String goalId, String milestoneId) {
        return milestoneRepository.findById(milestoneId)
                .filter(m -> m.getGoalId().equals(goalId))
                .orElseThrow(() -> new ResourceNotFoundException("GoalMilestone", milestoneId));
    }

    private static Instant requireDate(String value) {
        Instant due = GoalDates.end(value);
        if (due == null) {
            throw new IllegalArgumentException("Ein Meilenstein braucht ein Datum");
        }
        return due;
    }
}
