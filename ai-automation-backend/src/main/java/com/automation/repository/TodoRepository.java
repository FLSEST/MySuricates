package com.automation.repository;

import com.automation.entity.Todo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TodoRepository extends JpaRepository<Todo, String>, JpaSpecificationExecutor<Todo> {

    long countByStatus(Todo.Status status);

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
     * Find an open todo by referenceType and referenceId (source-agnostic).
     * Used for health check deduplication.
     */
    @Query("SELECT t FROM Todo t WHERE t.referenceType = :refType "
         + "AND t.referenceId = :refId AND t.status <> 'DONE'")
    Optional<Todo> findOpenByReferenceTypeAndId(
            @Param("refType") String referenceType,
            @Param("refId") String referenceId);
}
