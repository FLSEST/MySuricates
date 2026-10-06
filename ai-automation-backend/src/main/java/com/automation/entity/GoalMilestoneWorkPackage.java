package com.automation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * A work package a milestone depends on (TODO c60bf914). Both sides belong to the
 * same goal; the link is deleted with the milestone or the package (cascade).
 */
@Entity
@Table(name = "goal_milestone_work_packages")
@IdClass(GoalMilestoneWorkPackage.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoalMilestoneWorkPackage {

    @Id
    @Column(name = "milestone_id")
    private String milestoneId;

    @Id
    @Column(name = "work_package_id")
    private String workPackageId;

    /** Composite key (milestone_id, work_package_id). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String milestoneId;
        private String workPackageId;
    }
}
