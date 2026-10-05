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

/** TODOs: list, create, edit and close them, start their fix workflow, export them to a tracker, find duplicates. */
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

    /**
     * Lists TODOs page by page, most urgent first, filtered by project, status, priority, source, tag, text or goal.
     * ARCHIVED TODOs only appear when {@code status} names them, an unknown status, priority or source returns 400,
     * and {@code goalId=none} selects TODOs without a goal.
     */
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

    /**
     * Creates a TODO and returns it with 201; only {@code title} is required.
     * Defaults are OPEN, MEDIUM and source USER; an unknown {@code projectId} or {@code linkedWorkflowId} returns 404,
     * and a new OPEN TODO can start matching TODO triggers.
     */
    @PostMapping
    public ResponseEntity<TodoResponse> createTodo(@Valid @RequestBody CreateTodoRequest request) {
        var todo = todoService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(todoMapper.toResponse(todo));
    }

    /** Returns one TODO. */
    @GetMapping("/{id}")
    public TodoResponse getTodo(@PathVariable String id) {
        return todoService.findByIdResponse(id);
    }

    /**
     * Updates the fields sent in the body and leaves the others unchanged.
     * An empty {@code dueDate} or {@code linkedWorkflowId} clears it; an unknown project or workflow id returns 404.
     */
    @PutMapping("/{id}")
    public TodoResponse updateTodo(@PathVariable String id, @RequestBody UpdateTodoRequest request) {
        return todoService.updateAndReturn(id, request);
    }

    /** Sets a TODO's goal, rank and assessment (complexity, effort, reasoning, open question) – TODO dc0566f8. */
    @PatchMapping("/{id}/planning")
    @RequiresModule(ModuleCatalog.GOALS)
    public TodoResponse updateTodoPlanning(@PathVariable String id, @RequestBody UpdateTodoPlanningRequest request) {
        return planningService.update(id, request);
    }

    /** Sets the status of a TODO ({@code status} required), optionally recording the {@code sessionId} that changed it. */
    @PatchMapping("/{id}/status")
    public TodoResponse updateTodoStatus(@PathVariable String id,
                                         @Valid @RequestBody UpdateTodoStatusRequest request) {
        return todoService.updateStatusAndReturn(id, request);
    }

    /**
     * Starts the TODO's linked fix workflow and sets the TODO to IN_PROGRESS.
     * Returns 400 without a linked workflow and 409 when the TODO is DONE or its last fix run is still running.
     */
    @PostMapping("/{id}/resolve")
    public com.automation.dto.response.WorkflowRunResponse resolveTodo(@PathVariable String id) {
        return workflowMapper.toRunResponse(todoResolutionService.resolve(id));
    }

    /**
     * Creates a task for the TODO in an external tracker and returns its reference; a TODO already exported returns the existing link.
     * Provider and target come from the body or the project's {@code taskExport} setting.
     */
    @PostMapping("/{id}/export")
    @RequiresModule(ModuleCatalog.INTEGRATIONS)
    public TodoExportResponse exportTodo(@PathVariable String id,
                                         @RequestBody(required = false) ExportTodoRequest request) {
        var ref = todoTrackerExportService.exportToTracker(id,
                request != null ? request.provider() : null,
                request != null ? request.target() : null);
        return new TodoExportResponse(ref.referenceType(), ref.referenceId(), ref.url());
    }

    /** Deletes a TODO permanently. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTodo(@PathVariable String id) {
        todoService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Counts TODOs per status, optionally only those of {@code projectId}. */
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
