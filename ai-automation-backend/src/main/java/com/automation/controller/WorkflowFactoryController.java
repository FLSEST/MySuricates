package com.automation.controller;

import com.automation.dto.request.WorkflowFactoryRequest;
import com.automation.dto.response.WorkflowFactoryResult;
import com.automation.entity.Workflow;
import com.automation.mapper.WorkflowMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.factory.FactoryTopicCatalog;
import com.automation.service.factory.WorkflowFactoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RequiresModule(ModuleCatalog.FACTORY)
@RestController
@RequestMapping("/api/v1/factory")
@RequiredArgsConstructor
public class WorkflowFactoryController {

    private final WorkflowFactoryService factoryService;
    private final WorkflowMapper workflowMapper;

    /**
     * Generate a workflow from a topic (preview only, no testing).
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody WorkflowFactoryRequest request) throws IOException {
        Workflow workflow = factoryService.generateWorkflow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }

    /**
     * Full factory pipeline: generate → deploy to test → verify → optionally promote.
     */
    @PostMapping("/run")
    public ResponseEntity<WorkflowFactoryResult> run(@Valid @RequestBody WorkflowFactoryRequest request) {
        WorkflowFactoryResult result = factoryService.runFactory(request);
        return ResponseEntity.ok(result);
    }

    /**
     * Verify an existing workflow on the test instance.
     */
    @PostMapping("/verify/{id}")
    public ResponseEntity<WorkflowFactoryResult> verify(@PathVariable String id) {
        WorkflowFactoryResult result = factoryService.verifyWorkflow(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Promote a verified workflow as a template.
     */
    @PostMapping("/promote/{id}")
    public ResponseEntity<WorkflowFactoryResult> promote(@PathVariable String id) {
        WorkflowFactoryResult result = factoryService.promoteToTemplate(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Get available topic categories for the factory.
     */
    @GetMapping("/topics")
    public List<Map<String, String>> getTopics() {
        return FactoryTopicCatalog.getTopicCategories();
    }
}
