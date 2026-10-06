package com.automation.repository;

import com.automation.entity.DeadLetterEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterEntry, String> {
    Page<DeadLetterEntry> findByStatusOrderByLastFailureAtDesc(DeadLetterEntry.Status status, Pageable pageable);
    Page<DeadLetterEntry> findAllByOrderByLastFailureAtDesc(Pageable pageable);

    /**
     * Removes the entries of a deleted run.
     *
     * <p>{@code workflow_run_id} is only a text column without a foreign key.
     * If the entry stayed, its retry button would become a dummy:
     * {@code DeadLetterController.retry} no longer finds the original run,
     * but still marks the entry as RETRIED.
     *
     * @return number of deleted entries
     */
    @Modifying
    int deleteByWorkflowRunId(String workflowRunId);
}
