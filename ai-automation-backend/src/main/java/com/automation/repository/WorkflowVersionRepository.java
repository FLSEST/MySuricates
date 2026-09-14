package com.automation.repository;

import com.automation.entity.WorkflowVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, String> {

    Page<WorkflowVersion> findByWorkflowIdOrderByVersionNumberDesc(String workflowId, Pageable pageable);

    Optional<WorkflowVersion> findTopByWorkflowIdOrderByVersionNumberDesc(String workflowId);

    int countByWorkflowId(String workflowId);

    Optional<WorkflowVersion> findTopByWorkflowIdAndDefinitionHashOrderByVersionNumberDesc(String workflowId, String definitionHash);

    @Query("select v.id from WorkflowVersion v where v.definitionHash is null")
    List<String> findIdsWithoutDefinitionHash();
}
