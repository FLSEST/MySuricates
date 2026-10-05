package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.conversion.DialogWorkflowSuggestion;
import com.automation.service.dialog.DialogConversionService;
import com.automation.service.dialog.DialogSessionScanner;
import com.automation.service.dialog.DialogSessionScanner.DialogSessionInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Dialog import: turns recorded Claude Code sessions (JSONL files under {@code ~/.claude/projects}) into workflows. */
@RequiresModule(ModuleCatalog.DIALOG_IMPORT)
@RestController
@RequestMapping("/api/v1/dialog")
@RequiredArgsConstructor
@Slf4j
public class DialogController {

    private final DialogSessionScanner scanner;
    private final DialogConversionService conversionService;

    private static final Path CLAUDE_PROJECTS_BASE =
            Path.of(System.getProperty("user.home"), ".claude", "projects");

    /**
     * Counts the sessions per project directory (fast, without reading the files).
     */
    @GetMapping("/sessions/count")
    public Map<String, Integer> countSessionsPerProject() {
        return scanner.countPerProject();
    }

    /**
     * Lists sessions with their metadata.
     * {@code limit} (default 50) is capped at 2000 with {@code projectPath} (one project, all sessions visible)
     * and at 500 without it (for "Other Sessions").
     */
    @GetMapping("/sessions")
    public List<DialogSessionInfo> listSessions(
            @RequestParam(required = false) String projectPath,
            @RequestParam(defaultValue = "50") int limit) {
        int cap = projectPath != null ? 2000 : 500;
        return scanner.scanSessions(projectPath, Math.min(limit, cap));
    }

    /**
     * Returns all known project paths (for the filter dropdown).
     */
    @GetMapping("/projects")
    public List<String> listProjectPaths() {
        return scanner.listProjectPaths();
    }

    /**
     * Analyzes a session with AI and returns a workflow suggestion.
     * {@code filePath} must be a {@code .jsonl} file under {@code ~/.claude/projects}; any other path is 400.
     */
    @PostMapping("/sessions/analyze")
    public DialogWorkflowSuggestion analyzeSession(
            @Valid @RequestBody AnalyzeDialogRequest request) throws IOException {
        validateJsonlPath(request.filePath());
        return conversionService.suggestFromSession(request.filePath());
    }

    /**
     * Creates a workflow from a suggestion (with optional user adjustments).
     * {@code projectId} is required; the answer is 200 with the new workflow.
     */
    @PostMapping("/sessions/create-workflow")
    public ResponseEntity<WorkflowResponse> createWorkflow(
            @Valid @RequestBody CreateFromDialogRequest request) {
        WorkflowResponse created = conversionService.createFromSuggestion(
                request.suggestion(), request.projectId(), request.name());
        return ResponseEntity.ok(created);
    }

    // ---- Request Records ----

    public record AnalyzeDialogRequest(
            @NotBlank String filePath
    ) {}

    public record CreateFromDialogRequest(
            @NotBlank String projectId,
            String name,
            DialogWorkflowSuggestion suggestion
    ) {}

    // ---- Security ----

    private void validateJsonlPath(String filePath) {
        if (filePath == null) throw new IllegalArgumentException("filePath darf nicht null sein");
        Path resolved = Path.of(filePath).toAbsolutePath().normalize();
        Path allowed = CLAUDE_PROJECTS_BASE.toAbsolutePath().normalize();
        if (!resolved.startsWith(allowed) || !resolved.toString().endsWith(".jsonl")) {
            throw new IllegalArgumentException("Ungültiger JSONL-Pfad: " + filePath);
        }
    }
}
