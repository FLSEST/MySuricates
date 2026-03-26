package com.automation.repository;

import com.automation.entity.WorkflowMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowMemoryRepository extends JpaRepository<WorkflowMemory, String> {

    List<WorkflowMemory> findByWorkflowIdOrderByCreatedAtDesc(String workflowId);

    Optional<WorkflowMemory> findByWorkflowIdAndActiveTrue(String workflowId);

    List<WorkflowMemory> findByActiveTrueOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE WorkflowMemory m SET m.active = false WHERE m.workflowId = :workflowId AND m.active = true")
    void deactivateAllForWorkflow(@Param("workflowId") String workflowId);
}
