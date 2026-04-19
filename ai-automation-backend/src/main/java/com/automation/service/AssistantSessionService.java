package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.response.AssistantSessionDetail;
import com.automation.dto.response.AssistantSessionSummary;
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
import java.util.LinkedHashMap;
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
                .orElseGet(() -> {
                    String preview = req.message();
                    if (preview != null && preview.length() > 100) {
                        preview = preview.substring(0, 100) + "…";
                    }
                    return AssistantSession.builder()
                            .id(sessionId)
                            .page(page)
                            .entityId(entityId)
                            .preview(preview)
                            .startedAt(Instant.now())
                            .build();
                });

        // Deserialize existing messages
        List<Map<String, Object>> messages = deserializeMessages(session.getMessages());

        // Append new user message
        long now = Instant.now().toEpochMilli();
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", req.message());
        userMsg.put("timestamp", now);
        messages.add(userMsg);

        // Append assistant response
        String assistantContent = res.message();
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", assistantContent != null ? assistantContent : "");
        assistantMsg.put("timestamp", Instant.now().toEpochMilli());
        if (res.responseTimeMs() != null) {
            assistantMsg.put("responseTimeMs", res.responseTimeMs());
        }
        if (res.provider() != null) {
            assistantMsg.put("provider", res.provider());
        }
        if (res.model() != null) {
            assistantMsg.put("model", res.model());
        }
        messages.add(assistantMsg);

        session.setMessages(serializeMessages(messages));
        session.setMessageCount(messages.size());
        session.setEndedAt(Instant.now());

        sessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public List<AssistantSessionSummary> listRecentSessions() {
        return sessionRepository.findTop50ByOrderByStartedAtDesc().stream()
                .map(s -> new AssistantSessionSummary(
                        s.getId(), resolvePreview(s), s.getPage(),
                        s.getMessageCount(), s.getStartedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AssistantSessionDetail getSession(String id) {
        AssistantSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        return new AssistantSessionDetail(
                session.getId(), resolvePreview(session), session.getPage(),
                session.getEntityId(), deserializeMessages(session.getMessages()),
                session.getMessageCount(), session.getStartedAt(), session.getEndedAt());
    }

    @Transactional
    public void deleteSession(String id) {
        sessionRepository.deleteById(id);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String resolvePreview(AssistantSession session) {
        if (session.getPreview() != null) return session.getPreview();
        // Fallback for sessions created before V58: extract first user message
        List<Map<String, Object>> msgs = deserializeMessages(session.getMessages());
        return msgs.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> {
                    String content = String.valueOf(m.get("content"));
                    return content.length() > 100 ? content.substring(0, 100) + "…" : content;
                })
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deserializeMessages(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not deserialize assistant session messages", e);
            return new ArrayList<>();
        }
    }

    private String serializeMessages(List<Map<String, Object>> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            return "[]";
        }
    }
}
