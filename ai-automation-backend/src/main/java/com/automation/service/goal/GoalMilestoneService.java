package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithMilestones;
import com.automation.dto.projection.MilestoneTodoCounts;
import com.automation.dto.request.CreateGoalMilestoneRequest;
import com.automation.dto.request.UpdateGoalMilestoneRequest;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.exception.ResourceNotFoundException;
import com.automation.dto.projection.GoalProjectRow;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Meilensteine eines Ziels – in der Oberflaeche „Teilziele“ – anlegen, aendern, ordnen, loeschen und
 * mit Zustand lesen (TODO 259f4f66). Zugeordnet werden TODOs ueber {@code PATCH /todos/{id}/planning}
 * ({@link TodoPlanningService}).
 *
 * <p>Seit TODO c60bf914 gehoert jedes Teilziel genau einem Projekt des Ziels; das Datum ist optional,
 * die Reihenfolge innerhalb des Projekts steht in {@code position}.
 *
 * <p>Lesen ist schreibend transaktional: erkennt es einen Meilenstein zum ersten Mal als erreicht,
 * speichert es {@code reachedAt} ({@link MilestoneProgress}).
 */
@Service
@RequiredArgsConstructor
public class GoalMilestoneService {

    static final int MAX_TITLE = 200;

    private final GoalRepository goalRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final TodoRepository todoRepository;
    private final GoalProjectRepository goalProjectRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    /**
     * Nach Projekt-Position, dann Position im Projekt; Ziel samt Meilensteinen in einer Abfrage, die
     * Zaehler aus den TODO-Zeilen.
     */
    @Transactional
    public List<GoalMilestoneResponse> list(String goalId) {
        return snapshot(goalId).milestones();
    }

    /**
     * Neues Teilziel ans Ende seines Projekts. Ohne {@code projectId} nur, wenn das Ziel genau ein
     * Projekt hat; ein genanntes Projekt, das dem Ziel noch fehlt, kommt dazu. Datum optional.
     */
    @Transactional
    public GoalMilestoneResponse create(String goalId, CreateGoalMilestoneRequest request) {
        String title = title(request.title());
        Instant due = parseDueDate(request.dueDate());
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        String projectId = blankToNull(request.projectId());
        if (projectId == null) {
            List<GoalProjectRow> projects = goalProjectRepository.findRows(goalId);
            if (projects.size() != 1) {
                throw new IllegalArgumentException("Ein Teilziel braucht ein Projekt (projectId) – das Ziel hat "
                        + projects.size() + " Projekte");
            }
            projectId = projects.get(0).projectId();
        } else {
            linkProject(goalId, projectId);
        }
        GoalMilestone saved = milestoneRepository.save(GoalMilestone.builder()
                .goalId(goalId).projectId(projectId).title(title).dueDate(due)
                .position(nextPosition(goalId, projectId)).build());
        return MilestoneProgress.toResponse(saved, 0, 0, clock.instant());
    }

    /**
     * {@code dueDate = ""} entfernt das Datum. Ein Projektwechsel geht nur, solange dem Teilziel
     * keine TODOs zugeordnet sind (sonst 400); es rueckt ans Ende des neuen Projekts.
     */
    @Transactional
    public GoalMilestoneResponse update(String goalId, String milestoneId, UpdateGoalMilestoneRequest request) {
        GoalMilestone milestone = find(goalId, milestoneId);
        if (request.title() != null) {
            milestone.setTitle(title(request.title()));
        }
        if (request.dueDate() != null) {
            milestone.setDueDate(parseDueDate(request.dueDate()));
        }
        if (request.projectId() != null) {
            String projectId = blankToNull(request.projectId());
            if (projectId == null) {
                throw new IllegalArgumentException("Ein Teilziel braucht ein Projekt");
            }
            if (!projectId.equals(milestone.getProjectId())) {
                long todos = todoRepository.countByMilestoneId(milestone.getId());
                if (todos > 0) {
                    throw new IllegalArgumentException("Das Teilziel hat " + todos
                            + " TODO(s) – das Projekt laesst sich nur bei einem leeren Teilziel wechseln");
                }
                linkProject(goalId, projectId);
                milestone.setPosition(nextPosition(goalId, projectId));
                milestone.setProjectId(projectId);
            }
        }
        GoalMilestone saved = milestoneRepository.save(milestone);
        MilestoneTodoCounts counts = todoRepository.countByMilestone(List.of(saved.getId())).stream()
                .findFirst().orElse(new MilestoneTodoCounts(saved.getId(), 0, 0));
        return MilestoneProgress.toResponse(saved, counts.total(), counts.done(), counts.openQuestions(), clock.instant());
    }

