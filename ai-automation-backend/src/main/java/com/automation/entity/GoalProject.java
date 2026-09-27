package com.automation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Ein Projekt, dem ein Ziel ausdruecklich zugeordnet ist, mit seiner Reihenfolge im Ziel
 * (TODO c60bf914). Ein Ziel kann mehrere Projekte haben, ein Projekt mehrere Ziele. Wird ein TODO
 * aus einem noch nicht zugeordneten Projekt ins Ziel geholt, kommt das Projekt automatisch dazu
 * ({@code GoalProjectRepository#linkIfAbsent}).
 */
@Entity
@Table(name = "goal_projects")
@IdClass(GoalProject.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalProject {

    @Id
    @Column(name = "goal_id")
    private String goalId;

    @Id
    @Column(name = "project_id")
    private String projectId;

    /** Reihenfolge im Ziel, ab 0; die Gliederung und die Teilziele folgen ihr. */
    @Column(nullable = false)
    private int position;

    /** Zusammengesetzter Schluessel (goal_id, project_id). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String goalId;
        private String projectId;
    }
}
