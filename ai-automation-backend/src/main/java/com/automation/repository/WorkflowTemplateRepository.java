package com.automation.repository;

import com.automation.entity.WorkflowTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, String> {

    List<WorkflowTemplate> findByBuiltInTrue();
    long countByBuiltInTrue();

    List<WorkflowTemplate> findByBuiltInFalse();

    List<WorkflowTemplate> findAllByOrderBySortOrderAsc();
}
