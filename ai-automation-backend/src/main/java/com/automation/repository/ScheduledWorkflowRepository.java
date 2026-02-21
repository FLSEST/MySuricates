package com.automation.repository;

import com.automation.entity.ScheduledWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ScheduledWorkflowRepository extends JpaRepository<ScheduledWorkflow, String> {

    List<ScheduledWorkflow> findByEnabledTrue();

    @Query("SELECT s FROM ScheduledWorkflow s WHERE s.enabled = true AND s.nextRunAt <= :before")
    List<ScheduledWorkflow> findDueSchedules(@Param("before") Instant before);

    List<ScheduledWorkflow> findByWorkflowId(String workflowId);

    @Query("SELECT s FROM ScheduledWorkflow s WHERE s.enabled = true AND s.nextRunAt BETWEEN :from AND :to")
    List<ScheduledWorkflow> findSchedulesInRange(@Param("from") Instant from, @Param("to") Instant to);
}
