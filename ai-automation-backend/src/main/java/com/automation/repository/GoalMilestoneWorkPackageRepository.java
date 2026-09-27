package com.automation.repository;

import com.automation.entity.GoalMilestoneWorkPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Welche Arbeitspakete ein Meilenstein braucht (TODO c60bf914). Geschrieben wird je Verknuepfung
 * ein Statement ohne vorheriges Lesen; geloescht per Meilenstein, per Paket greift die Kaskade.
 */
@Repository
public interface GoalMilestoneWorkPackageRepository
        extends JpaRepository<GoalMilestoneWorkPackage, GoalMilestoneWorkPackage.Key> {

    @Query("SELECT l.workPackageId FROM GoalMilestoneWorkPackage l WHERE l.milestoneId = :milestoneId ORDER BY l.workPackageId")
    List<String> findWorkPackageIds(@Param("milestoneId") String milestoneId);

    /** Verknuepft, falls noch nicht verknuepft. Aufrufer muss @Transactional sein. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT OR IGNORE INTO goal_milestone_work_packages (milestone_id, work_package_id) "
            + "VALUES (:milestoneId, :workPackageId)", nativeQuery = true)
    int link(@Param("milestoneId") String milestoneId, @Param("workPackageId") String workPackageId);

    /** Loest alle Pakete vom Meilenstein. Aufrufer muss @Transactional sein. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GoalMilestoneWorkPackage l WHERE l.milestoneId = :milestoneId")
    int unlinkAll(@Param("milestoneId") String milestoneId);

    /** Loest das Paket von allen Meilensteinen. Aufrufer muss @Transactional sein. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM GoalMilestoneWorkPackage l WHERE l.workPackageId = :workPackageId")
    int unlinkWorkPackage(@Param("workPackageId") String workPackageId);
}
