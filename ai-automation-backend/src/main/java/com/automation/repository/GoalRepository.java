package com.automation.repository;

import com.automation.dto.projection.GoalWithMilestones;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ziele (TODO dc0566f8); die Zugehoerigkeit der TODOs fragt {@link TodoRepository} ab. */
@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {

    List<Goal> findByStatusInOrderByCreatedAtDesc(Collection<Goal.Status> statuses);

    /**
     * Ziel samt Meilensteinen in einer Abfrage (TODO 259f4f66) – die Detailansicht bleibt bei zwei
     * Statements. Eine Zeile je Meilenstein, ohne Meilenstein eine mit {@code null}.
     */
    @Query("SELECT g, m FROM Goal g LEFT JOIN GoalMilestone m ON m.goalId = g.id WHERE g.id = :id")
    List<Object[]> findWithMilestoneRows(@Param("id") String id);

    /** {@link #findWithMilestoneRows} als Ziel plus Liste; leer, wenn es das Ziel nicht gibt. */
    default Optional<GoalWithMilestones> findWithMilestones(String id) {
        List<Object[]> rows = findWithMilestoneRows(id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<GoalMilestone> milestones = rows.stream()
                .map(row -> (GoalMilestone) row[1])
                .filter(Objects::nonNull)
                .toList();
        return Optional.of(new GoalWithMilestones((Goal) rows.get(0)[0], milestones));
    }
}
