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

@RestController
@RequestMapping("/api/v1/completion-triggers")
@RequiredArgsConstructor
public class CompletionTriggerController {

    private final CompletionTriggerService service;
    private final CompletionTriggerMapper mapper;

    @GetMapping
    public List<CompletionTriggerResponse> listAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public CompletionTriggerResponse getById(@PathVariable String id) {
        return mapper.toResponse(service.findById(id));
    }

    @GetMapping("/workflow/{workflowId}/as-source")
    public List<CompletionTriggerResponse> getBySourceWorkflow(@PathVariable String workflowId) {
        return mapper.toResponseList(service.findBySourceWorkflowId(workflowId));
    }

    @GetMapping("/workflow/{workflowId}/as-target")
    public List<CompletionTriggerResponse> getByTargetWorkflow(@PathVariable String workflowId) {
        return mapper.toResponseList(service.findByTargetWorkflowId(workflowId));
    }

    @PostMapping
    public ResponseEntity<CompletionTriggerResponse> create(
            @Valid @RequestBody CreateCompletionTriggerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.create(request)));
    }

    @PostMapping("/{id}/enable")
    public CompletionTriggerResponse enable(@PathVariable String id) {
        return mapper.toResponse(service.enable(id));
    }

    @PostMapping("/{id}/disable")
    public CompletionTriggerResponse disable(@PathVariable String id) {
        return mapper.toResponse(service.disable(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
