package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * An outcome that TODOs contribute to - e.g. "Open-Source-Launch" (TODO dc0566f8).
 *
 * <p>Cross-project: membership is on the TODO ({@code Todo.goalId}), not here.
 * A goal therefore knows no list of its TODOs; progress and order are computed by
 * {@code GoalService} from the TODOs.
 */
@Entity
@Table(name = "goals")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Goal {

    public enum Status { ACTIVE, DONE, ARCHIVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    /** What "done" means - Markdown. */
    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "target_date")
    private Instant targetDate;

    /**
     * Workflow of the goal guard (template {@code goal-guard}, TODO 259f4f66); null = never
     * enabled. Whether it is currently running is told by its schedule, not by this field.
     */
    @Column(name = "guard_workflow_id")
    private String guardWorkflowId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
