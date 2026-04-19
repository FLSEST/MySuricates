package com.automation.repository;

import com.automation.dto.response.TodoCountResponse;
import com.automation.entity.Todo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface TodoRepository extends JpaRepository<Todo, String>, JpaSpecificationExecutor<Todo> {

    long countByStatus(Todo.Status status);

    long countBySource(Todo.Source source);

    @Query("SELECT new com.automation.dto.response.TodoCountResponse("
         + "SUM(CASE WHEN t.status = 'OPEN' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'BACKLOG' THEN 1 ELSE 0 END)) FROM Todo t")
    TodoCountResponse countGroupedByStatus();

    /**
     * Find an open auto-todo by composite key (projectId + source=WORKFLOW + referenceType + referenceId + category tag).
     * Used for deduplication: at most 1 open todo per category per workflow per project.
     */
    @Query("SELECT t FROM Todo t WHERE t.projectId = :projectId "
         + "AND t.source = 'WORKFLOW' AND t.referenceType = :refType "
         + "AND t.referenceId = :refId AND t.status <> 'DONE' "
         + "AND t.tags LIKE %:categoryTag%")
    Optional<Todo> findOpenByCompositeKey(
            @Param("projectId") String projectId,
            @Param("refType") String referenceType,
            @Param("refId") String referenceId,
            @Param("categoryTag") String categoryTag);

    /**
     * Find all open auto-todos for a specific workflow (used by GREEN gate to close them).
     */
    List<Todo> findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
            String projectId, Todo.Source source, String referenceType,
            String referenceId, Todo.Status status);

    /**
     * Find TODOs by project and status (for similarity search across all sources).
     */
    List<Todo> findByProjectIdAndStatusIn(String projectId, List<Todo.Status> statuses);

    /**
     * Find open/backlog TODOs ordered by priority (CRITICAL first) for backlog processing.
     * Used by TodoTriggerService.processBacklog() to pick the next candidate.
     */
    @Query("SELECT t FROM Todo t WHERE t.status IN ('OPEN', 'BACKLOG') "
         + "AND t.fixAttempts < :maxAttempts "
         + "ORDER BY CASE t.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 "
         + "WHEN 'MEDIUM' THEN 2 ELSE 3 END, t.createdAt ASC")
    List<Todo> findBacklogCandidates(@Param("maxAttempts") int maxAttempts, Pageable pageable);

    /**
     * Find an open todo by referenceType and referenceId (source-agnostic).
     * Used for health check deduplication.
     */
    @Query("SELECT t FROM Todo t WHERE t.referenceType = :refType "
         + "AND t.referenceId = :refId AND t.status <> 'DONE'")
    Optional<Todo> findOpenByReferenceTypeAndId(
            @Param("refType") String referenceType,
            @Param("refId") String referenceId);

    Optional<Todo> findByLastFixRunId(String lastFixRunId);
}
