package com.automation.repository;

import com.automation.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    List<Workflow> findByProjectId(String projectId);

    List<Workflow> findByNameContainingIgnoreCase(String name);

    Optional<Workflow> findByNameAndProjectId(String name, String projectId);

    /**
     * Lightweight projection: returns only id, name, block_types for similarity queries.
     * Avoids full entity loading, JSON deserialization, and EAGER Project join.
     */
    @Query(value = "SELECT w.id, w.name, w.block_types FROM workflows w WHERE w.id != :excludeId AND w.deleted_at IS NULL",
            nativeQuery = true)
    List<Object[]> findAllIdNameBlockTypes(@Param("excludeId") String excludeId);

    /**
     * Finds all workflows whose project is not archived.
     */
    @Query("SELECT w FROM Workflow w WHERE w.project.archivedAt IS NULL")
    List<Workflow> findAllWithActiveProject();

    /**
     * Finds workflows that need block_types backfill (null or empty).
     */
    @Query("SELECT w FROM Workflow w WHERE w.blockTypes IS NULL OR w.blockTypes = ''")
    List<Workflow> findWithEmptyBlockTypes();

}
