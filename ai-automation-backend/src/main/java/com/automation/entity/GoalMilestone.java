package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * A milestone of a goal (TODO c60bf914): a deadline for the whole goal - not a project, always
 * with a date. Optionally linked to work packages ({@link GoalMilestoneWorkPackage}): then it is
 * reached when all linked packages are completed, and {@link #reachedAt} records the first
 * time. Without linked packages it is marked as reached by hand. The state is
 * computed on read ({@code MilestoneProgress}).
 *
 * <p>Not to be confused with the milestones until V92 - those have since been called work packages
 * ({@link GoalWorkPackage}).
 */
@Entity
@Table(name = "goal_milestones")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    @Column(nullable = false)
    private String title;

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    /** Reached since; with packages the first detection, without packages set by hand. null = not reached. */
    @Column(name = "reached_at")
    private Instant reachedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
