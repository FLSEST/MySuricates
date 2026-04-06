package com.automation.service.execution;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExecutionQueryService {

    private final WorkflowRunRepository runRepository;

    @Transactional(readOnly = true)
    public Page<WorkflowRun> listExecutions(int page, int size, String status,
                                             String workflowId, String projectId, String search) {
        Pageable pageable = PageRequest.of(page, size);

        RunStatus runStatus = null;
        if (status != null && !status.isBlank()) {
            runStatus = RunStatus.valueOf(status.toUpperCase());
        }

        String wfId = (workflowId != null && !workflowId.isBlank()) ? workflowId : null;
        String projId = (projectId != null && !projectId.isBlank()) ? projectId : null;
        String q = (search != null && !search.isBlank()) ? search.trim() : null;

        return runRepository.findFiltered(runStatus, wfId, projId, q, pageable);
    }

    @Transactional(readOnly = true)
    public WorkflowRun findById(String id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution", id));
    }
}
