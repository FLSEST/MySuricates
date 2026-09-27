package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.entity.GoalMilestone;
import com.automation.entity.Todo;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zustand der Meilensteine (Teilziele) eines Ziels aus seinen TODO-Zeilen (TODO 259f4f66) – ohne
 * eigene Abfrage: die Zeilen hat die Zielansicht ohnehin schon geladen.
 *
 * <p>Erkennt {@link #evaluate} einen Meilenstein zum ersten Mal als erreicht, setzt es
 * {@code reachedAt} an der (verwalteten) Entity; die umgebende Schreib-Transaktion speichert es.
 *
 * <p>Reihenfolge (TODO c60bf914): nach der Position des Projekts im Ziel (Teilziele ohne
 * zugeordnetes Projekt zuletzt), dann nach der Position im Projekt; bei Gleichstand nach Datum
 * (ohne Datum zuletzt) und Anlage.
 */
final class MilestoneProgress {

    private static final Set<Todo.Status> ACTIVE = Set.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS, Todo.Status.BACKLOG);

    private MilestoneProgress() {
    }

    static List<GoalMilestoneResponse> evaluate(List<GoalMilestone> milestones, List<GoalTodoRow> rows, Instant now) {
        return evaluate(milestones, rows, Map.of(), now);
    }

    /** @param projectPositions Projekt-ID → Position im Ziel; fehlende Projekte stehen hinten */
    static List<GoalMilestoneResponse> evaluate(List<GoalMilestone> milestones, List<GoalTodoRow> rows,
                                                Map<String, Integer> projectPositions, Instant now) {
        if (milestones == null || milestones.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> counts = new HashMap<>();
        for (GoalTodoRow row : rows) {
            if (row.milestoneId() == null || row.status() == Todo.Status.ARCHIVED) {
                continue;
            }
            long[] c = counts.computeIfAbsent(row.milestoneId(), id -> new long[3]);
            c[0]++;
            if (row.status() == Todo.Status.DONE) c[1]++;
            if (ACTIVE.contains(row.status()) && row.openQuestion() != null) c[2]++;
        }
        return milestones.stream()
                .sorted(order(projectPositions))
                .map(m -> {
                    long[] c = counts.getOrDefault(m.getId(), new long[3]);
                    return toResponse(m, c[0], c[1], c[2], now);
                })
                .toList();
    }

    static GoalMilestoneResponse toResponse(GoalMilestone m, long total, long done, Instant now) {
        return toResponse(m, total, done, 0, now);
    }

    /**
     * Zustand eines Meilensteins; beim ersten Erkennen „erreicht“ wird {@code reachedAt} gesetzt und
     * bleibt danach, auch wenn der Meilenstein wieder offen ist ({@code reopened}). Ohne Datum ist
     * er nur OPEN oder REACHED.
     */
    static GoalMilestoneResponse toResponse(GoalMilestone m, long total, long done, long openQuestions, Instant now) {
        GoalMilestone.State state = GoalMilestone.stateOf(m.getDueDate(), total, done, now);
        if (state == GoalMilestone.State.REACHED && m.getReachedAt() == null) {
            m.setReachedAt(now);
        }
        boolean reopened = m.getReachedAt() != null && state != GoalMilestone.State.REACHED;
        return new GoalMilestoneResponse(m.getId(), m.getGoalId(), m.getTitle(), m.getDueDate(), state, total, done,
                m.getReachedAt(), reopened, m.getCreatedAt(), m.getUpdatedAt(), m.getProjectId(), m.getPosition(),
                openQuestions);
    }

    static Comparator<GoalMilestone> order(Map<String, Integer> projectPositions) {
        return Comparator
                .comparing((GoalMilestone m) -> m.getProjectId() == null ? null : projectPositions.get(m.getProjectId()),
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(GoalMilestone::getProjectId, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparingInt(GoalMilestone::getPosition)
                .thenComparing(GoalMilestone::getDueDate, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(GoalMilestone::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(GoalMilestone::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));
    }
}
