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
                                             String workflowId, String projectId) {
        Pageable pageable = PageRequest.of(page, size);

        RunStatus runStatus = null;
        if (status != null && !status.isBlank()) {
            runStatus = RunStatus.valueOf(status.toUpperCase());
        }

        if (workflowId != null && !workflowId.isBlank()) {
            return runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId, pageable);
        } else if (projectId != null && !projectId.isBlank()) {
            if (runStatus != null) {
                return runRepository.findByStatusAndProjectIdOrderByStartedAtDesc(runStatus, projectId, pageable);
            } else {
                return runRepository.findByProjectIdOrderByStartedAtDesc(projectId, pageable);
            }
        } else if (runStatus != null) {
            return runRepository.findByStatusOrderByStartedAtDesc(runStatus, pageable);
        } else {
            return runRepository.findByOrderByStartedAtDesc(pageable);
        }
    }

    @Transactional(readOnly = true)
    public WorkflowRun findById(String id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution", id));
    }
}
