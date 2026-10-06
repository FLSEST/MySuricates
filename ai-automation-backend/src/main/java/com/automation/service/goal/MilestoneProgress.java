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
 * State of the milestones of a goal (TODO c60bf914) - without a query of its own, from the linked
 * work packages, whose state the goal view has already computed anyway.
 *
 * <p>With linked packages a milestone is reached when all are completed; on first
 * recognition {@link #evaluate} sets {@code reachedAt} on the (managed) entity, and if the packages
 * are later no longer all completed, it is "reopened". Without packages it is reached as long as
 * {@code reachedAt} is set by hand. Otherwise the date decides (OPEN/DUE_SOON/OVERDUE).
 * Order: by date, then title.
 */
final class MilestoneProgress {

    static final Comparator<GoalMilestoneResponse> BY_DATE = Comparator
            .comparing(GoalMilestoneResponse::dueDate, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(GoalMilestoneResponse::title, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(GoalMilestoneResponse::id, Comparator.nullsLast(Comparator.<String>naturalOrder()));

    private MilestoneProgress() {
    }

    /** @param completedPackages the IDs of the goal's work packages that are currently completed */
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

    /** The next unreached milestone by date, then title; null = none. */
    static GoalResponse.NextMilestone next(List<GoalMilestoneResponse> milestones) {
        return milestones.stream()
                .filter(m -> m.state() != GoalDueState.REACHED)
                .min(BY_DATE)
                .map(m -> new GoalResponse.NextMilestone(m.id(), m.title(), m.dueDate(), m.state()))
                .orElse(null);
    }

    /** Like {@link #next}, but from the summaries of a goal list - without loading packages. */
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
