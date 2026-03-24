package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.entity.AssistantSession;
import com.automation.repository.AssistantSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantSessionService {

    private final AssistantSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Appends user message + assistant response to the session in DB.
     * Creates the session if it doesn't exist yet.
     */
    @Transactional
    public void appendMessages(String sessionId, AssistantChatRequest req, AssistantChatResponse res) {
        if (sessionId == null) return;

        String rawPage = req.context() != null ? req.context().page() : null;
        String page = rawPage != null ? rawPage : "dashboard";
        String entityId = req.context() != null ? req.context().entityId() : null;

        AssistantSession session = sessionRepository.findById(sessionId)
                .orElseGet(() -> AssistantSession.builder()
                        .id(sessionId)
                        .page(page)
                        .entityId(entityId)
                        .startedAt(Instant.now())
                        .build());

        // Deserialize existing messages
        List<Map<String, String>> messages = deserializeMessages(session.getMessages());

        // Append new user message
        messages.add(Map.of("role", "user", "content", req.message()));

        // Append assistant response (truncated to 500 chars to keep DB lean)
        String assistantContent = res.message();
        if (assistantContent != null && assistantContent.length() > 500) {
            assistantContent = assistantContent.substring(0, 500) + "…";
        }
        messages.add(Map.of("role", "assistant", "content", assistantContent != null ? assistantContent : ""));

        session.setMessages(serializeMessages(messages));
        session.setMessageCount(messages.size());
        session.setEndedAt(Instant.now());

        sessionRepository.save(session);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> deserializeMessages(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not deserialize assistant session messages", e);
            return new ArrayList<>();
        }
    }

    private String serializeMessages(List<Map<String, String>> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            return "[]";
        }
    }
}
