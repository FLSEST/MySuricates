package com.automation.repository;

import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChainRunRepository extends JpaRepository<ChainRun, String> {

    long countByChainId(String chainId);

    long countByStatus(RunStatus status);

    List<ChainRun> findByChainIdOrderByStartedAtDesc(String chainId);

    Page<ChainRun> findByOrderByStartedAtDesc(Pageable pageable);

    Page<ChainRun> findByStatusOrderByStartedAtDesc(RunStatus status, Pageable pageable);

    boolean existsByChainIdAndStatus(String chainId, RunStatus status);

    List<ChainRun> findByTriggeredByScheduleIdOrderByStartedAtDesc(String scheduleId);
}
