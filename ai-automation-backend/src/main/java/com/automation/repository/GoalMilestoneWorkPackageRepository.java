package com.automation.repository;

import com.automation.entity.GoalMilestoneWorkPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Which work packages a milestone needs (TODO c60bf914). Per link, one statement is written
 * without reading first; deleted per milestone, per package the cascade applies.
 */
@Repository
public interface GoalMilestoneWorkPackageRepository
        extends JpaRepository<GoalMilestoneWorkPackage, GoalMilestoneWorkPackage.Key> {

    @Query("SELECT l.workPackageId FROM GoalMilestoneWorkPackage l WHERE l.milestoneId = :milestoneId ORDER BY l.workPackageId")
    List<String> findWorkPackageIds(@Param("milestoneId") String milestoneId);

    /** Links, if not yet linked. Caller must be @Transactional. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT OR IGNORE INTO goal_milestone_work_packages (milestone_id, work_package_id) "
            + "VALUES (:milestoneId, :workPackageId)", nativeQuery = true)
    int link(@Param("milestoneId") String milestoneId, @Param("workPackageId") String workPackageId);

    /** Detaches all packages from the milestone. Caller must be @Transactional. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GoalMilestoneWorkPackage l WHERE l.milestoneId = :milestoneId")
    int unlinkAll(@Param("milestoneId") String milestoneId);

    /** Detaches the package from all milestones. Caller must be @Transactional. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GoalMilestoneWorkPackage l WHERE l.workPackageId = :workPackageId")
    int unlinkWorkPackage(@Param("workPackageId") String workPackageId);
}
