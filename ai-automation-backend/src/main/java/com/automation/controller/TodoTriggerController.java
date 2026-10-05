package com.automation.controller;

import com.automation.dto.request.CreateTodoTriggerRequest;
import com.automation.dto.response.TodoTriggerResponse;
import com.automation.mapper.TodoTriggerMapper;
import com.automation.service.trigger.TodoTriggerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** TODO triggers: start a fix workflow automatically when an open TODO matches tags, project, source and priority. */
@RestController
@RequestMapping("/api/v1/todo-triggers")
@RequiredArgsConstructor
public class TodoTriggerController {

    private final TodoTriggerService service;
    private final TodoTriggerMapper mapper;

    /** Lists all TODO triggers. */
    @GetMapping
    public List<TodoTriggerResponse> listAll() {
        return mapper.toResponseList(service.findAll());
    }

    /** Returns one TODO trigger. */
    @GetMapping("/{id}")
    public TodoTriggerResponse getById(@PathVariable String id) {
        return mapper.toResponse(service.findById(id));
    }

    /** Lists the TODO triggers that start the given workflow. */
    @GetMapping("/workflow/{workflowId}")
    public List<TodoTriggerResponse> getByTargetWorkflow(@PathVariable String workflowId) {
        return mapper.toResponseList(service.findByTargetWorkflowId(workflowId));
    }

    /**
     * Creates a TODO trigger and returns it with 201; {@code tagPattern} and {@code targetWorkflowId} are required.
     * {@code sourceFilter} and {@code minPriority} ignore case, other values return 400; with {@code processBacklog}
     * (default on) the periodic poll also picks up existing matching TODOs.
     */
    @PostMapping
    public ResponseEntity<TodoTriggerResponse> create(
            @Valid @RequestBody CreateTodoTriggerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.create(request)));
    }

    /** Enables a TODO trigger. */
    @PostMapping("/{id}/enable")
    public TodoTriggerResponse enable(@PathVariable String id) {
        return mapper.toResponse(service.enable(id));
    }

    /** Disables a TODO trigger; it then ignores new and existing TODOs. */
    @PostMapping("/{id}/disable")
    public TodoTriggerResponse disable(@PathVariable String id) {
        return mapper.toResponse(service.disable(id));
    }

    /** Deletes a TODO trigger. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
