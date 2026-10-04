package com.automation.service.execution;

import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Write-side companion of {@link ExecutionQueryService}: retry, resume and run notes.
 * Keeps repository access and DTO mapping out of {@code ExecutionController} (layer rule:
 * Controller → Service → Repository). Transactions are short — the executor only persists the
 * new run and hands it to its own thread.
 */
@Service
@RequiredArgsConstructor
public class ExecutionCommandService {

    private final ExecutionQueryService executionQueryService;
    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final WorkflowMapper workflowMapper;

    /**
     * Starts a fresh run of the same workflow with the original input context.
     *
     * <p>Deliberately not {@code @Transactional} (same as {@code POST /workflows/{id}/execute}): the
     * executor hands the new run to its thread right away, and inside a surrounding transaction that
     * thread would start before the run row is committed — with pool-size 1 it then waits for the
     * request's connection, and the client may ask for a run that is not visible yet (TODO fe06ebf9).
     * The lookups below run in their own short read transactions; the workflow is loaded eagerly.
     */
    public WorkflowRunResponse retry(String runId) {
        WorkflowRun run = executionQueryService.findById(runId);
        var workflow = workflowService.findByIdEager(run.getWorkflow().getId());
        return workflowMapper.toRunResponse(workflowExecutor.execute(workflow, run.getInputContext(), TriggerSource.RETRY));
    }

    /**
     * Not {@code @Transactional} for the same reason as {@link #retry}: the executor commits its own short
     * transaction before the run thread starts. The response is read afresh (own read transaction) —
     * mapping walks the run's lazy steps.
     */
    public WorkflowRunResponse resumeFromStep(String runId) {
        WorkflowRun started = workflowExecutor.resumeFromStep(runId, TriggerSource.RESUME);
        return executionQueryService.getExecutionResponse(started.getId());
    }

    /** Stores free-text notes in the run metadata; blank notes remove the entry. */
    @Transactional
    public WorkflowRunResponse updateNotes(String runId, String notes) {
        WorkflowRun run = executionQueryService.findById(runId);
        Map<String, Object> metadata = run.getMetadata() != null ? new HashMap<>(run.getMetadata()) : new HashMap<>();
        if (notes != null && !notes.isBlank()) {
            metadata.put("notes", notes);
        } else {
            metadata.remove("notes");
        }
        run.setMetadata(metadata);
        runRepository.save(run);
        return workflowMapper.toRunResponse(run);
    }
}
