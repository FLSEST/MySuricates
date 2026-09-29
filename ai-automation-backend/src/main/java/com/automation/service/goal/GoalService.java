package com.automation.service.goal;

import com.automation.dto.projection.GoalStatusCounts;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithMilestones;
import com.automation.dto.request.CreateGoalRequest;
import com.automation.dto.request.UpdateGoalRequest;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.dto.response.GoalProgressResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.dto.response.GoalTodoItem;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ziele anlegen, aendern, loeschen und lesen – samt Fortschritt aus den eingeordneten TODOs
 * (TODO dc0566f8). Zuordnen und Ordnen der TODOs liegt in {@link GoalMembershipService}, die
 * Einschaetzung eines einzelnen TODOs in {@link TodoPlanningService}.
 *
 * <p>Alles liest schlanke Projektionen ({@link GoalTodoRow}, {@link GoalStatusCounts}) – ein Ziel
 * mit 40 TODOs soll nicht 40 Beschreibungen durch die einzige SQLite-Verbindung ziehen.
 */
@Service
@RequiredArgsConstructor
public class GoalService {

    /** Arbeitsreihenfolge innerhalb gleichen Rangs: dringend zuerst. */
    private static final List<Todo.Priority> PRIORITY_ORDER = List.of(
            Todo.Priority.CRITICAL, Todo.Priority.HIGH, Todo.Priority.MEDIUM, Todo.Priority.LOW);

    static final Comparator<GoalTodoRow> WORK_ORDER = workOrder(GoalTodoRow::goalRank, GoalTodoRow::priority, GoalTodoRow::createdAt);

    /** Arbeitsreihenfolge fuer jede Zeilenart: Rang (ohne Rang zuletzt), dann Prioritaet, dann Alter. */
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
    private final GoalMilestoneRepository milestoneRepository;
    private final Clock clock;

    /** Ohne Statusfilter: aktive und erledigte Ziele, archivierte nur auf Nachfrage. */
    @Transactional(readOnly = true)
    public List<GoalResponse> list(List<Goal.Status> statuses) {
        List<Goal.Status> wanted = statuses == null || statuses.isEmpty()
                ? List.of(Goal.Status.ACTIVE, Goal.Status.DONE) : statuses;
        List<Goal> goals = goalRepository.findByStatusInOrderByCreatedAtDesc(wanted);
        if (goals.isEmpty()) {
            return List.of();
        }
        Map<String, GoalStatusCounts> counts = todoRepository.countByGoal(goals.stream().map(Goal::getId).toList())
                .stream().collect(Collectors.toMap(GoalStatusCounts::goalId, Function.identity()));
        Map<String, GoalGuardResponse> guards = guardLookup.byGoal(goals);
        return goals.stream()
                .map(goal -> toResponse(goal, progressOf(counts.get(goal.getId())), guards.get(goal.getId())))
                .toList();
    }

    /** Ein Ziel mit Fortschritt und Waechter, ohne TODO-Liste (Antwort von {@code POST /goals/{id}/guard}). */
    @Transactional(readOnly = true)
    public GoalResponse get(String id) {
        return detailOf(find(id)).goal();
    }

    /**
     * Schreibend, weil das Lesen einen Meilenstein zum ersten Mal als erreicht erkennen kann – dann
     * wird {@code reachedAt} gespeichert (TODO 259f4f66). Sonst schreibt es nichts.
     */
    @Transactional
    public GoalDetailResponse detail(String id) {
        GoalWithMilestones loaded = goalRepository.findWithMilestones(id)
                .orElseThrow(() -> new ResourceNotFoundException("Goal", id));
        return detailOf(loaded.goal(), loaded.milestones());
    }

