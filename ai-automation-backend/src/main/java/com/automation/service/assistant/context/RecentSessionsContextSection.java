package com.automation.service.assistant.context;

import com.automation.entity.AssistantSession;
import com.automation.repository.AssistantSessionRepository;
import com.automation.service.assistant.format.AssistantFormatUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lists the first exchange of the five most recent assistant conversations so the
 * assistant can pick up threads across sessions. Only rendered on the overview
 * page — there is no compact variant, the flag is ignored.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecentSessionsContextSection implements AssistantContextSection {

    private final AssistantSessionRepository assistantSessionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            List<AssistantSession> sessions = assistantSessionRepository.findTop5ByOrderByStartedAtDesc();
            if (sessions.isEmpty()) return;
            sb.append("## Recent Conversations (Context)\n");
            sessions.forEach(sess -> {
                String[] firstExchange = extractFirstExchange(sess.getMessages());
                if (firstExchange != null) {
                    sb.append("  - [").append(AssistantFormatUtils.fmt(sess.getStartedAt())).append("] ")
                            .append("User: \"").append(AssistantFormatUtils.truncate(firstExchange[0], 150)).append("\"");
                    if (firstExchange[1] != null) {
                        sb.append(" → Response: \"").append(AssistantFormatUtils.truncate(firstExchange[1], 100)).append("\"");
                    }
                    sb.append("\n");
                }
            });
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load recent sessions for assistant context", e);
        }
    }

    /**
     * Extracts the first user message and assistant response from a session's messages JSON.
     * Returns [userMessage, assistantResponse] or null if no user message found.
     */
    private String[] extractFirstExchange(String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank() || "[]".equals(messagesJson)) return null;
        try {
            List<Map<String, String>> messages = objectMapper.readValue(messagesJson, new TypeReference<>() {});
            String userMsg = null;
            String assistantMsg = null;
            for (Map<String, String> m : messages) {
                if (userMsg == null && "user".equals(m.get("role"))) {
                    userMsg = m.get("content");
                } else if (userMsg != null && assistantMsg == null && "assistant".equals(m.get("role"))) {
                    assistantMsg = m.get("content");
                    break;
                }
            }
            if (userMsg == null || userMsg.isBlank()) return null;
            return new String[]{ userMsg, assistantMsg };
        } catch (Exception e) {
            return null;
        }
    }
}
