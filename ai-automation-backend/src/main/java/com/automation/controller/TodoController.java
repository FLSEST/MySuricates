package com.automation.controller;

import com.automation.dto.request.BulkUpdateTodoStatusRequest;
import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.ExportTodoRequest;
import com.automation.dto.request.UpdateTodoPlanningRequest;
import com.automation.dto.request.UpdateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoExportResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoOverviewResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.mapper.TodoMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.goal.TodoPlanningService;
import com.automation.service.todo.TodoHygieneService;
import com.automation.service.todo.TodoOverviewService;
import com.automation.service.todo.TodoResolutionService;
import com.automation.service.todo.TodoService;
import com.automation.service.todo.TodoSimilarityService;
import com.automation.service.todo.TodoTrackerExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;
    private final TodoResolutionService todoResolutionService;
    private final TodoTrackerExportService todoTrackerExportService;
    private final TodoOverviewService todoOverviewService;
    private final TodoHygieneService todoHygieneService;
    private final TodoMapper todoMapper;
    private final WorkflowMapper workflowMapper;
    private final TodoSimilarityService similarityService;
    private final TodoPlanningService planningService;

    @GetMapping
    public PageResponse<TodoResponse> listTodos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String goalId) {
        return todoService.findAll(page, size, projectId, status, priority, source, tag, q, goalId);
    }

    @PostMapping
    public ResponseEntity<TodoResponse> createTodo(@Valid @RequestBody CreateTodoRequest request) {
        var todo = todoService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(todoMapper.toResponse(todo));
    }

    @GetMapping("/{id}")
    public TodoResponse getTodo(@PathVariable String id) {
        return todoService.findByIdResponse(id);
    }

    @PutMapping("/{id}")
    public TodoResponse updateTodo(@PathVariable String id, @RequestBody UpdateTodoRequest request) {
        return todoService.updateAndReturn(id, request);
    }

    /** Ziel, Rang und Einschaetzung (Komplexitaet, Effort, Begruendung, offene Frage) – TODO dc0566f8. */
    @PatchMapping("/{id}/planning")
    public TodoResponse updateTodoPlanning(@PathVariable String id, @RequestBody UpdateTodoPlanningRequest request) {
        return planningService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public TodoResponse updateTodoStatus(@PathVariable String id,
                                         @Valid @RequestBody UpdateTodoStatusRequest request) {
        return todoService.updateStatusAndReturn(id, request);
    }

    @PostMapping("/{id}/resolve")
    public com.automation.dto.response.WorkflowRunResponse resolveTodo(@PathVariable String id) {
        return workflowMapper.toRunResponse(todoResolutionService.resolve(id));
    }

    @PostMapping("/{id}/export")
    @RequiresModule(ModuleCatalog.INTEGRATIONS)
    public TodoExportResponse exportTodo(@PathVariable String id,
                                         @RequestBody(required = false) ExportTodoRequest request) {
        var ref = todoTrackerExportService.exportToTracker(id,
                request != null ? request.provider() : null,
                request != null ? request.target() : null);
        return new TodoExportResponse(ref.referenceType(), ref.referenceId(), ref.url());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTodo(@PathVariable String id) {
        todoService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/count")
    public TodoCountResponse countTodos(@RequestParam(required = false) String projectId) {
        return todoService.count(projectId);
    }

    /** Cross-project cockpit overview: per-project rollups + a global "Braucht dich" lane. */
    @GetMapping("/overview")
    public TodoOverviewResponse overview() {
        return todoOverviewService.getOverview();
    }

    /** Bulk status change (e.g. archive selected todos). Returns {@code {"updated": n}}. */
    @PostMapping("/bulk-status")
    public ResponseEntity<Map<String, Integer>> bulkUpdateStatus(@Valid @RequestBody BulkUpdateTodoStatusRequest request) {
        int updated = todoService.bulkUpdateStatus(request.ids(), request.status());
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    /**
     * Archive stale auto-todos now (cockpit "Verstaubtes archivieren"). Optional {@code projectId}
     * scopes it to one project; omit to archive across all. Returns {@code {"archived": n}}.
     */
    @PostMapping("/archive-stale")
    public ResponseEntity<Map<String, Integer>> archiveStale(@RequestParam(required = false) String projectId) {
        int archived = todoHygieneService.archiveStaleNow(projectId);
        return ResponseEntity.ok(Map.of("archived", archived));
    }

    /**
     * Check for similar TODOs before creating a new one.
     * Frontend calls this to offer merge/link options to the user.
     */
    @GetMapping("/similar")
    public List<SimilarTodoResponse> findSimilar(
            @RequestParam String projectId,
            @RequestParam String title) {
        return similarityService.findSimilar(projectId, title, null).stream()
                .map(s -> new SimilarTodoResponse(
                        s.todo().getId(),
                        s.todo().getTitle(),
                        s.todo().getPriority().name(),
                        s.keywordScore(),
                        s.similarity().name()))
                .toList();
    }

    record SimilarTodoResponse(String id, String title, String priority, double score, String similarity) {}
}
