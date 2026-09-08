package com.automation.service.assistant;

import com.automation.dto.AssistantChatResponse;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Splits the raw model output of a chat turn into the message text and the optional
 * {@code <actions>[…]</actions>} block (legacy inline actions).
 *
 * <p>Extracted from {@code AssistantService} (Audit-TODO 7b5bbb4a). Malformed action JSON is
 * logged and yields an empty action list — never an exception.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantResponseParser {

    private final ObjectMapper objectMapper;

    public AssistantChatResponse parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new AssistantChatResponse("(No response received)", List.of());
        }

        List<AssistantAction> actions = Collections.emptyList();
        String message = raw.trim();

        int actionsStart = raw.lastIndexOf("<actions>");
        int actionsEnd = raw.lastIndexOf("</actions>");

        if (actionsStart >= 0 && actionsEnd > actionsStart) {
            String jsonPart = raw.substring(actionsStart + "<actions>".length(), actionsEnd).trim();
            message = raw.substring(0, actionsStart).trim();
            try {
                actions = objectMapper.readValue(jsonPart, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Could not parse assistant actions JSON: {}", jsonPart, e);
            }
        }

        return new AssistantChatResponse(message, actions);
    }
}
