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
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalRepository;
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
import java.util.List;

/**
 * Meilensteine eines Ziels anlegen, aendern, loeschen und mit Zustand lesen (TODO 259f4f66).
 * Zugeordnet werden TODOs ueber {@code PATCH /todos/{id}/planning} ({@link TodoPlanningService}).
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
    private final Clock clock;

    /** Nach Datum; Ziel samt Meilensteinen in einer Abfrage, die Zaehler aus den TODO-Zeilen. */
    @Transactional
    public List<GoalMilestoneResponse> list(String goalId) {
        return snapshot(goalId).milestones();
    }

    @Transactional
    public GoalMilestoneResponse create(String goalId, CreateGoalMilestoneRequest request) {
        String title = title(request.title());
        Instant due = parseDueDate(request.dueDate());
        if (due == null) {
            throw new IllegalArgumentException("Ein Meilenstein braucht ein Datum");
        }
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        GoalMilestone saved = milestoneRepository.save(GoalMilestone.builder()
                .goalId(goalId).title(title).dueDate(due).build());
        return MilestoneProgress.toResponse(saved, 0, 0, clock.instant());
    }

    @Transactional
    public GoalMilestoneResponse update(String goalId, String milestoneId, UpdateGoalMilestoneRequest request) {
        GoalMilestone milestone = find(goalId, milestoneId);
        if (request.title() != null) {
            milestone.setTitle(title(request.title()));
        }
        if (request.dueDate() != null) {
            Instant due = parseDueDate(request.dueDate());
            if (due == null) {
                throw new IllegalArgumentException("Ein Meilenstein braucht ein Datum");
            }
            milestone.setDueDate(due);
        }
        GoalMilestone saved = milestoneRepository.save(milestone);
        MilestoneTodoCounts counts = todoRepository.countByMilestone(List.of(saved.getId())).stream()
                .findFirst().orElse(new MilestoneTodoCounts(saved.getId(), 0, 0));
        return MilestoneProgress.toResponse(saved, counts.total(), counts.done(), clock.instant());
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
        List<GoalMilestoneResponse> milestones = MilestoneProgress.evaluate(loaded.milestones(), rows, clock.instant());
        return new GoalSnapshot(goal, rows, milestones, GoalService.progressOf(rows));
    }

    /** Ein Meilenstein eines anderen Ziels ist hier so unbekannt wie ein fehlender: 404. */
    private GoalMilestone find(String goalId, String milestoneId) {
        return milestoneRepository.findById(milestoneId)
                .filter(m -> m.getGoalId().equals(goalId))
                .orElseThrow(() -> new ResourceNotFoundException("GoalMilestone", milestoneId));
    }

    private static String title(String raw) {
        String title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Ein Meilenstein braucht einen Titel");
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
