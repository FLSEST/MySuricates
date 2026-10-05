package com.automation.controller;

import com.automation.dto.request.ExtractMemoryRequest;
import com.automation.dto.request.UpdateMemoryRequest;
import com.automation.dto.response.WorkflowMemoryResponse;
import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorResponse;
import com.automation.mapper.WorkflowMemoryMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.memory.WorkflowMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Workflow memory: knowledge distilled from a workflow's runs, of which one entry can be active per workflow. */
@RequiresModule(ModuleCatalog.MEMORY)
@RestController
@RequestMapping("/api/v1/workflows/{workflowId}/memory")
@RequiredArgsConstructor
@Slf4j
public class WorkflowMemoryController {

    private final WorkflowMemoryService memoryService;
    private final WorkflowMemoryMapper memoryMapper;

    /** Lists the workflow's memory entries, newest first. */
    @GetMapping
    public List<WorkflowMemoryResponse> list(@PathVariable String workflowId) {
        return memoryMapper.toResponseList(memoryService.findByWorkflowId(workflowId));
    }

    /**
     * Has an AI summarize the steps of the given runs into a new, inactive memory entry and answers {@code 201}.
     * Without {@code runIds} the workflow's latest runs are used; {@code provider} defaults to {@code CLAUDE}.
     * No runs or no steps: {@code 422}.
     */
    @PostMapping("/extract")
    public ResponseEntity<?> extract(
            @PathVariable String workflowId,
            @RequestBody ExtractMemoryRequest request) {
        try {
            var memory = memoryService.extractKnowledge(
                    workflowId,
                    request.runIds(),
                    request.provider() != null ? request.provider() : "CLAUDE");
            return ResponseEntity.status(HttpStatus.CREATED).body(memoryMapper.toResponse(memory));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.from(e, ErrorCode.INVALID_ARGUMENT));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(ErrorResponse.from(e, ErrorCode.STATE_CONFLICT));
        } catch (IOException e) {
            log.error("Knowledge extraction failed for workflow {}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Knowledge extraction failed: " + e.getMessage()));
        }
    }

    /** Changes title and/or content of a memory entry; a field left out stays as it is. */
    @PutMapping("/{memoryId}")
    public WorkflowMemoryResponse update(
            @PathVariable String workflowId,
            @PathVariable String memoryId,
            @RequestBody UpdateMemoryRequest request) {
        var memory = memoryService.update(workflowId, memoryId,
                request.title(), request.content());
        return memoryMapper.toResponse(memory);
    }

    /**
     * Makes this memory entry the workflow's active one and deactivates all others ({@code 204}).
     * The active entry is written into the project before each run so the executing agent can read it.
     */
    @PutMapping("/{memoryId}/activate")
    public ResponseEntity<Void> activate(
            @PathVariable String workflowId,
            @PathVariable String memoryId) {
        memoryService.activate(workflowId, memoryId);
        return ResponseEntity.noContent().build();
    }

    /** Deletes a memory entry ({@code 204}). */
    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(
            @PathVariable String workflowId,
            @PathVariable String memoryId) {
        memoryService.delete(workflowId, memoryId);
        return ResponseEntity.noContent().build();
    }
}
