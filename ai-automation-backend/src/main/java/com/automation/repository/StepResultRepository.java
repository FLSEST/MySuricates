package com.automation.repository;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StepResultRepository extends JpaRepository<StepResult, String> {

    List<StepResult> findByWorkflowRunId(String workflowRunId);

    List<StepResult> findByWorkflowRunIdOrderByStepIndexAsc(String workflowRunId);

    List<StepResult> findByStatus(StepStatus status);
}
