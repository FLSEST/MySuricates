package com.automation.service.execution;

import com.automation.dto.response.PendingInputResponse;
import com.automation.executor.ApprovalRequest;
import com.automation.repository.WorkflowRunProjectionRepository;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import com.automation.service.workflow.WorkflowExecutionRegistry.PendingInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Läufe, die gerade auf eine Antwort warten, über alle Workflows hinweg (TODO 52d946cc).
 *
 * <p>Die Fragen liegen nur im Speicher ({@link WorkflowExecutionRegistry}); aus der DB kommen allein
 * die Workflow-Namen, in einer Abfrage. Wartet nichts, fällt gar keine an — die Seitenleiste pollt
 * diesen Weg von jeder Seite aus.
 */
@Service
@RequiredArgsConstructor
public class PendingInputService {

    private final WorkflowExecutionRegistry executionRegistry;
    private final WorkflowRunProjectionRepository runProjections;

    @Transactional(readOnly = true)
    public List<PendingInputResponse> listPendingInputs() {
        List<PendingInput> pending = executionRegistry.listPendingInputs();
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<String, WorkflowRef> workflowByRun = new HashMap<>();
        for (Object[] row : runProjections.findWorkflowRefsByRunIds(pending.stream().map(PendingInput::runId).toList())) {
            workflowByRun.put((String) row[0], new WorkflowRef((String) row[1], (String) row[2]));
        }
        return pending.stream().map(p -> toResponse(p, workflowByRun.get(p.runId()))).toList();
    }

    private record WorkflowRef(String id, String name) {}

    private static PendingInputResponse toResponse(PendingInput pending, WorkflowRef workflow) {
        ApprovalRequest request = pending.request();
        Instant timeoutAt = request.getTimeoutMinutes() == null
                ? null
                : request.getRequestedAt().plusSeconds(request.getTimeoutMinutes() * 60L);
        return new PendingInputResponse(
                pending.runId(),
                workflow != null ? workflow.id() : null,
                workflow != null ? workflow.name() : null,
                request.getType(),
                request.getBlockId(),
                request.getQuestion(),
                request.getOptions() != null ? request.getOptions() : List.of(),
                request.getRejectOptions(),
                request.getStepIndex(),
                request.getRequestedAt(),
                timeoutAt,
                request.getFields() != null && !request.getFields().isEmpty() ? request.getFields() : null);
    }
}
