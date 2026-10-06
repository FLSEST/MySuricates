package com.automation.repository;

import com.automation.dto.projection.GoalWithWorkPackages;
import com.automation.entity.Goal;
import com.automation.entity.GoalWorkPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Goals (TODO dc0566f8); the membership of the TODOs is queried by {@link TodoRepository}. */
@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {

    List<Goal> findByStatusInOrderByCreatedAtDesc(Collection<Goal.Status> statuses);

    /** The goals to which the project is explicitly assigned (TODO c60bf914), newest first. */
    @Query("SELECT g FROM Goal g WHERE g.status IN :statuses AND EXISTS "
         + "(SELECT 1 FROM GoalProject gp WHERE gp.goalId = g.id AND gp.projectId = :projectId) "
         + "ORDER BY g.createdAt DESC")
    List<Goal> findForProject(@Param("statuses") Collection<Goal.Status> statuses, @Param("projectId") String projectId);

    /**
     * Goal with work packages in one query (TODO 259f4f66, c60bf914) – the package list stays at
     * two statements. One row per package, without a package one with {@code null}. Plus the position of the
     * package's project in the goal, so that sorting needs no further query.
     */
    @Query("SELECT g, p, gp.position FROM Goal g LEFT JOIN GoalWorkPackage p ON p.goalId = g.id "
         + "LEFT JOIN GoalProject gp ON gp.goalId = p.goalId AND gp.projectId = p.projectId WHERE g.id = :id")
    List<Object[]> findWithWorkPackageRows(@Param("id") String id);

    /** {@link #findWithWorkPackageRows} as goal plus list; empty if the goal does not exist. */
    default Optional<GoalWithWorkPackages> findWithWorkPackages(String id) {
        List<Object[]> rows = findWithWorkPackageRows(id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<GoalWorkPackage> workPackages = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();
        for (Object[] row : rows) {
            if (row[1] instanceof GoalWorkPackage workPackage) {
                workPackages.add(workPackage);
                if (workPackage.getProjectId() != null && row[2] instanceof Integer position) {
                    positions.put(workPackage.getProjectId(), position);
                }
            }
        }
        return Optional.of(new GoalWithWorkPackages((Goal) rows.get(0)[0], workPackages, positions));
    }
}
