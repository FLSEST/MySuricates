package com.automation.repository;

import com.automation.entity.WorkflowVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, String> {

    Page<WorkflowVersion> findByWorkflowIdOrderByVersionNumberDesc(String workflowId, Pageable pageable);

    Optional<WorkflowVersion> findTopByWorkflowIdOrderByVersionNumberDesc(String workflowId);

    int countByWorkflowId(String workflowId);

    Optional<WorkflowVersion> findTopByWorkflowIdAndDefinitionHashOrderByVersionNumberDesc(String workflowId, String definitionHash);

    @Query("select coalesce(max(v.versionNumber), 0) from WorkflowVersion v where v.workflowId = :workflowId")
    int maxVersionNumber(@Param("workflowId") String workflowId);

    @Query("select v.id from WorkflowVersion v where v.definitionHash is null")
    List<String> findIdsWithoutDefinitionHash();

    /** Id and number of the given versions, without their snapshots — one query for a page of runs. */
    @Query("select v.id, v.versionNumber from WorkflowVersion v where v.id in :ids")
    List<Object[]> findVersionNumbers(@Param("ids") Collection<String> ids);
}
