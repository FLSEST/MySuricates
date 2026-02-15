package com.automation.repository;

import com.automation.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    List<Workflow> findByProjectId(String projectId);

    List<Workflow> findByNameContainingIgnoreCase(String name);
}
