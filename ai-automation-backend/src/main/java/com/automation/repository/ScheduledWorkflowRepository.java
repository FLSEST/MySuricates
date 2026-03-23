package com.automation.repository;

import com.automation.entity.ScheduledWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledWorkflowRepository extends JpaRepository<ScheduledWorkflow, String> {

    List<ScheduledWorkflow> findByEnabledTrue();

    @Query("SELECT s FROM ScheduledWorkflow s WHERE s.enabled = true AND s.nextRunAt <= :before")
    List<ScheduledWorkflow> findDueSchedules(@Param("before") Instant before);

    /**
     * Loads a schedule with its workflow's activatedSkills eagerly fetched via JOIN FETCH.
     * Use this in the scheduler executor to avoid LazyInitializationException when
     * WorkflowExecutor.execute() accesses activatedSkills outside a JPA session.
     */
    @Query("SELECT sw FROM ScheduledWorkflow sw " +
           "LEFT JOIN FETCH sw.workflow w " +
           "LEFT JOIN FETCH w.activatedSkills " +
           "WHERE sw.id = :id")
    Optional<ScheduledWorkflow> findByIdWithWorkflowSkills(@Param("id") String id);

    List<ScheduledWorkflow> findByWorkflowId(String workflowId);

    List<ScheduledWorkflow> findByChainId(String chainId);

    @Query("SELECT s FROM ScheduledWorkflow s WHERE s.enabled = true AND s.nextRunAt BETWEEN :from AND :to")
    List<ScheduledWorkflow> findSchedulesInRange(@Param("from") Instant from, @Param("to") Instant to);
}
