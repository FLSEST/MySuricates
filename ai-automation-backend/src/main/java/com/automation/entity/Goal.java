package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Ein Ergebnis, auf das TODOs einzahlen – etwa „Open-Source-Launch“ (TODO dc0566f8).
 *
 * <p>Projektuebergreifend: Die Zugehoerigkeit steht am TODO ({@code Todo.goalId}), nicht hier.
 * Ein Ziel kennt deshalb keine Liste seiner TODOs; Fortschritt und Reihenfolge rechnet
 * {@code GoalService} aus den TODOs zusammen.
 */
@Entity
@Table(name = "goals")
@EntityListeners(AuditingEntityListener.class)
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

    /** Was „fertig“ heisst – Markdown. */
    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "target_date")
    private Instant targetDate;

    /**
     * Workflow des Ziel-Waechters (Vorlage {@code goal-guard}, TODO 259f4f66); null = nie
     * eingeschaltet. Ob er gerade laeuft, sagt sein Zeitplan, nicht dieses Feld.
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
