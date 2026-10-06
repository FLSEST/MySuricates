package com.automation.service.goal;

import com.automation.dto.projection.GoalMilestoneSummaryRow;
import com.automation.dto.projection.GoalStatusCounts;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithWorkPackages;
import com.automation.dto.request.CreateGoalRequest;
import com.automation.dto.request.UpdateGoalRequest;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.dto.response.GoalProgressResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalProject;
import com.automation.entity.Todo;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Create, change, delete and read goals - including progress from the assigned TODOs
 * (TODO dc0566f8). Assigning and ordering the TODOs lives in {@link GoalMembershipService}, the
 * assessment of a single TODO in {@link TodoPlanningService}.
 *
 * <p>Everything reads slim projections ({@link GoalTodoRow}, {@link GoalStatusCounts},
 * {@link GoalProjectRow}) - a goal with 40 TODOs should not pull 40 descriptions through the single
 * SQLite connection. The projects of a goal (TODO c60bf914) are maintained by {@link GoalProjectService}.
 */
@Service
@RequiredArgsConstructor
public class GoalService {

    /** Work order within the same rank: most urgent first. */
    private static final List<Todo.Priority> PRIORITY_ORDER = List.of(
            Todo.Priority.CRITICAL, Todo.Priority.HIGH, Todo.Priority.MEDIUM, Todo.Priority.LOW);

    static final Comparator<GoalTodoRow> WORK_ORDER = workOrder(GoalTodoRow::goalRank, GoalTodoRow::priority, GoalTodoRow::createdAt);

    /** Working order for every row kind: rank (no rank last), then priority, then age. */
    static <T> Comparator<T> workOrder(Function<T, Integer> rank, Function<T, Todo.Priority> priority,
                                       Function<T, Instant> createdAt) {
        return Comparator.comparing(rank, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(row -> PRIORITY_ORDER.indexOf(priority.apply(row)))
                .thenComparing(createdAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()));
    }

    private final GoalRepository goalRepository;
    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final GoalGuardLookup guardLookup;
    private final GoalWorkPackageRepository workPackageRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final GoalProjectRepository goalProjectRepository;
    private final GoalDetailAssembler detailAssembler;
    private final Clock clock;

    /**
     * Without status filter: active and completed goals, archived ones only on request. With
     * {@code projectId} only the goals this project is assigned to (TODO c60bf914). Per goal the
     * next unreached milestone - for all goals from one query with package counters.
     */
    @Transactional(readOnly = true)
    public List<GoalResponse> list(List<Goal.Status> statuses, String projectId) {
        List<Goal.Status> wanted = statuses == null || statuses.isEmpty()
                ? List.of(Goal.Status.ACTIVE, Goal.Status.DONE) : statuses;
        List<Goal> goals = projectId == null || projectId.isBlank()
                ? goalRepository.findByStatusInOrderByCreatedAtDesc(wanted)
                : goalRepository.findForProject(wanted, projectId.trim());
        if (goals.isEmpty()) {
            return List.of();
        }
        List<String> ids = goals.stream().map(Goal::getId).toList();
        Map<String, GoalStatusCounts> counts = todoRepository.countByGoal(ids)
                .stream().collect(Collectors.toMap(GoalStatusCounts::goalId, Function.identity()));
        Map<String, List<GoalResponse.ProjectRef>> projects =
                GoalProjectSummaries.refsByGoal(goalProjectRepository.findRowsForGoals(ids));
        Map<String, List<GoalMilestoneSummaryRow>> milestones = milestoneRepository.findSummaries(ids).stream()
                .collect(Collectors.groupingBy(GoalMilestoneSummaryRow::goalId));
        Map<String, GoalGuardResponse> guards = guardLookup.byGoal(goals);
        Instant now = clock.instant();
        return goals.stream()
                .map(goal -> toResponse(goal, progressOf(counts.get(goal.getId())), guards.get(goal.getId()),
                        projects.getOrDefault(goal.getId(), List.of()),
                        MilestoneProgress.nextOf(milestones.getOrDefault(goal.getId(), List.of()), now)))
                .toList();
    }

    /** A goal with progress and guard, without TODO list (response of {@code POST /goals/{id}/guard}). */
    @Transactional(readOnly = true)
    public GoalResponse get(String id) {
        return detailOf(find(id)).goal();
    }

    /**
     * Writing, because reading may recognise a work package or milestone as completed or
     * reached for the first time - then {@code completedAt}/{@code reachedAt} is
     * stored (TODO 259f4f66, c60bf914). Otherwise it writes nothing. Goal with packages in one
     * query, the rest in {@link GoalDetailAssembler}.
     */
    @Transactional
    public GoalDetailResponse detail(String id) {
        GoalWithWorkPackages loaded = goalRepository.findWithWorkPackages(id)
                .orElseThrow(() -> new ResourceNotFoundException("Goal", id));
        return detailAssembler.detailOf(loaded.goal(), loaded.workPackages());
    }

