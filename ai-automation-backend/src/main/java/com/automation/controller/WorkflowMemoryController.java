package com.automation.controller;

import com.automation.dto.request.ExtractMemoryRequest;
import com.automation.dto.request.UpdateMemoryRequest;
import com.automation.dto.response.WorkflowMemoryResponse;
import com.automation.mapper.WorkflowMemoryMapper;
import com.automation.service.memory.WorkflowMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflows/{workflowId}/memory")
@RequiredArgsConstructor
@Slf4j
public class WorkflowMemoryController {

    private final WorkflowMemoryService memoryService;
    private final WorkflowMemoryMapper memoryMapper;

    @GetMapping
    public List<WorkflowMemoryResponse> list(@PathVariable String workflowId) {
        return memoryMapper.toResponseList(memoryService.findByWorkflowId(workflowId));
    }

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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Knowledge extraction failed for workflow {}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Knowledge extraction failed: " + e.getMessage()));
        }
    }

    @PutMapping("/{memoryId}")
    public WorkflowMemoryResponse update(
            @PathVariable String workflowId,
            @PathVariable String memoryId,
            @RequestBody UpdateMemoryRequest request) {
        var memory = memoryService.update(workflowId, memoryId,
                request.title(), request.content());
        return memoryMapper.toResponse(memory);
    }

    @PutMapping("/{memoryId}/activate")
    public ResponseEntity<Void> activate(
            @PathVariable String workflowId,
            @PathVariable String memoryId) {
        memoryService.activate(workflowId, memoryId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(
            @PathVariable String workflowId,
            @PathVariable String memoryId) {
        memoryService.delete(workflowId, memoryId);
        return ResponseEntity.noContent().build();
    }
}
