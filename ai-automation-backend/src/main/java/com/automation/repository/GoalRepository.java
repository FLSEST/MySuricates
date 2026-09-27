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

/** Ziele (TODO dc0566f8); die Zugehoerigkeit der TODOs fragt {@link TodoRepository} ab. */
@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {

    List<Goal> findByStatusInOrderByCreatedAtDesc(Collection<Goal.Status> statuses);

    /** Die Ziele, denen das Projekt ausdruecklich zugeordnet ist (TODO c60bf914), neueste zuerst. */
    @Query("SELECT g FROM Goal g WHERE g.status IN :statuses AND EXISTS "
         + "(SELECT 1 FROM GoalProject gp WHERE gp.goalId = g.id AND gp.projectId = :projectId) "
         + "ORDER BY g.createdAt DESC")
    List<Goal> findForProject(@Param("statuses") Collection<Goal.Status> statuses, @Param("projectId") String projectId);

    /**
     * Ziel samt Arbeitspaketen in einer Abfrage (TODO 259f4f66, c60bf914) – die Paket-Liste bleibt bei
     * zwei Statements. Eine Zeile je Paket, ohne Paket eine mit {@code null}. Dazu die Position des
     * Paket-Projekts im Ziel, damit die Sortierung keine Abfrage mehr braucht.
     */
    @Query("SELECT g, p, gp.position FROM Goal g LEFT JOIN GoalWorkPackage p ON p.goalId = g.id "
         + "LEFT JOIN GoalProject gp ON gp.goalId = p.goalId AND gp.projectId = p.projectId WHERE g.id = :id")
    List<Object[]> findWithWorkPackageRows(@Param("id") String id);

    /** {@link #findWithWorkPackageRows} als Ziel plus Liste; leer, wenn es das Ziel nicht gibt. */
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
