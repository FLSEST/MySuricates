package com.automation.repository;

import com.automation.entity.WorkflowChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkflowChainRepository extends JpaRepository<WorkflowChain, String> {
}
