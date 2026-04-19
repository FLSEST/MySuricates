package com.automation.controller;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.response.AssistantSessionDetail;
import com.automation.dto.response.AssistantSessionSummary;
import com.automation.event.AssistantSessionUpdatedEvent;
import com.automation.service.AssistantService;
import com.automation.service.AssistantSessionService;
import com.automation.service.PageSuggestionService;
import com.automation.service.assistant.AssistantChatBroadcaster;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantSessionService assistantSessionService;
    private final PageSuggestionService pageSuggestionService;
    private final AssistantChatBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;

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
            eventPublisher.publishEvent(new AssistantSessionUpdatedEvent(page));
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
}
