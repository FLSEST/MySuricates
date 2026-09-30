package com.automation.controller;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.response.AssistantSessionDetail;
import com.automation.dto.response.AssistantSessionSummary;
import com.automation.event.AssistantSessionUpdatedEvent;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.AssistantService;
import com.automation.service.AssistantSessionService;
import com.automation.service.PageSuggestionService;
import com.automation.service.assistant.AssistantChatBroadcaster;
import com.automation.service.assistant.AssistantToolExecutor;
import com.automation.dto.PendingTool;
import com.automation.service.assistant.PendingToolRegistry;
import com.automation.service.assistant.ToolExecutionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiresModule(ModuleCatalog.ASSISTANT)
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantSessionService assistantSessionService;
    private final PageSuggestionService pageSuggestionService;
    private final AssistantChatBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;
    private final PendingToolRegistry pendingToolRegistry;
    private final AssistantToolExecutor toolExecutor;

    /**
     * Optional body for confirm/reject — carries sessionId so the result hits the right history, and the UI
     * {@code language} ({@code "en"}/{@code "de"}, TODO 81773d7b) the build check of a workflow tool answers in;
     * without it German, as before.
     */
    public record PendingToolAction(String sessionId, String language) {
        public PendingToolAction(String sessionId) {
            this(sessionId, null);
        }
    }

    @PostMapping("/chat")
    public AssistantChatResponse chat(@Valid @RequestBody AssistantChatRequest request) {
        // Cancel running suggestion refresh to free up CLI capacity for chat
        pageSuggestionService.cancelRunning();

        long startNanos = System.nanoTime();
        broadcaster.broadcastStatus(request.requestId(), "RECEIVED", null, startNanos);
        AssistantChatResponse response = assistantService.chat(request);
        long responseTimeMs = (System.nanoTime() - startNanos) / 1_000_000;
        response = response.withResponseTimeMs(responseTimeMs);

        String sessionId = request.sessionId();
        if (sessionId != null) {
            assistantSessionService.appendMessages(sessionId, request, response);
            String page = request.context() != null && request.context().page() != null
                    ? request.context().page() : "dashboard";
            eventPublisher.publishEvent(new AssistantSessionUpdatedEvent(page, request.language()));
        }

        return response;
    }

    @GetMapping("/sessions")
    public List<AssistantSessionSummary> listSessions() {
        return assistantSessionService.listRecentSessions();
    }

    @GetMapping("/sessions/{id}")
    public AssistantSessionDetail getSession(@PathVariable String id) {
        return assistantSessionService.getSession(id);
    }

    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable String id) {
        assistantSessionService.deleteSession(id);
    }

    /**
     * Executes a previously proposed tool after explicit user confirmation.
     * Returns 410 Gone if the tool has already been consumed or expired — the frontend treats
     * that as "already handled" and doesn't retry.
     */
    @PostMapping("/pending-tools/{id}/confirm")
    public ResponseEntity<ToolExecutionResult> confirmPendingTool(
            @PathVariable String id,
            @RequestBody(required = false) PendingToolAction body) {
        PendingTool tool = pendingToolRegistry.take(id).orElse(null);
        if (tool == null) {
            return ResponseEntity.status(410).build();
        }
        ToolExecutionResult result = toolExecutor.executeConfirmed(tool, body != null ? body.language() : null);
        persistResult(body, result);
        broadcaster.broadcastStatus(id, result.success() ? "TOOL_EXECUTED" : "TOOL_FAILED",
                result.message(), System.nanoTime());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/pending-tools/{id}/reject")
    public ResponseEntity<ToolExecutionResult> rejectPendingTool(
            @PathVariable String id,
            @RequestBody(required = false) PendingToolAction body) {
        PendingTool tool = pendingToolRegistry.take(id).orElse(null);
        if (tool == null) {
            return ResponseEntity.status(410).build();
        }
        ToolExecutionResult result = ToolExecutionResult.rejected(tool.id(), tool.type());
        persistResult(body, result);
        broadcaster.broadcastStatus(id, "TOOL_REJECTED", result.message(), System.nanoTime());
        return ResponseEntity.ok(result);
    }

    private void persistResult(PendingToolAction body, ToolExecutionResult result) {
        if (body == null || body.sessionId() == null) return;
        assistantSessionService.appendToolResult(body.sessionId(), result);
    }
}
