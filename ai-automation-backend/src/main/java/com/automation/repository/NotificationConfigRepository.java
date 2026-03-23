package com.automation.repository;

import com.automation.entity.NotificationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationConfigRepository extends JpaRepository<NotificationConfig, String> {

    List<NotificationConfig> findByWorkflowId(String workflowId);

    List<NotificationConfig> findByWorkflowIdIsNull();

    /**
     * Returns enabled configs that are either global (workflowId IS NULL)
     * or specific to the given workflow.
     */
    @Query("SELECT n FROM NotificationConfig n WHERE n.enabled = true AND (n.workflowId IS NULL OR n.workflowId = :workflowId)")
    List<NotificationConfig> findEnabledForWorkflow(@Param("workflowId") String workflowId);
}
