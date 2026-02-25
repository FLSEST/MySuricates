package com.automation.repository;

import com.automation.entity.ChainRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChainRunRepository extends JpaRepository<ChainRun, String> {

    long countByChainId(String chainId);

    List<ChainRun> findByChainIdOrderByStartedAtDesc(String chainId);
}