    @Transactional
    public GoalResponse create(CreateGoalRequest request) {
        Goal goal = goalRepository.save(Goal.builder()
                .title(request.title().trim())
                .description(blankToNull(request.description()))
                .targetDate(parseDate(request.targetDate()))
                .build());
        return toResponse(goal, GoalProgressResponse.of(0, 0, 0, 0, 0), GoalGuardResponse.NONE);
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

    /** Loest die TODOs vom Ziel und loescht es; die TODOs bleiben erhalten. */
    @Transactional
    public void delete(String id) {
        Goal goal = find(id);
        todoRepository.detachFromGoal(goal.getId(), Instant.now());
        goalRepository.deleteById(goal.getId());
    }

    Goal find(String id) {
        return goalRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Goal", id));
    }

    GoalDetailResponse detailOf(Goal goal) {
        return detailOf(goal, milestoneRepository.findByGoalId(goal.getId()));
    }

    GoalDetailResponse detailOf(Goal goal, List<GoalMilestone> milestones) {
        List<GoalTodoRow> rows = todoRepository.findGoalRows(goal.getId()).stream().sorted(WORK_ORDER).toList();
        Map<String, String> projectNames = projectNames(rows);
        List<GoalTodoItem> items = rows.stream().map(row -> toItem(row, projectNames.get(row.projectId()))).toList();
        return new GoalDetailResponse(toResponse(goal, progressOf(rows), guardLookup.of(goal)), items,
                byProject(rows, projectNames), MilestoneProgress.evaluate(milestones, rows, clock.instant()));
    }

    static GoalProgressResponse progressOf(GoalStatusCounts counts) {
        return counts == null ? GoalProgressResponse.of(0, 0, 0, 0, 0)
                : GoalProgressResponse.of(counts.open(), counts.inProgress(), counts.backlog(), counts.done(), counts.openQuestions());
    }

    static GoalProgressResponse progressOf(List<GoalTodoRow> rows) {
        long open = count(rows, Todo.Status.OPEN);
        long inProgress = count(rows, Todo.Status.IN_PROGRESS);
        long backlog = count(rows, Todo.Status.BACKLOG);
        long done = count(rows, Todo.Status.DONE);
        long questions = rows.stream().filter(GoalService::isActive).filter(row -> row.openQuestion() != null).count();
        return GoalProgressResponse.of(open, inProgress, backlog, done, questions);
    }

    private static long count(List<GoalTodoRow> rows, Todo.Status status) {
        return rows.stream().filter(row -> row.status() == status).count();
    }

    private static boolean isActive(GoalTodoRow row) {
        return row.status() == Todo.Status.OPEN || row.status() == Todo.Status.IN_PROGRESS
                || row.status() == Todo.Status.BACKLOG;
    }

    private static List<GoalDetailResponse.ProjectProgress> byProject(List<GoalTodoRow> rows, Map<String, String> names) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (GoalTodoRow row : rows) {
            if (row.status() == Todo.Status.ARCHIVED) {
                continue;
            }
            long[] t = totals.computeIfAbsent(Objects.toString(row.projectId(), ""), k -> new long[2]);
            t[0]++;
            if (row.status() == Todo.Status.DONE) t[1]++;
        }
        return totals.entrySet().stream()
                .map(e -> {
                    String projectId = e.getKey().isEmpty() ? null : e.getKey();
                    return new GoalDetailResponse.ProjectProgress(projectId, names.get(projectId), e.getValue()[0], e.getValue()[1]);
                })
                .toList();
    }

    /** Projekt-ID → Name; eine HashMap, weil TODOs ohne Projekt mit {@code null} nachschlagen. */
    private Map<String, String> projectNames(List<GoalTodoRow> rows) {
        Set<String> ids = rows.stream().map(GoalTodoRow::projectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            // Nur ID und Name – findAllById kostete je Projekt ein select containers (TODO 08224ed6)
            projectRepository.findIdAndNameByIdIn(ids).forEach(row -> names.put((String) row[0], (String) row[1]));
        }
        return names;
    }

    private static GoalTodoItem toItem(GoalTodoRow row, String projectName) {
        return new GoalTodoItem(row.id(), row.title(), row.status(), row.priority(), row.projectId(), projectName,
                TodoMapper.jsonToList(row.tags()), row.goalRank(), row.complexity(), row.suggestedEffort(),
                row.ratingNote(), row.ratedBy(), row.ratedAt(), row.openQuestion(), row.updatedAt(),
                row.dueDate(), row.milestoneId());
    }

    static GoalResponse toResponse(Goal goal, GoalProgressResponse progress, GoalGuardResponse guard) {
        return new GoalResponse(goal.getId(), goal.getTitle(), goal.getDescription(), goal.getStatus(),
                goal.getTargetDate(), goal.getCreatedAt(), goal.getUpdatedAt(), progress,
                guard != null ? guard : GoalGuardResponse.NONE);
    }

    /** {@code 2026-10-15} oder ein ISO-Zeitpunkt; leer = kein Datum. */
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
            throw new IllegalArgumentException("Ungueltiges Datum: " + trimmed);
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