    /** With {@code projectIds} directly with projects in this order; an unknown project is a 404. */
    @Transactional
    public GoalResponse create(CreateGoalRequest request) {
        List<String> projectIds = request.projectIds() == null ? List.of() : request.projectIds().stream()
                .filter(Objects::nonNull).map(String::trim).filter(id -> !id.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
        for (String projectId : projectIds) {
            if (!projectRepository.existsById(projectId)) {
                throw new ResourceNotFoundException("Project", projectId);
            }
        }
        Goal goal = goalRepository.save(Goal.builder()
                .title(request.title().trim())
                .description(blankToNull(request.description()))
                .targetDate(parseDate(request.targetDate()))
                .build());
        List<GoalResponse.ProjectRef> projects = List.of();
        if (!projectIds.isEmpty()) {
            for (int i = 0; i < projectIds.size(); i++) {
                goalProjectRepository.save(new GoalProject(goal.getId(), projectIds.get(i), i));
            }
            projects = GoalProjectSummaries.refs(goalProjectRepository.findRows(goal.getId()));
        }
        return toResponse(goal, GoalProgressResponse.of(0, 0, 0, 0, 0), GoalGuardResponse.NONE, projects, null);
    }

    @Transactional
    public GoalResponse update(String id, UpdateGoalRequest request) {
        Goal goal = find(id);
        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new IllegalArgumentException("Ein Ziel braucht einen Titel");
            }
            goal.setTitle(request.title().trim());
        }
        if (request.description() != null) goal.setDescription(blankToNull(request.description()));
        if (request.status() != null) goal.setStatus(request.status());
        if (request.targetDate() != null) goal.setTargetDate(parseDate(request.targetDate()));
        Goal saved = goalRepository.save(goal);
        return detailOf(saved).goal();
    }

    /** Detaches the TODOs from the goal and deletes it; the TODOs are kept. */
    @Transactional
    public void delete(String id) {
        Goal goal = find(id);
        todoRepository.detachFromGoal(goal.getId(), Instant.now());
        goalRepository.deleteById(goal.getId());
    }

    Goal find(String id) {
        return goalRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Goal", id));
    }

    /** Goal view after a change; the packages are reloaded for it. */
    GoalDetailResponse detailOf(Goal goal) {
        return detailAssembler.detailOf(goal, workPackageRepository.findByGoalId(goal.getId()));
    }

    static GoalProgressResponse progressOf(GoalStatusCounts counts) {
        return counts == null ? GoalProgressResponse.of(0, 0, 0, 0, 0)
                : GoalProgressResponse.of(counts.open(), counts.inProgress(), counts.backlog(), counts.done(), counts.openQuestions(),
                        counts.decided());
    }

    static GoalProgressResponse progressOf(List<GoalTodoRow> rows) {
        long open = count(rows, Todo.Status.OPEN);
        long inProgress = count(rows, Todo.Status.IN_PROGRESS);
        long backlog = count(rows, Todo.Status.BACKLOG);
        long done = count(rows, Todo.Status.DONE);
        long questions = rows.stream().filter(GoalService::isActive).filter(row -> row.openQuestion() != null).count();
        long decided = rows.stream().filter(row -> row.status() != Todo.Status.ARCHIVED)
                .filter(row -> row.decisionState() == Todo.DecisionState.DECIDED).count();
        return GoalProgressResponse.of(open, inProgress, backlog, done, questions, decided);
    }

    private static long count(List<GoalTodoRow> rows, Todo.Status status) {
        return rows.stream().filter(row -> row.status() == status).count();
    }

    private static boolean isActive(GoalTodoRow row) {
        return row.status() == Todo.Status.OPEN || row.status() == Todo.Status.IN_PROGRESS
                || row.status() == Todo.Status.BACKLOG;
    }

    static GoalResponse toResponse(Goal goal, GoalProgressResponse progress, GoalGuardResponse guard,
                                   List<GoalResponse.ProjectRef> projects, GoalResponse.NextMilestone nextMilestone) {
        return new GoalResponse(goal.getId(), goal.getTitle(), goal.getDescription(), goal.getStatus(),
                goal.getTargetDate(), goal.getCreatedAt(), goal.getUpdatedAt(), progress,
                guard != null ? guard : GoalGuardResponse.NONE, projects != null ? projects : List.of(), nextMilestone);
    }

    /** {@code 2026-10-15} or an ISO timestamp; empty = no date. */
    static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return trimmed.length() == 10
                    ? LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new LocalizedArgumentException(ErrorMessage.INVALID_DATE, Map.of("value", trimmed));
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
