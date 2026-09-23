package com.automation.repository;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StepResultRepository extends JpaRepository<StepResult, String> {

    List<StepResult> findByWorkflowRunId(String workflowRunId);

    List<StepResult> findByWorkflowRunIdOrderByStepIndexAsc(String workflowRunId);

    List<StepResult> findByStatus(StepStatus status);

    /**
     * Outputs of one run, newest step first — the source of the dashboard-report assist.
     *
     * <p>Deliberately a projection of ONE column: {@code input} and {@code context_delta} are the
     * other large TEXT columns of a step, and with pool-size = 1 every byte holds the single SQLite
     * connection. The caller pages this (never the whole run) and caps the joined text.
     */
    @Query("SELECT s.output FROM StepResult s WHERE s.workflowRun.id = :runId AND s.output IS NOT NULL "
         + "ORDER BY s.stepIndex DESC")
    List<String> findOutputsByRunIdNewestFirst(@Param("runId") String runId, Pageable pageable);

    /**
     * Rows {@code [runId, stepCount]} for the run lists — counted, not loaded (TODO 7fd9f654).
     * Runs without steps have no row.
     */
    @Query("SELECT s.workflowRun.id, COUNT(s) FROM StepResult s WHERE s.workflowRun.id IN :runIds "
         + "GROUP BY s.workflowRun.id")
    List<Object[]> countStepsByRunIds(@Param("runIds") Collection<String> runIds);

    /**
     * Rows {@code [runId, status, head]} with the last step of every run: its status and the
     * first {@code chars} characters of its output. The output is cut in the database, so a list
     * of runs never carries a whole step blob across the single SQLite connection (TODO 7fd9f654).
     */
    @Query("SELECT s.workflowRun.id, s.status, SUBSTRING(s.output, 1, :chars) FROM StepResult s "
         + "WHERE s.workflowRun.id IN :runIds AND s.stepIndex = "
         + "(SELECT MAX(last.stepIndex) FROM StepResult last WHERE last.workflowRun = s.workflowRun)")
    List<Object[]> findLastStepHeadsByRunIds(@Param("runIds") Collection<String> runIds, @Param("chars") int chars);
}
