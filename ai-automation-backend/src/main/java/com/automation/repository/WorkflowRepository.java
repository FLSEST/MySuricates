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
     * Workflow count per project in ONE query — for the project list ({@code workflowCount}).
     * JPQL on purpose: {@code @SQLRestriction("deleted_at IS NULL")} applies, so soft-deleted
     * workflows do not count (a native query would count them).
     */
    @Query("SELECT w.project.id, COUNT(w) FROM Workflow w GROUP BY w.project.id")
    List<Object[]> countWorkflowsGroupedByProject();

    /** Workflow count of one project; soft-deleted workflows excluded via {@code @SQLRestriction}. */
    long countByProjectId(String projectId);

    /**
     * Finds all workflows whose project is not archived — the list behind {@code /workflows} and
     * {@code /search}. Eagerly fetches activatedSkills to avoid LazyInitializationException (OSIV
     * disabled), and the project's container along with the project: {@code Project.container} is a
     * nullable {@code @OneToOne(mappedBy)} that Hibernate otherwise resolves with one
     * {@code select containers} per result row, 30 for 30 workflows (TODO 13d1e2a2).
     */
    @EntityGraph(attributePaths = {"activatedSkills", "project", "project.container"})
    @Query("SELECT w FROM Workflow w WHERE w.project.archivedAt IS NULL")
    List<Workflow> findAllWithActiveProject();

    /**
     * Workflow- und Projektbezeichner für eine Menge von Workflows in EINER Query — für Run-Listen,
     * die sonst je Zeile die Lazy-Assoziation {@code WorkflowRun.workflow} auflösen (N+1, TODO
     * 9db34f3b). Soft-gelöschte Workflows fehlen im Ergebnis (`@SQLRestriction`); der Mapper fällt
     * dann auf den Namens-Schnappschuss des Runs bzw. "(deleted)" zurück — dasselbe Verhalten wie
     * beim Einzel-Mapping.
     */
    @Query("SELECT w.id, w.name, w.project.name, w.project.path FROM Workflow w WHERE w.id IN :ids")
    List<Object[]> findRunOwnerData(@Param("ids") Collection<String> ids);

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

    /**
     * Names of sealed, not deleted workflows that use a skill — pinned at sealing, activated, or
     * referenced from a block. Conservative on purpose (substring match on the JSON columns): a
     * false positive only asks for an unseal, a false negative would let a sealed workflow change.
     */
    @Query(value = "SELECT w.name FROM workflows w WHERE w.sealed = 1 AND w.deleted_at IS NULL AND ("
            + "instr(coalesce(w.sealed_skill_hashes, ''), :skillId) > 0 OR instr(coalesce(w.blocks, ''), :skillId) > 0 "
            + "OR EXISTS (SELECT 1 FROM workflow_skills ws WHERE ws.workflow_id = w.id AND ws.skill_id = :skillId)) "
            + "ORDER BY w.name", nativeQuery = true)
    List<String> findSealedWorkflowNamesUsingSkill(@Param("skillId") String skillId);

    /** Sealed, not deleted workflows with their activated skills — the seal status view and skill pins. */
    @EntityGraph(attributePaths = {"activatedSkills"})
    @Query("SELECT w FROM Workflow w WHERE w.sealed = true ORDER BY w.name")
    List<Workflow> findSealedWithSkills();
}
