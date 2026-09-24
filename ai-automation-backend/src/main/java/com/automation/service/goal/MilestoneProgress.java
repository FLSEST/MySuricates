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

/**
 * Zustand der Meilensteine eines Ziels aus seinen TODO-Zeilen (TODO 259f4f66) – ohne eigene
 * Abfrage: die Zeilen hat die Zielansicht ohnehin schon geladen.
 *
 * <p>Erkennt {@link #evaluate} einen Meilenstein zum ersten Mal als erreicht, setzt es
 * {@code reachedAt} an der (verwalteten) Entity; die umgebende Schreib-Transaktion speichert es.
 */
final class MilestoneProgress {

    private MilestoneProgress() {
    }

    static List<GoalMilestoneResponse> evaluate(List<GoalMilestone> milestones, List<GoalTodoRow> rows, Instant now) {
        if (milestones == null || milestones.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> counts = new HashMap<>();
        for (GoalTodoRow row : rows) {
            if (row.milestoneId() == null || row.status() == Todo.Status.ARCHIVED) {
                continue;
            }
            long[] c = counts.computeIfAbsent(row.milestoneId(), id -> new long[2]);
            c[0]++;
            if (row.status() == Todo.Status.DONE) c[1]++;
        }
        return milestones.stream()
                .sorted(BY_DATE)
                .map(m -> {
                    long[] c = counts.getOrDefault(m.getId(), new long[2]);
                    return toResponse(m, c[0], c[1], now);
                })
                .toList();
    }

    /**
     * Zustand eines Meilensteins; beim ersten Erkennen „erreicht“ wird {@code reachedAt} gesetzt und
     * bleibt danach, auch wenn der Meilenstein wieder offen ist ({@code reopened}).
     */
    static GoalMilestoneResponse toResponse(GoalMilestone m, long total, long done, Instant now) {
        GoalMilestone.State state = GoalMilestone.stateOf(m.getDueDate(), total, done, now);
        if (state == GoalMilestone.State.REACHED && m.getReachedAt() == null) {
            m.setReachedAt(now);
        }
        boolean reopened = m.getReachedAt() != null && state != GoalMilestone.State.REACHED;
        return new GoalMilestoneResponse(m.getId(), m.getGoalId(), m.getTitle(), m.getDueDate(), state, total, done,
                m.getReachedAt(), reopened, m.getCreatedAt(), m.getUpdatedAt());
    }

    private static final Comparator<GoalMilestone> BY_DATE = Comparator
            .comparing(GoalMilestone::getDueDate, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(GoalMilestone::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()));
}
