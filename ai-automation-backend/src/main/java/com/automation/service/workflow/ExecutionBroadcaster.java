package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
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
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs",
                    Map.of(
                            "stepIndex", step.getStepIndex(),
                            "status", step.getStatus(),
                            "input", step.getInput() != null ? step.getInput() : "",
                            "output", step.getOutput() != null ? step.getOutput() : "",
                            "durationMs", step.getDurationMs() != null ? step.getDurationMs() : 0
                    )
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast step result: {}", e.getMessage());
        }
    }

    public void broadcastRunStatus(String runId, RunStatus status) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs",
                    Map.of("type", "status", "runId", runId, "status", status)
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast final status: {}", e.getMessage());
        }
    }

    public void broadcastLogMessage(String runId, String type, Map<String, Object> data) {
        try {
            Map<String, Object> msg = new HashMap<>(data);
            msg.put("type", type);
            msg.put("runId", runId);
            msg.put("timestamp", Instant.now().toString());
            messagingTemplate.convertAndSend("/topic/executions/" + runId + "/logs", msg);
        } catch (Exception e) {
            log.warn("Failed to broadcast log message: {}", e.getMessage());
        }
    }
}
