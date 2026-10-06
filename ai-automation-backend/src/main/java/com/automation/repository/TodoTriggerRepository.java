package com.automation.repository;

import com.automation.dto.projection.TodoTriggerMatchRow;
import com.automation.entity.TodoTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TodoTriggerRepository extends JpaRepository<TodoTrigger, String> {

    List<TodoTrigger> findByEnabledTrue();
    long countByEnabledTrue();

    List<TodoTrigger> findByTargetWorkflowId(String workflowId);

    /** Active triggers as lean rows, without loading the target workflow (TODO c6ea8f5c). */
    @Query("SELECT new com.automation.dto.projection.TodoTriggerMatchRow(t.id, t.tagPattern, t.projectId, "
         + "t.sourceFilter, t.minPriority, t.targetWorkflow.id, t.description) FROM TodoTrigger t WHERE t.enabled = true")
    List<TodoTriggerMatchRow> findActiveMatchRows();
}
