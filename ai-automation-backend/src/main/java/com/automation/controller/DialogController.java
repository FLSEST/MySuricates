package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
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
     * Zählt Sessions pro Projekt-Verzeichnis (schnell, kein File-Lesen).
     */
    @GetMapping("/sessions/count")
    public Map<String, Integer> countSessionsPerProject() {
        return scanner.countPerProject();
    }

    /**
     * Listet Sessions mit Metadaten.
     * Mit projectPath: hohes Limit (einzelnes Projekt, alle Sessions sichtbar).
     * Ohne projectPath: konservativeres Limit (für "Other Sessions").
     */
    @GetMapping("/sessions")
    public List<DialogSessionInfo> listSessions(
            @RequestParam(required = false) String projectPath,
            @RequestParam(defaultValue = "50") int limit) {
        int cap = projectPath != null ? 2000 : 500;
        return scanner.scanSessions(projectPath, Math.min(limit, cap));
    }

    /**
     * Gibt alle bekannten Projekt-Pfade zurück (für Filter-Dropdown).
     */
    @GetMapping("/projects")
    public List<String> listProjectPaths() {
        return scanner.listProjectPaths();
    }

    /**
     * Analysiert eine Session mit KI und liefert eine Workflow-Suggestion.
     */
    @PostMapping("/sessions/analyze")
    public DialogWorkflowSuggestion analyzeSession(
            @Valid @RequestBody AnalyzeDialogRequest request) throws IOException {
        validateJsonlPath(request.filePath());
        return conversionService.suggestFromSession(request.filePath());
    }

    /**
     * Erstellt einen Workflow aus einer Suggestion (mit optionalen User-Anpassungen).
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
