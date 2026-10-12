package com.automation.executor.blocks;

import lombok.RequiredArgsConstructor;
import com.automation.util.RunAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the live events of an agentic block ({@code agentic_started}, {@code agentic_reasoned},
 * {@code agentic_acted}, …) to the run's WebSocket log topic. A failing broadcast never aborts
 * the block.
 *
 * <p>Extracted from {@code AgenticBlockExecutor} (Audit-TODO a253ad3e).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgenticEventBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(String runId, int stepIndex, String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", eventType);
            message.put("stepIndex", stepIndex);
            message.putAll(RunAccess.maskValues(data));
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs", (Object) message);
        } catch (Exception e) {
            log.warn("Failed to broadcast agentic event: {}", e.getMessage());
        }
    }
}
