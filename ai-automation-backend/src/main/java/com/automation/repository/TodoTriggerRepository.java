package com.automation.repository;

import com.automation.entity.TodoTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TodoTriggerRepository extends JpaRepository<TodoTrigger, String> {

    List<TodoTrigger> findByEnabledTrue();
    long countByEnabledTrue();

    List<TodoTrigger> findByTargetWorkflowId(String workflowId);
}
