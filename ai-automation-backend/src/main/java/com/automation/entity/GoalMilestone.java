package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Duration;
import java.time.Instant;

/**
 * Ein Zwischenpunkt auf dem Weg zu einem Ziel: Titel und Datum (TODO 259f4f66). TODOs des Ziels
 * werden ihm ueber {@code Todo.milestoneId} zugeordnet.
 *
 * <p>Der Zustand wird nicht gespeichert, sondern beim Lesen berechnet ({@link #stateOf}); nur
 * {@link #reachedAt} haelt fest, wann er zum ersten Mal erreicht war. Der Zeitpunkt bleibt, auch
 * wenn spaeter wieder ein offenes TODO dazukommt – die Oberflaeche zeigt ihn dann als „wieder offen“.
 */
@Entity
@Table(name = "goal_milestones")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalMilestone {

    /** Ab hier heisst ein offener Meilenstein „bald faellig“. */
    public static final Duration DUE_SOON = Duration.ofDays(3);

    public enum State { OPEN, DUE_SOON, OVERDUE, REACHED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "goal_id", nullable = false)
    private String goalId;

    @Column(nullable = false)
    private String title;

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    /** Erstes Erkennen „alle zugeordneten TODOs erledigt“; null = noch nie erreicht. */
    @Column(name = "reached_at")
    private Instant reachedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Zustand aus Faelligkeit und zugeordneten TODOs. {@code total}/{@code done} zaehlen ohne
     * ARCHIVED. REACHED braucht mindestens ein TODO – ein leerer Meilenstein ist nicht erreicht.
     */
    public static State stateOf(Instant dueDate, long total, long done, Instant now) {
        if (total > 0 && done >= total) {
            return State.REACHED;
        }
        if (dueDate == null) {
            return State.OPEN;
        }
        if (dueDate.isBefore(now)) {
            return State.OVERDUE;
        }
        return dueDate.isAfter(now.plus(DUE_SOON)) ? State.OPEN : State.DUE_SOON;
    }
}
