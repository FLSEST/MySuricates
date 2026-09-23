package com.automation.repository;

import com.automation.entity.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Ziele (TODO dc0566f8); die Zugehoerigkeit der TODOs fragt {@link TodoRepository} ab. */
@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {

    List<Goal> findByStatusInOrderByCreatedAtDesc(Collection<Goal.Status> statuses);
}
