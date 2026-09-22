package com.automation.repository;

import com.automation.entity.WorkflowSealEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowSealEventRepository extends JpaRepository<WorkflowSealEvent, String> {

    List<WorkflowSealEvent> findByWorkflowIdOrderByOccurredAtDesc(String workflowId);

    /** Last entry of the whole chain — the chain spans all workflows, so a deletion cannot hide. */
    Optional<WorkflowSealEvent> findTopByOrderByOccurredAtDescIdDesc();

    List<WorkflowSealEvent> findAllByOrderByOccurredAtAscIdAsc();
}
