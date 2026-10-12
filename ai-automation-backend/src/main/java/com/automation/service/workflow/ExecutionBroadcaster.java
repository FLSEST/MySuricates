package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.util.RunAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcasts workflow execution events via WebSocket.
 * Extracted from WorkflowExecutor to reduce its dependency count.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcastStepResult(String runId, StepResult step) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("stepIndex", step.getStepIndex());
            message.put("status", step.getStatus());
            message.put("input", step.getInput() != null ? step.getInput() : "");
            message.put("output", step.getOutput() != null ? step.getOutput() : "");
            message.put("durationMs", step.getDurationMs() != null ? step.getDurationMs() : 0);
            // Labels let log viewers without the workflow definition name the step.
            Map<String, Object> metadata = step.getMetadata() != null ? step.getMetadata() : Map.of();
            for (String label : new String[]{"blockType", "blockName"}) {
                if (metadata.get(label) instanceof String value && !value.isBlank()) {
                    message.put(label, value);
                }
            }
            messagingTemplate.convertAndSend("/topic/executions/" + runId + "/logs", (Object) message);
        } catch (Exception e) {
            log.warn("Failed to broadcast step result: {}", e.getMessage());
        }
    }

    public void broadcastRunStatus(String runId, RunStatus status) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs",
                    (Object) Map.of("type", "status", "runId", runId, "status", status)
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast final status: {}", e.getMessage());
        }
    }

    public void broadcastLogMessage(String runId, String type, Map<String, Object> data) {
        try {
            Map<String, Object> msg = new HashMap<>(RunAccess.maskValues(data));   // foreach item outputs
            msg.put("type", type);
            msg.put("runId", runId);
            msg.put("timestamp", Instant.now().toString());
            messagingTemplate.convertAndSend("/topic/executions/" + runId + "/logs", (Object) msg);
        } catch (Exception e) {
            log.warn("Failed to broadcast log message: {}", e.getMessage());
        }
    }
}
