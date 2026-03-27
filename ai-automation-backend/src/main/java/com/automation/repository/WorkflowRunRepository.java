package com.automation.repository;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    List<WorkflowRun> findByStatus(RunStatus status);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow")
    List<WorkflowRun> findAllWithWorkflow();

    long countByWorkflowId(String workflowId);

    long countByStatus(RunStatus status);

    long countByStartedAtAfter(Instant since);

    List<WorkflowRun> findAllByOrderByStartedAtDesc();

    List<WorkflowRun> findByStatusOrderByStartedAtDesc(RunStatus status);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE w.project.id = :projectId ORDER BY wr.startedAt DESC")
    List<WorkflowRun> findByProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("projectId") String projectId);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE wr.status = :status AND w.project.id = :projectId ORDER BY wr.startedAt DESC")
    List<WorkflowRun> findByStatusAndProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("status") RunStatus status, @org.springframework.data.repository.query.Param("projectId") String projectId);

    List<WorkflowRun> findTop10ByOrderByStartedAtDesc();

    List<WorkflowRun> findByStartedAtAfterOrderByStartedAtDesc(Instant since);

    @Query("SELECT wr.workflow.id, wr.workflow.name, COUNT(wr) " +
           "FROM WorkflowRun wr " +
           "WHERE wr.workflow IS NOT NULL " +
           "GROUP BY wr.workflow.id, wr.workflow.name " +
           "ORDER BY COUNT(wr) DESC " +
           "LIMIT 5")
    List<Object[]> findTopWorkflows();

    List<WorkflowRun> findByStartedAtBetween(Instant from, Instant to);

    long countByStatusAndStartedAtAfter(RunStatus status, Instant since);

    // Pageable variants
    Page<WorkflowRun> findByOrderByStartedAtDesc(Pageable pageable);

    Page<WorkflowRun> findByStatusOrderByStartedAtDesc(RunStatus status, Pageable pageable);

    Page<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId, Pageable pageable);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE w.project.id = :projectId ORDER BY wr.startedAt DESC")
    Page<WorkflowRun> findByProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("projectId") String projectId, Pageable pageable);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE wr.status = :status AND w.project.id = :projectId ORDER BY wr.startedAt DESC")
    Page<WorkflowRun> findByStatusAndProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("status") RunStatus status, @org.springframework.data.repository.query.Param("projectId") String projectId, Pageable pageable);

    @Query("SELECT DISTINCT wr.workflow.id FROM WorkflowRun wr WHERE wr.status = 'FAILED' AND wr.workflow IS NOT NULL")
    List<String> findDistinctWorkflowIdsWithFailedRuns();

    boolean existsByWorkflowIdAndStatus(String workflowId, RunStatus status);

    @Query("SELECT r FROM WorkflowRun r LEFT JOIN FETCH r.steps WHERE r.id = :id")
    Optional<WorkflowRun> findByIdWithSteps(@Param("id") String id);

    List<WorkflowRun> findTop5ByWorkflowIdOrderByStartedAtDesc(String workflowId);

    List<WorkflowRun> findByTriggeredByScheduleIdOrderByStartedAtDesc(String scheduleId);
}
