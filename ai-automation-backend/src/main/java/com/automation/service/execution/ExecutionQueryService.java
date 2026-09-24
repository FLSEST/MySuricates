package com.automation.service.execution;

import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.util.EnumParameter;
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
    private final WorkflowMapper workflowMapper;

    @Transactional(readOnly = true)
    public Page<WorkflowRun> listExecutions(int page, int size, String status,
                                             String workflowId, String projectId, String search) {
        Pageable pageable = PageRequest.of(page, size);

        RunStatus runStatus = EnumParameter.parseOptional(RunStatus.class, "status", status);

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

    /**
     * Wie {@link #listExecutions}, aber inklusive DTO-Mapping innerhalb der Transaktion.
     * OSIV ist deaktiviert — Lazy-Associations (workflow, steps) dürfen nur hier geladen werden.
     */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowRunListItemResponse> listExecutionResponses(int page, int size, String status,
                                                                            String workflowId, String projectId, String search) {
        Page<WorkflowRun> result = listExecutions(page, size, status, workflowId, projectId, search);
        return new PageResponse<>(
                workflowMapper.toRunListItemsWithOwners(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public WorkflowRunResponse getExecutionResponse(String id) {
        return workflowMapper.toRunResponse(findById(id));
    }

    /**
     * Runs eines Workflows, absteigend nach Startzeit — Listenzeilen ohne Schritte wie bei
     * {@code /executions} (TODO 7fd9f654). Wer die Schritte eines Runs zeigt, laedt ihn ueber
     * {@link #getRunForWorkflow}.
     */
    @Transactional(readOnly = true)
    public PageResponse<WorkflowRunListItemResponse> listRunsForWorkflow(String workflowId, int page, int size) {
        Page<WorkflowRun> result = runRepository.findByWorkflowIdOrderByStartedAtDesc(
                workflowId, PageRequest.of(page, size));
        return new PageResponse<>(
                workflowMapper.toRunListItemsWithOwners(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** Einzelner Run für die Workflow-Detailansicht ({@code 404} mit "WorkflowRun"-Wortlaut). */
    @Transactional(readOnly = true)
    public WorkflowRunResponse getRunForWorkflow(String runId) {
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));
        return workflowMapper.toRunResponse(run);
    }

    public boolean hasFailedRuns(String workflowId) {
        return runRepository.existsByWorkflowIdAndStatus(workflowId, RunStatus.FAILED);
    }
}
