package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zustand der Arbeitspakete eines Ziels aus seinen TODO-Zeilen (TODO 259f4f66, c60bf914) – ohne
 * eigene Abfrage: die Zeilen hat die Zielansicht ohnehin schon geladen.
 *
 * <p>Erkennt {@link #evaluate} ein Paket zum ersten Mal als abgeschlossen, setzt es
 * {@code completedAt} an der (verwalteten) Entity; die umgebende Schreib-Transaktion speichert es.
 *
 * <p>Reihenfolge: nach der Position des Projekts im Ziel (Pakete ohne zugeordnetes Projekt zuletzt),
 * dann nach der Position im Projekt; bei Gleichstand nach Ende (ohne Zeitraum zuletzt) und Anlage.
 */
final class WorkPackageProgress {

    private static final Set<Todo.Status> ACTIVE = Set.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS, Todo.Status.BACKLOG);

    private WorkPackageProgress() {
    }

    static List<GoalWorkPackageResponse> evaluate(List<GoalWorkPackage> workPackages, List<GoalTodoRow> rows, Instant now) {
        return evaluate(workPackages, rows, Map.of(), now);
    }

    /** @param projectPositions Projekt-ID → Position im Ziel; fehlende Projekte stehen hinten */
    static List<GoalWorkPackageResponse> evaluate(List<GoalWorkPackage> workPackages, List<GoalTodoRow> rows,
                                                  Map<String, Integer> projectPositions, Instant now) {
        if (workPackages == null || workPackages.isEmpty()) {
            return List.of();
        }
        Map<String, long[]> counts = new HashMap<>();
        for (GoalTodoRow row : rows) {
            if (row.workPackageId() == null || row.status() == Todo.Status.ARCHIVED) {
                continue;
            }
            long[] c = counts.computeIfAbsent(row.workPackageId(), id -> new long[4]);
            c[0]++;
            if (row.status() == Todo.Status.DONE) c[1]++;
            if (ACTIVE.contains(row.status()) && row.openQuestion() != null) c[2]++;
            if (row.decisionState() == Todo.DecisionState.DECIDED) c[3]++;
        }
        return workPackages.stream()
                .sorted(order(projectPositions))
                .map(p -> {
                    long[] c = counts.getOrDefault(p.getId(), new long[4]);
                    return toResponse(p, c[0], c[1], c[2], c[3], now);
                })
                .toList();
    }

    /**
     * Zustand eines Pakets; beim ersten Erkennen „abgeschlossen“ wird {@code completedAt} gesetzt und
     * bleibt danach, auch wenn das Paket wieder offen ist ({@code reopened}). Ohne Zeitraum ist es nur
     * OPEN oder REACHED.
     */
    static GoalWorkPackageResponse toResponse(GoalWorkPackage p, long total, long done, long openQuestions, Instant now) {
        return toResponse(p, total, done, openQuestions, 0, now);
    }

    /** @param decided nicht archivierte TODOs mit Entscheidung und ohne offene Frage (TODO 68a5e24e) */
    static GoalWorkPackageResponse toResponse(GoalWorkPackage p, long total, long done, long openQuestions, long decided,
                                              Instant now) {
        GoalDueState state = GoalWorkPackage.stateOf(p.getEndDate(), total, done, now);
        if (state == GoalDueState.REACHED && p.getCompletedAt() == null) {
            p.setCompletedAt(now);
        }
        boolean reopened = p.getCompletedAt() != null && state != GoalDueState.REACHED;
        return new GoalWorkPackageResponse(p.getId(), p.getGoalId(), p.getProjectId(), p.getTitle(), p.getStartDate(),
                p.getEndDate(), p.getPosition(), state, total, done, openQuestions, p.getCompletedAt(), reopened,
                p.getCreatedAt(), p.getUpdatedAt(), decided);
    }

    static Comparator<GoalWorkPackage> order(Map<String, Integer> projectPositions) {
        return Comparator
                .comparing((GoalWorkPackage p) -> p.getProjectId() == null ? null : projectPositions.get(p.getProjectId()),
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(GoalWorkPackage::getProjectId, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparingInt(GoalWorkPackage::getPosition)
                .thenComparing(GoalWorkPackage::getEndDate, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(GoalWorkPackage::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(GoalWorkPackage::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));
    }
}
