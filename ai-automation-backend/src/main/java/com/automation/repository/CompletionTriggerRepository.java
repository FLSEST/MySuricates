package com.automation.repository;

import com.automation.entity.CompletionTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompletionTriggerRepository extends JpaRepository<CompletionTrigger, String> {

    List<CompletionTrigger> findBySourceWorkflowId(String sourceWorkflowId);

    List<CompletionTrigger> findByTargetWorkflowId(String targetWorkflowId);

    List<CompletionTrigger> findBySourceWorkflowIdAndEnabledTrue(String sourceWorkflowId);

    boolean existsBySourceWorkflowIdAndTargetWorkflowId(String sourceId, String targetId);
}
