package com.automation.repository;

import com.automation.dto.projection.NeedsYouRow;
import com.automation.dto.projection.ProjectCount;
import com.automation.dto.projection.ProjectStatusCounts;
import com.automation.dto.response.TodoCountResponse;
import com.automation.entity.Todo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;

import java.time.Instant;
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
         + "SUM(CASE WHEN t.status = 'BACKLOG' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'ARCHIVED' THEN 1 ELSE 0 END)) FROM Todo t")
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
     * Backlog candidates scoped to one trigger's hard criteria (project, source,
     * the trigger's own maxAttempts). Prevents starvation: the unscoped global
     * top-N page fills up with old high-priority todos no trigger matches, so
     * trigger-relevant todos deep in the list never get a retry. Tag and
     * minPriority matching stay in TodoTriggerService (tags are a JSON column).
     */
    @Query("SELECT t FROM Todo t WHERE t.status IN ('OPEN', 'BACKLOG') "
         + "AND t.fixAttempts < :maxAttempts "
         + "AND (:projectId IS NULL OR t.projectId = :projectId) "
         + "AND (:source IS NULL OR t.source = :source) "
         + "ORDER BY CASE t.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 "
         + "WHEN 'MEDIUM' THEN 2 ELSE 3 END, t.createdAt ASC")
    List<Todo> findBacklogCandidatesForTrigger(
            @Param("projectId") String projectId,
            @Param("source") Todo.Source source,
            @Param("maxAttempts") int maxAttempts, Pageable pageable);

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

    // ── Projekt-Cockpit aggregation (lean projections, no TEXT blobs) ──────────

    /**
     * Per-project status + active-source counts in a single GROUP BY.
     * Mirrors {@link #countGroupedByStatus()} (SUM/CASE) so no entity or blob is loaded.
     * userActive/machineActive count only active statuses (OPEN/IN_PROGRESS/BACKLOG).
     */
    @Query("SELECT new com.automation.dto.projection.ProjectStatusCounts("
         + "t.projectId, "
         + "SUM(CASE WHEN t.status = 'OPEN' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'BACKLOG' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status = 'ARCHIVED' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status IN ('OPEN','IN_PROGRESS','BACKLOG') AND t.source = 'USER' THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN t.status IN ('OPEN','IN_PROGRESS','BACKLOG') AND t.source IN ('AI','WORKFLOW') THEN 1 ELSE 0 END)"
         + ") FROM Todo t GROUP BY t.projectId")
    List<ProjectStatusCounts> aggregateByProject();

    /**
     * Per-project count of stale auto-todos. Uses the SAME predicate as
     * {@link #findStaleAutoTodoIds} so the cockpit's stale badge matches exactly what
     * "Verstaubtes archivieren" will move (OPEN/BACKLOG, machine source, auto:workflow tag, no recent activity).
     */
    @Query("SELECT new com.automation.dto.projection.ProjectCount(t.projectId, COUNT(t)) FROM Todo t "
         + "WHERE t.status IN ('OPEN','BACKLOG') AND t.source IN ('AI','WORKFLOW') "
         + "AND t.tags LIKE '%auto:workflow%' AND t.updatedAt < :staleBefore GROUP BY t.projectId")
    List<ProjectCount> staleCountByProject(@Param("staleBefore") Instant staleBefore);

    /**
     * Cross-project "Braucht dich" list: active todos that are USER-source, CRITICAL, or overdue.
     * Selects short scalar columns only (never description). Ordered by priority, then due/age.
     */
    @Query("SELECT new com.automation.dto.projection.NeedsYouRow("
         + "t.id, t.title, t.projectId, t.priority, t.dueDate, t.source, t.createdAt, t.updatedAt) FROM Todo t "
         + "WHERE t.status IN ('OPEN','IN_PROGRESS','BACKLOG') AND ("
         + "  t.source = 'USER' OR t.priority = 'CRITICAL' OR (t.dueDate IS NOT NULL AND t.dueDate < :now)) "
         + "ORDER BY CASE t.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, "
         + "CASE WHEN t.dueDate IS NULL THEN 1 ELSE 0 END, t.dueDate ASC, t.createdAt DESC")
    List<NeedsYouRow> findNeedsYou(@Param("now") Instant now, Pageable pageable);

    // ── Hygiene ────────────────────────────────────────────────────────────────

    /**
     * IDs of stale auto-generated todos (auto:workflow, OPEN/BACKLOG, machine source,
     * no activity since cutoff), oldest first. IDs only — lean and capped via Pageable.
     */
    @Query("SELECT t.id FROM Todo t WHERE t.status IN ('OPEN','BACKLOG') "
         + "AND t.source IN ('AI','WORKFLOW') AND t.tags LIKE '%auto:workflow%' "
         + "AND t.updatedAt < :cutoff ORDER BY t.updatedAt ASC")
    List<String> findStaleAutoTodoIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Same as {@link #findStaleAutoTodoIds} but scoped to one project — backs the cockpit's
     * per-project "Verstaubtes archivieren" one-click action.
     */
    @Query("SELECT t.id FROM Todo t WHERE t.projectId = :projectId AND t.status IN ('OPEN','BACKLOG') "
         + "AND t.source IN ('AI','WORKFLOW') AND t.tags LIKE '%auto:workflow%' "
         + "AND t.updatedAt < :cutoff ORDER BY t.updatedAt ASC")
    List<String> findStaleAutoTodoIdsForProject(@Param("projectId") String projectId,
                                                @Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Bulk status change without loading entities. Sets updatedAt explicitly because
     * bulk JPQL bypasses @LastModifiedDate auditing. Caller must be @Transactional.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Todo t SET t.status = :status, t.updatedAt = :now WHERE t.id IN :ids")
    int bulkSetStatus(@Param("ids") List<String> ids, @Param("status") Todo.Status status, @Param("now") Instant now);
}
