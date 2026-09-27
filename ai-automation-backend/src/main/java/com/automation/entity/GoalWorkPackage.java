package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Ein Arbeitspaket eines Ziels (TODO c60bf914): gehoert genau einem Projekt des Ziels
 * ({@link #projectId}), buendelt TODOs dieses Projekts ({@code Todo.workPackageId}) und hat einen
 * optionalen Zeitraum – {@link #startDate} und {@link #endDate} gibt es nur zusammen, Start nie nach
 * dem Ende. Die Reihenfolge innerhalb des Projekts steht in {@link #position}. {@code projectId} ist
 * nur bei Altdaten eines Ziels ohne Projekte null. Bis V92 hiessen sie Meilensteine (TODO 259f4f66).
 *
 * <p>Der Zustand wird nicht gespeichert, sondern beim Lesen berechnet ({@link #stateOf}); nur
 * {@link #completedAt} haelt fest, wann das Paket zum ersten Mal abgeschlossen war. Der Zeitpunkt
 * bleibt, auch wenn spaeter wieder ein offenes TODO dazukommt – die Oberflaeche zeigt es dann als
 * „wieder offen“.
 */
@Entity
@Table(name = "goal_work_packages")
@EntityListeners(AuditingEntityListener.class)
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

    /** Projekt des Pakets; null nur bei Altdaten eines Ziels ohne Projekte (V92). */
    @Column(name = "project_id")
    private String projectId;

    @Column(nullable = false)
    private String title;

    /** Beginn des Zeitraums; null = ohne Zeitraum (dann auch kein Ende). */
    @Column(name = "start_date")
    private Instant startDate;

    /** Ende des Zeitraums – die Basis fuer „bald faellig“/„ueberfaellig“; null = ohne Zeitraum. */
    @Column(name = "end_date")
    private Instant endDate;

    /** Reihenfolge innerhalb seines Projekts im Ziel, ab 0. */
    @Column(nullable = false)
    private int position;

    /** Erstes Erkennen „alle zugeordneten TODOs erledigt“; null = noch nie abgeschlossen. */
    @Column(name = "completed_at")
    private Instant completedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Zustand aus dem Ende und den zugeordneten TODOs. {@code total}/{@code done} zaehlen ohne
     * ARCHIVED. REACHED (abgeschlossen) braucht mindestens ein TODO – ein leeres Paket ist nicht fertig.
     */
    public static GoalDueState stateOf(Instant endDate, long total, long done, Instant now) {
        return GoalDueState.of(endDate, isComplete(total, done), now);
    }

    /** Abgeschlossen: mindestens ein TODO, alle (ohne ARCHIVED) erledigt. */
    public static boolean isComplete(long total, long done) {
        return total > 0 && done >= total;
    }
}
