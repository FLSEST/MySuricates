package com.automation.controller;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.event.AssistantSessionUpdatedEvent;
import com.automation.service.AssistantService;
import com.automation.service.AssistantSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantSessionService assistantSessionService;
    private final ApplicationEventPublisher eventPublisher;

    @PostMapping("/chat")
    public AssistantChatResponse chat(@Valid @RequestBody AssistantChatRequest request) {
        AssistantChatResponse response = assistantService.chat(request);

        String sessionId = request.sessionId();
        if (sessionId != null) {
            assistantSessionService.appendMessages(sessionId, request, response);
            String page = request.context() != null && request.context().page() != null
                    ? request.context().page() : "dashboard";
            eventPublisher.publishEvent(new AssistantSessionUpdatedEvent(page));
        }

        return response;
    }
}
