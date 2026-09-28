package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * Ein Meilenstein eines Ziels (TODO c60bf914): ein Termin fuers ganze Ziel – kein Projekt, immer
 * mit Datum. Optional mit Arbeitspaketen verknuepft ({@link GoalMilestoneWorkPackage}): dann ist er
 * erreicht, wenn alle verknuepften Pakete abgeschlossen sind, und {@link #reachedAt} haelt den ersten
 * Zeitpunkt fest. Ohne verknuepfte Pakete wird er von Hand als erreicht markiert. Der Zustand wird
 * beim Lesen berechnet ({@code MilestoneProgress}).
 *
 * <p>Nicht zu verwechseln mit den Meilensteinen bis V92 – die heissen seitdem Arbeitspakete
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

    /** Erreicht seit; mit Paketen das erste Erkennen, ohne Pakete von Hand gesetzt. null = nicht erreicht. */
    @Column(name = "reached_at")
    private Instant reachedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
