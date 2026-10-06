package com.automation.controller;

import com.automation.dto.request.CreateCompletionTriggerRequest;
import com.automation.dto.response.CompletionTriggerResponse;
import com.automation.mapper.CompletionTriggerMapper;
import com.automation.service.trigger.CompletionTriggerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Completion triggers: start a target workflow automatically when a source workflow finishes. */
@RestController
@RequestMapping("/api/v1/completion-triggers")
@RequiredArgsConstructor
public class CompletionTriggerController {

    private final CompletionTriggerService service;
    private final CompletionTriggerMapper mapper;

    /** Lists all completion triggers. */
    @GetMapping
    public List<CompletionTriggerResponse> listAll() {
        return mapper.toResponseList(service.findAll());
    }

    /** Returns one completion trigger. */
    @GetMapping("/{id}")
    public CompletionTriggerResponse getById(@PathVariable String id) {
        return mapper.toResponse(service.findById(id));
    }

    /** Lists the triggers that fire when the given workflow finishes. */
    @GetMapping("/workflow/{workflowId}/as-source")
    public List<CompletionTriggerResponse> getBySourceWorkflow(@PathVariable String workflowId) {
        return mapper.toResponseList(service.findBySourceWorkflowId(workflowId));
    }

    /** Lists the triggers that start the given workflow. */
    @GetMapping("/workflow/{workflowId}/as-target")
    public List<CompletionTriggerResponse> getByTargetWorkflow(@PathVariable String workflowId) {
        return mapper.toResponseList(service.findByTargetWorkflowId(workflowId));
    }

    /**
     * Creates a completion trigger from source to target workflow and returns it with 201.
     * Both workflows must exist (404); a trigger that would start its own source or close a cycle is rejected with 400.
     */
    @PostMapping
    public ResponseEntity<CompletionTriggerResponse> create(
            @Valid @RequestBody CreateCompletionTriggerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.create(request)));
    }

    /** Enables a trigger; the cycle check runs again first. */
    @PostMapping("/{id}/enable")
    public CompletionTriggerResponse enable(@PathVariable String id) {
        return mapper.toResponse(service.enable(id));
    }

    /** Disables a trigger without deleting it. */
    @PostMapping("/{id}/disable")
    public CompletionTriggerResponse disable(@PathVariable String id) {
        return mapper.toResponse(service.disable(id));
    }

    /** Deletes a completion trigger; answers 204. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
