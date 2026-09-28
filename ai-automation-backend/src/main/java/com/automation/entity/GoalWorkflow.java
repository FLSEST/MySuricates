package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * Ein Workflow, der an einem Ziel oder an einem seiner Arbeitspakete haengt (TODO 52bc1e8c): Er
 * arbeitet daran ({@link Role#WORKER}) oder liefert den Stand ({@link Role#STATUS}). Angezeigt und
 * gestartet wird jede Zuordnung; der Stand kommt aus dem letzten Lauf des Workflows. Der Ziel-Waechter
 * steht nicht hier – er haengt ueber {@code Goal.guardWorkflowId} am Ziel.
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

    /** Arbeitspaket des Ziels; null = am ganzen Ziel. */
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
