package com.automation.repository;

import com.automation.entity.Workflow;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    @EntityGraph(attributePaths = {"activatedSkills", "project"})
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
     * Eagerly fetches activatedSkills to avoid LazyInitializationException (OSIV disabled).
     */
    @EntityGraph(attributePaths = {"activatedSkills", "project"})
    @Query("SELECT w FROM Workflow w WHERE w.project.archivedAt IS NULL")
    List<Workflow> findAllWithActiveProject();

    /**
     * Finds a workflow by ID with eagerly fetched activatedSkills and project.
     */
    @EntityGraph(attributePaths = {"activatedSkills", "project"})
    @Query("SELECT w FROM Workflow w WHERE w.id = :id")
    Optional<Workflow> findByIdWithSkills(@Param("id") String id);

    /**
     * Finds workflows that need block_types backfill (null or empty).
     */
    @Query("SELECT w FROM Workflow w WHERE w.blockTypes IS NULL OR w.blockTypes = ''")
    List<Workflow> findWithEmptyBlockTypes();

    /**
     * Workflows that have never been auto-tagged — used for one-shot backfill at startup.
     */
    @Query("SELECT w FROM Workflow w WHERE w.tagsGeneratedAt IS NULL AND w.tagsUserEdited = false")
    List<Workflow> findUntagged();

    /**
     * Loads all workflows in Hibernate read-only mode — prevents dirty-checking and flushes.
     * Used by InstanceSyncHelper to avoid Hibernate overwriting stable_synced_at via dirty-check flush.
     */
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    @Query("SELECT w FROM Workflow w")
    List<Workflow> findAllReadOnly();

    /**
     * Updates stable_synced_at via native SQL — bypasses @LastModifiedDate on updatedAt.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE workflows SET stable_synced_at = :syncedAt WHERE id = :id", nativeQuery = true)
    void updateStableSyncedAt(@Param("id") String id, @Param("syncedAt") String syncedAt);

    /**
     * Nur {@code id} und {@code name} der (nicht soft-gelöschten) Workflows — für die
     * Workflow-Widgets des Dashboards. Root-Query auf Workflow, also greift
     * {@code @SQLRestriction("deleted_at IS NULL")}: ein gelöschter Workflow fehlt im Ergebnis und
     * das Widget zeigt „nicht mehr vorhanden“.
     */
    @Query("SELECT w.id, w.name FROM Workflow w WHERE w.id IN :ids")
    List<Object[]> findIdAndNameByIdIn(@Param("ids") Collection<String> ids);
}
