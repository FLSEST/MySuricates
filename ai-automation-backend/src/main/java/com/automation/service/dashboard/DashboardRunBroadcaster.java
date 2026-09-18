package com.automation.service.dashboard;

import com.automation.event.WorkflowRunCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tells open dashboards that a run has ended (spec E3): one global topic, so the workflow widgets
 * refresh on the event instead of polling every 30 seconds per widget instance.
 *
 * <p>Runs on {@code WorkflowRunCompletedEvent}, which RunFinalizer publishes <b>after</b> the run is
 * saved — a client that reloads on this message therefore sees the final state, including the
 * dashboard_widget contract of the run. The message carries ids and the status enum only: no run
 * metadata, no error text, nothing from block output.
 *
 * <p>Deliberately synchronous but exception-free: the publisher runs other listeners after this one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardRunBroadcaster {

    static final String TOPIC = "/topic/dashboard/runs";

    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        if (event.getWorkflowId() == null || event.getRunId() == null || event.getStatus() == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSend(TOPIC, Map.of(
                    "type", "run_completed",
                    "runId", event.getRunId(),
                    "workflowId", event.getWorkflowId(),
                    "status", event.getStatus().name()));
        } catch (Exception e) {
            log.debug("Dashboard run broadcast failed: {}", e.getMessage());
        }
    }
}
