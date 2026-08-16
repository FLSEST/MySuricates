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
     * Raeumt die Eintraege eines geloeschten Runs weg.
     *
     * <p>{@code workflow_run_id} ist nur eine Textspalte ohne Fremdschluessel.
     * Bliebe der Eintrag stehen, wuerde sein Retry-Knopf zur Attrappe:
     * {@code DeadLetterController.retry} findet den Originallauf nicht mehr,
     * markiert den Eintrag aber trotzdem als RETRIED.
     *
     * @return Anzahl geloeschter Eintraege
     */
    @Modifying
    int deleteByWorkflowRunId(String workflowRunId);
}
