package com.automation.repository;

import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
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
     * Run-Counts aller Chains in EINER GROUP-BY-Query (statt {@code countByChainId} pro Chain).
     * Für die /chains-Liste — vermeidet N+1. Nutzt {@code idx_chain_runs_chain_id} (V21).
     */
    @Query("SELECT cr.chain.id, COUNT(cr) FROM ChainRun cr WHERE cr.chain IS NOT NULL GROUP BY cr.chain.id")
    List<Object[]> countRunsGroupedByChain();

    long countByStatus(RunStatus status);

    List<ChainRun> findByChainIdOrderByStartedAtDesc(String chainId);

    Page<ChainRun> findByOrderByStartedAtDesc(Pageable pageable);

    Page<ChainRun> findByStatusOrderByStartedAtDesc(RunStatus status, Pageable pageable);

    boolean existsByChainIdAndStatus(String chainId, RunStatus status);

    List<ChainRun> findByStatus(RunStatus status);

    List<ChainRun> findByTriggeredByScheduleIdOrderByStartedAtDesc(String scheduleId);

    /** Wie {@code WorkflowRunRepository.findStatusesByScheduleIds}, fuer Ketten-Runs (TODO 172a6a67). */
    @Query("SELECT r.triggeredByScheduleId, r.status, r.startedAt FROM ChainRun r "
           + "WHERE r.triggeredByScheduleId IN :scheduleIds "
           + "ORDER BY r.startedAt DESC")
    List<Object[]> findStatusesByScheduleIds(@Param("scheduleIds") Collection<String> scheduleIds);

    @Query("SELECT cr FROM ChainRun cr LEFT JOIN cr.chain c " +
           "WHERE (:status IS NULL OR cr.status = :status) " +
           "AND (:search IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY cr.startedAt DESC")
    Page<ChainRun> findFiltered(@Param("status") RunStatus status,
                                 @Param("search") String search,
                                 Pageable pageable);
}