    /**
     * Neue Reihenfolge der Teilziele EINES Projekts: die genannten in dieser Folge vorn, nicht
     * genannte desselben Projekts in ihrer bisherigen Folge dahinter. Teilziele verschiedener Projekte
     * in einer Liste sind ein 400, ein fremdes oder unbekanntes ein 404. Antwort: alle Teilziele des Ziels.
     */
    @Transactional
    public List<GoalMilestoneResponse> reorder(String goalId, List<String> milestoneIds) {
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        Map<String, GoalMilestone> byId = milestoneRepository.findByGoalId(goalId).stream()
                .collect(Collectors.toMap(GoalMilestone::getId, Function.identity()));
        List<GoalMilestone> ordered = new ArrayList<>();
        for (String id : new LinkedHashSet<>(milestoneIds)) {
            GoalMilestone milestone = byId.get(id);
            if (milestone == null) {
                throw new ResourceNotFoundException("GoalMilestone", id);
            }
            ordered.add(milestone);
        }
        String projectId = ordered.get(0).getProjectId();
        if (ordered.stream().anyMatch(m -> !Objects.equals(m.getProjectId(), projectId))) {
            throw new IllegalArgumentException("Umsortieren geht nur innerhalb eines Projekts");
        }
        byId.values().stream()
                .filter(m -> Objects.equals(m.getProjectId(), projectId) && !ordered.contains(m))
                .sorted(MilestoneProgress.order(Map.of()))
                .forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
        milestoneRepository.saveAll(ordered);
        return list(goalId);
    }

    /** Loescht den Meilenstein; seine TODOs bleiben im Ziel und verlieren nur die Zuordnung. */
    @Transactional
    public void delete(String goalId, String milestoneId) {
        GoalMilestone milestone = find(goalId, milestoneId);
        todoRepository.detachFromMilestone(milestone.getId(), clock.instant());
        milestoneRepository.delete(milestone);
    }

    /** Ziel, Zeilen und Meilensteine fuer die Kontextvariable {@code goal_status}; unbekannt = 404. */
    @Transactional
    public GoalSnapshot snapshot(String goalId) {
        GoalWithMilestones loaded = goalRepository.findWithMilestones(goalId)
                .orElseThrow(() -> new ResourceNotFoundException("Goal", goalId));
        Goal goal = loaded.goal();
        List<GoalTodoRow> rows = todoRepository.findGoalRows(goal.getId());
        List<GoalMilestoneResponse> milestones = MilestoneProgress.evaluate(loaded.milestones(), rows,
                loaded.projectPositions(), clock.instant());
        return new GoalSnapshot(goal, rows, milestones, GoalService.progressOf(rows));
    }

    /** Ein Meilenstein eines anderen Ziels ist hier so unbekannt wie ein fehlender: 404. */
    private GoalMilestone find(String goalId, String milestoneId) {
        return milestoneRepository.findById(milestoneId)
                .filter(m -> m.getGoalId().equals(goalId))
                .orElseThrow(() -> new ResourceNotFoundException("GoalMilestone", milestoneId));
    }

    private void linkProject(String goalId, String projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        goalProjectRepository.linkIfAbsent(goalId, projectId);
    }

    private int nextPosition(String goalId, String projectId) {
        Integer max = milestoneRepository.maxPosition(goalId, projectId);
        return max == null ? 0 : max + 1;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String title(String raw) {
        String title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Ein Teilziel braucht einen Titel");
        }
        if (title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("Der Titel ist laenger als " + MAX_TITLE + " Zeichen");
        }
        return title;
    }

    /**
     * Ein ISO-Zeitpunkt wie gesendet; ein reines Datum ({@code 2026-10-15}) heisst „bis Ende dieses
     * Tages“ (23:59:59 UTC) – ueberfaellig wird der Meilenstein erst am Tag danach. Leer = kein Datum.
     */
    static Instant parseDueDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return trimmed.length() == 10
                    ? LocalDate.parse(trimmed).atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC)
                    : Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Ungueltiges Datum: " + trimmed);
        }
    }
}
