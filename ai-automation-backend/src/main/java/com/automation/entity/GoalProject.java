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
 * A project a goal is explicitly assigned to, with its order in the goal
 * (TODO c60bf914). A goal can have several projects, a project several goals. If a TODO
 * from a not yet assigned project is pulled into the goal, the project is added automatically
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

    /** Order in the goal, from 0; the outline and the work packages follow it. */
    @Column(nullable = false)
    private int position;

    /** Composite key (goal_id, project_id). */
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
