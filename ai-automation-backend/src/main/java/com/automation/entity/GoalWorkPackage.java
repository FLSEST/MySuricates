package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * A work package of a goal (TODO c60bf914): belongs to exactly one project of the goal
 * ({@link #projectId}), bundles TODOs of this project ({@code Todo.workPackageId}) and has an
 * optional period - {@link #startDate} and {@link #endDate} exist only together, start never after
 * the end. The order within the project is in {@link #position}. {@code projectId} is
 * null only for legacy data of a goal without projects. Until V92 they were called milestones (TODO 259f4f66).
 *
 * <p>The state is not stored but computed on read ({@link #stateOf}); only
 * {@link #completedAt} records when the package was first completed. The time
 * stays even if an open TODO is added later - the UI then shows it as
 * "wieder offen".
 */
@Entity
@Table(name = "goal_work_packages")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalWorkPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    /** Project of the package; null only for legacy data of a goal without projects (V92). */
    @Column(name = "project_id")
    private String projectId;

    @Column(nullable = false)
    private String title;

    /** Start of the period; null = no period (then no end either). */
    @Column(name = "start_date")
    private Instant startDate;

    /** End of the period - the basis for "due soon"/"overdue"; null = no period. */
    @Column(name = "end_date")
    private Instant endDate;

    /** Order within its project in the goal, from 0. */
    @Column(nullable = false)
    private int position;

    /** First detection of "all assigned TODOs done"; null = never completed. */
    @Column(name = "completed_at")
    private Instant completedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * State from the end and the assigned TODOs. {@code total}/{@code done} count without
     * ARCHIVED. REACHED (completed) needs at least one TODO - an empty package is not done.
     */
    public static GoalDueState stateOf(Instant endDate, long total, long done, Instant now) {
        return GoalDueState.of(endDate, isComplete(total, done), now);
    }

    /** Completed: at least one TODO, all (without ARCHIVED) done. */
    public static boolean isComplete(long total, long done) {
        return total > 0 && done >= total;
    }
}
