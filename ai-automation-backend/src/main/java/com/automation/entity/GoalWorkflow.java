package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * A workflow attached to a goal or to one of its work packages (TODO 52bc1e8c): it
 * works on it ({@link Role#WORKER}) or delivers the status ({@link Role#STATUS}). Every assignment is displayed and
 * started; the status comes from the workflow's last run. The goal guard
 * is not here - it hangs on the goal via {@code Goal.guardWorkflowId}.
 */
@Entity
@Table(name = "goal_workflows")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalWorkflow {

    public enum Role { WORKER, STATUS }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    /** Work package of the goal; null = on the whole goal. */
    @Column(name = "work_package_id")
    private String workPackageId;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.WORKER;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
