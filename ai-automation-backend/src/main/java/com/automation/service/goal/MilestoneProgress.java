package com.automation.service.goal;

import com.automation.dto.projection.GoalMilestoneLinks;
import com.automation.dto.projection.GoalMilestoneSummaryRow;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalMilestone;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Zustand der Meilensteine eines Ziels (TODO c60bf914) – ohne eigene Abfrage, aus den verknuepften
 * Arbeitspaketen, deren Zustand die Zielansicht ohnehin schon berechnet hat.
 *
 * <p>Mit verknuepften Paketen ist ein Meilenstein erreicht, wenn alle abgeschlossen sind; beim ersten
 * Erkennen setzt {@link #evaluate} {@code reachedAt} an der (verwalteten) Entity, und sind die Pakete
 * spaeter nicht mehr alle abgeschlossen, heisst er „wieder offen“. Ohne Pakete ist er erreicht, solange
 * {@code reachedAt} von Hand gesetzt ist. Sonst entscheidet das Datum (OPEN/DUE_SOON/OVERDUE).
 * Reihenfolge: nach Datum, dann Titel.
 */
final class MilestoneProgress {

    static final Comparator<GoalMilestoneResponse> BY_DATE = Comparator
            .comparing(GoalMilestoneResponse::dueDate, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(GoalMilestoneResponse::title, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(GoalMilestoneResponse::id, Comparator.nullsLast(Comparator.<String>naturalOrder()));

    private MilestoneProgress() {
    }

    /** @param completedPackages die IDs der Arbeitspakete des Ziels, die gerade abgeschlossen sind */
    static List<GoalMilestoneResponse> evaluate(List<GoalMilestoneLinks> milestones, Set<String> completedPackages,
                                                Instant now) {
        if (milestones == null || milestones.isEmpty()) {
            return List.of();
        }
        return milestones.stream()
                .map(links -> toResponse(links.milestone(), links.workPackageIds(), completedPackages, now))
                .sorted(BY_DATE)
                .toList();
    }

    static GoalMilestoneResponse toResponse(GoalMilestone m, List<String> workPackageIds, Collection<String> completedPackages,
                                            Instant now) {
        long total = workPackageIds.size();
        long done = workPackageIds.stream().filter(completedPackages::contains).count();
        boolean reached;
        boolean reopened = false;
        if (total > 0) {
            reached = done >= total;
            if (reached && m.getReachedAt() == null) {
                m.setReachedAt(now);
            }
            reopened = !reached && m.getReachedAt() != null;
        } else {
            reached = m.getReachedAt() != null;
        }
        return new GoalMilestoneResponse(m.getId(), m.getGoalId(), m.getTitle(), m.getDueDate(),
                GoalDueState.of(m.getDueDate(), reached, now), List.copyOf(workPackageIds), total, done,
                m.getReachedAt(), reopened, m.getCreatedAt(), m.getUpdatedAt());
    }

    /** Der naechste nicht erreichte Meilenstein nach Datum, dann Titel; null = keiner. */
    static GoalResponse.NextMilestone next(List<GoalMilestoneResponse> milestones) {
        return milestones.stream()
                .filter(m -> m.state() != GoalDueState.REACHED)
                .min(BY_DATE)
                .map(m -> new GoalResponse.NextMilestone(m.id(), m.title(), m.dueDate(), m.state()))
                .orElse(null);
    }

    /** Wie {@link #next}, aber aus den Zusammenfassungen einer Zielliste – ohne Pakete zu laden. */
    static GoalResponse.NextMilestone nextOf(Collection<GoalMilestoneSummaryRow> rows, Instant now) {
        return rows.stream()
                .filter(row -> !row.reached())
                .min(Comparator.comparing(GoalMilestoneSummaryRow::dueDate, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                        .thenComparing(GoalMilestoneSummaryRow::title, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                        .thenComparing(GoalMilestoneSummaryRow::id))
                .map(row -> new GoalResponse.NextMilestone(row.id(), row.title(), row.dueDate(), row.state(now)))
                .orElse(null);
    }
}
