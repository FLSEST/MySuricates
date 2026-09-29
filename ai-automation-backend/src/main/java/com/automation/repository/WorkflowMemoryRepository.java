package com.automation.repository;

import com.automation.entity.WorkflowMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowMemoryRepository extends JpaRepository<WorkflowMemory, String> {

    List<WorkflowMemory> findByWorkflowIdOrderByCreatedAtDesc(String workflowId);

    Optional<WorkflowMemory> findByWorkflowIdAndActiveTrue(String workflowId);

    /**
     * Die aktiven Memories mehrerer Workflows in einer Abfrage — fuer den Wunsch-Prompt, der sonst
     * je Workflow des Projekts einzeln fragte (TODO 5fb7b1e9). Reihenfolge ist nicht festgelegt.
     */
    List<WorkflowMemory> findByWorkflowIdInAndActiveTrue(Collection<String> workflowIds);

    /** The five newest active memories — the page suggestions only show that many (TODO bdd11037). */
    List<WorkflowMemory> findTop5ByActiveTrueOrderByCreatedAtDesc();

    /** The eight newest active memories — the assistant's "Learnings &amp; Pitfalls" show that many (TODO 8c791a45). */
    List<WorkflowMemory> findTop8ByActiveTrueOrderByCreatedAtDesc();
    long countByActiveTrue();

    @Modifying
    @Query("UPDATE WorkflowMemory m SET m.active = false WHERE m.workflowId = :workflowId AND m.active = true")
    void deactivateAllForWorkflow(@Param("workflowId") String workflowId);
}
