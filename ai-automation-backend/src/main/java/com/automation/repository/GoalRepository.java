package com.automation.repository;

import com.automation.dto.projection.GoalWithMilestones;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
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
     * Ziel samt Meilensteinen in einer Abfrage (TODO 259f4f66) – die Meilenstein-Liste bleibt bei zwei
     * Statements. Eine Zeile je Meilenstein, ohne Meilenstein eine mit {@code null}. Dazu die Position
     * des Meilenstein-Projekts im Ziel (TODO c60bf914), damit die Sortierung keine Abfrage mehr braucht.
     */
    @Query("SELECT g, m, gp.position FROM Goal g LEFT JOIN GoalMilestone m ON m.goalId = g.id "
         + "LEFT JOIN GoalProject gp ON gp.goalId = m.goalId AND gp.projectId = m.projectId WHERE g.id = :id")
    List<Object[]> findWithMilestoneRows(@Param("id") String id);

    /** {@link #findWithMilestoneRows} als Ziel plus Liste; leer, wenn es das Ziel nicht gibt. */
    default Optional<GoalWithMilestones> findWithMilestones(String id) {
        List<Object[]> rows = findWithMilestoneRows(id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<GoalMilestone> milestones = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();
        for (Object[] row : rows) {
            if (row[1] instanceof GoalMilestone milestone) {
                milestones.add(milestone);
                if (milestone.getProjectId() != null && row[2] instanceof Integer position) {
                    positions.put(milestone.getProjectId(), position);
                }
            }
        }
        return Optional.of(new GoalWithMilestones((Goal) rows.get(0)[0], milestones, positions));
    }
}
