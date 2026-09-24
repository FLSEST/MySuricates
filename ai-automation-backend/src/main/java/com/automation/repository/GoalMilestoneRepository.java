package com.automation.repository;

import com.automation.entity.GoalMilestone;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Meilensteine der Ziele (TODO 259f4f66); die zugeordneten TODOs zaehlt {@link TodoRepository}. */
@Repository
public interface GoalMilestoneRepository extends JpaRepository<GoalMilestone, String> {

    List<GoalMilestone> findByGoalId(String goalId);

    /**
     * Noch nicht erledigte Meilensteine aktiver Ziele, die bis {@code until} faellig sind – die
     * Kandidaten fuer „Braucht dich“. Ob einer inzwischen erreicht ist, entscheiden die Zaehler.
     */
    @Query("SELECT m FROM GoalMilestone m, Goal g WHERE g.id = m.goalId AND g.status = 'ACTIVE' "
         + "AND m.dueDate <= :until ORDER BY m.dueDate ASC")
    List<GoalMilestone> findDueForActiveGoals(@Param("until") Instant until, Pageable page);
}
