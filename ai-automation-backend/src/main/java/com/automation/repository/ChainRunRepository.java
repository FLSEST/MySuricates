package com.automation.repository;

import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ChainRunRepository extends JpaRepository<ChainRun, String> {

    long countByChainId(String chainId);

    /**
     * Run counts of all chains in ONE GROUP-BY query (instead of {@code countByChainId} per chain).
     * For the /chains list — avoids N+1. Uses {@code idx_chain_runs_chain_id} (V21).
     */
    @Query("SELECT cr.chain.id, COUNT(cr) FROM ChainRun cr WHERE cr.chain IS NOT NULL GROUP BY cr.chain.id")
    List<Object[]> countRunsGroupedByChain();

    long countByStatus(RunStatus status);

    List<ChainRun> findByChainIdOrderByStartedAtDesc(String chainId);

    Page<ChainRun> findByOrderByStartedAtDesc(Pageable pageable);

    Page<ChainRun> findByStatusOrderByStartedAtDesc(RunStatus status, Pageable pageable);

    boolean existsByChainIdAndStatus(String chainId, RunStatus status);

    /** Whether the chain has a run in one of these states — the scheduler's SKIP check (RUNNING, PAUSED). */
    boolean existsByChainIdAndStatusIn(String chainId, Collection<RunStatus> statuses);

    List<ChainRun> findByStatus(RunStatus status);

    /** A schedule's newest chain runs, at most {@code limit} — its run list (TODO 86777fb4). */
    List<ChainRun> findByTriggeredByScheduleIdOrderByStartedAtDesc(String scheduleId, Limit limit);

    long countByTriggeredByScheduleId(String scheduleId);

    /**
     * Chain runs with id and name of their chain in ONE query, without entry_runs and input_context —
     * for the origin of the audit reports (TODO a3b89355).
     */
    @Query("SELECT new com.automation.dto.projection.ChainRunOriginRow(cr.id, c.id, c.name, cr.triggeredByScheduleId) "
           + "FROM ChainRun cr JOIN cr.chain c WHERE cr.id IN :ids")
    List<com.automation.dto.projection.ChainRunOriginRow> findOriginRows(@Param("ids") Collection<String> ids);

    /**
     * The chain of a chain run, without loading the run – for the link of the run origin to
     * {@code /chains?chain=<id>} (3a35ced1). Empty if the chain run is deleted.
     */
    @Query("SELECT cr.chain.id FROM ChainRun cr WHERE cr.id = :id")
    java.util.Optional<String> findChainIdById(@Param("id") String id);

    /** Like {@code WorkflowRunRepository.findStatusesByScheduleIds}, for chain runs (TODO 172a6a67). */
    @Query("SELECT r.triggeredByScheduleId, r.status, r.startedAt FROM ChainRun r "
           + "WHERE r.triggeredByScheduleId IN :scheduleIds "
           + "ORDER BY r.startedAt DESC")
    List<Object[]> findStatusesByScheduleIds(@Param("scheduleIds") Collection<String> scheduleIds);

    /** Like {@code WorkflowRunRepository.findStatusesByScheduleIdNewestFirst}, for chain runs (TODO 86286572). */
    @Query("SELECT r.status FROM ChainRun r WHERE r.triggeredByScheduleId = :scheduleId "
           + "ORDER BY r.startedAt DESC")
    List<RunStatus> findStatusesByScheduleIdNewestFirst(@Param("scheduleId") String scheduleId, Pageable pageable);

    /**
     * The run list fetches the chain in the same query: {@code ChainRun.chain} is EAGER, a
     * plain JOIN would afterwards load it once per chain of the page (TODO 44db05fd). The JOIN FETCH
     * needs its own count query.
     */
    @Query(value = "SELECT cr FROM ChainRun cr LEFT JOIN FETCH cr.chain c " +
           "WHERE (:status IS NULL OR cr.status = :status) " +
           "AND (:search IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY cr.startedAt DESC",
           countQuery = "SELECT COUNT(cr) FROM ChainRun cr LEFT JOIN cr.chain c " +
           "WHERE (:status IS NULL OR cr.status = :status) " +
           "AND (:search IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<ChainRun> findFiltered(@Param("status") RunStatus status,
                                 @Param("search") String search,
                                 Pageable pageable);
}
