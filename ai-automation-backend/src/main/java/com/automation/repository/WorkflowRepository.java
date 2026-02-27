package com.automation.repository;

import com.automation.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    List<Workflow> findByProjectId(String projectId);

    List<Workflow> findByNameContainingIgnoreCase(String name);

    /**
     * Lightweight projection: returns only id, name, block_types for similarity queries.
     * Avoids full entity loading, JSON deserialization, and EAGER Project join.
     */
    @Query(value = "SELECT w.id, w.name, w.block_types FROM workflows w WHERE w.id != :excludeId",
            nativeQuery = true)
    List<Object[]> findAllIdNameBlockTypes(@Param("excludeId") String excludeId);

    /**
     * Finds workflows that need block_types backfill (null or empty).
     */
    @Query("SELECT w FROM Workflow w WHERE w.blockTypes IS NULL OR w.blockTypes = ''")
    List<Workflow> findWithEmptyBlockTypes();
}
