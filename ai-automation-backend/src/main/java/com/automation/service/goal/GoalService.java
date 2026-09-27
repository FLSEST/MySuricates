package com.automation.service.goal;

import com.automation.dto.projection.GoalProjectRow;
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
import com.automation.entity.GoalProject;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
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
import java.util.LinkedHashSet;
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
 * <p>Alles liest schlanke Projektionen ({@link GoalTodoRow}, {@link GoalStatusCounts},
 * {@link GoalProjectRow}) – ein Ziel mit 40 TODOs soll nicht 40 Beschreibungen durch die einzige
 * SQLite-Verbindung ziehen. Die Projekte eines Ziels (TODO c60bf914) pflegt {@link GoalProjectService}.
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
    private final GoalProjectRepository goalProjectRepository;
    private final Clock clock;

    /**
     * Ohne Statusfilter: aktive und erledigte Ziele, archivierte nur auf Nachfrage. Mit
     * {@code projectId} nur die Ziele, denen dieses Projekt zugeordnet ist (TODO c60bf914).
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
        Map<String, GoalGuardResponse> guards = guardLookup.byGoal(goals);
        return goals.stream()
                .map(goal -> toResponse(goal, progressOf(counts.get(goal.getId())), guards.get(goal.getId()),
                        projects.getOrDefault(goal.getId(), List.of())))
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

    /** Mit {@code projectIds} gleich samt Projekten in dieser Reihenfolge; ein unbekanntes Projekt ist ein 404. */
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
        return toResponse(goal, GoalProgressResponse.of(0, 0, 0, 0, 0), GoalGuardResponse.NONE, projects);
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

    /**
     * Ziel, TODO-Zeilen und Zielprojekte – drei Statements; Projektnamen nur fuer TODOs aus Projekten,
     * die dem Ziel nicht zugeordnet sind (Altdaten), in einer weiteren Abfrage.
     */
    GoalDetailResponse detailOf(Goal goal, List<GoalMilestone> milestones) {
        List<GoalTodoRow> rows = todoRepository.findGoalRows(goal.getId()).stream().sorted(WORK_ORDER).toList();
        List<GoalProjectRow> projects = goalProjectRepository.findRows(goal.getId());
        Map<String, String> projectNames = projectNames(rows, projects);
        Map<String, Integer> positions = new HashMap<>();
        projects.forEach(p -> positions.put(p.projectId(), p.position()));
        List<GoalTodoItem> items = rows.stream().map(row -> toItem(row, projectNames.get(row.projectId()))).toList();
        GoalResponse response = toResponse(goal, progressOf(rows), guardLookup.of(goal), GoalProjectSummaries.refs(projects));
        return new GoalDetailResponse(response, items, GoalProjectSummaries.byProject(rows, projectNames),
                MilestoneProgress.evaluate(milestones, rows, positions, clock.instant()),
                GoalProjectSummaries.projects(projects, rows));
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

    /** Projekt-ID → Name; eine HashMap, weil TODOs ohne Projekt mit {@code null} nachschlagen. */
    private Map<String, String> projectNames(List<GoalTodoRow> rows, List<GoalProjectRow> projects) {
        Map<String, String> names = new HashMap<>();
        projects.forEach(p -> names.put(p.projectId(), p.projectName()));
        Set<String> ids = rows.stream().map(GoalTodoRow::projectId).filter(Objects::nonNull)
                .filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
        if (!ids.isEmpty()) {
            projectRepository.findAllById(ids).forEach(project -> names.put(project.getId(), project.getName()));
        }
        return names;
    }

    private static GoalTodoItem toItem(GoalTodoRow row, String projectName) {
        return new GoalTodoItem(row.id(), row.title(), row.status(), row.priority(), row.projectId(), projectName,
                TodoMapper.jsonToList(row.tags()), row.goalRank(), row.complexity(), row.suggestedEffort(),
                row.ratingNote(), row.ratedBy(), row.ratedAt(), row.openQuestion(), row.updatedAt(),
                row.dueDate(), row.milestoneId());
    }

    static GoalResponse toResponse(Goal goal, GoalProgressResponse progress, GoalGuardResponse guard,
                                   List<GoalResponse.ProjectRef> projects) {
        return new GoalResponse(goal.getId(), goal.getTitle(), goal.getDescription(), goal.getStatus(),
                goal.getTargetDate(), goal.getCreatedAt(), goal.getUpdatedAt(), progress,
                guard != null ? guard : GoalGuardResponse.NONE, projects != null ? projects : List.of());
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
