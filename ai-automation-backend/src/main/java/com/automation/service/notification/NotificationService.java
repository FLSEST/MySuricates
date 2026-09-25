package com.automation.service.notification;

import com.automation.dto.request.CreateNotificationConfigRequest;
import com.automation.entity.NotificationConfig;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.NotificationConfigRepository;
import com.automation.service.tray.SystemTrayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class NotificationService {

    private final NotificationConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final TransactionTemplate readOnlyTransactionTemplate;

    @Autowired(required = false)
    private SystemTrayService systemTrayService;

    @Autowired
    public NotificationService(NotificationConfigRepository repository, ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this(repository, objectMapper, transactionManager, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    NotificationService(NotificationConfigRepository repository, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager, HttpClient httpClient) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    @Transactional(readOnly = true)
    public List<NotificationConfig> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public NotificationConfig findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationConfig", id));
    }

    @Transactional
    public NotificationConfig create(CreateNotificationConfigRequest req) {
        NotificationConfig config = NotificationConfig.builder()
                .name(req.name())
                .channelType(req.channelType())
                .webhookUrl(req.webhookUrl())
                .workflowId(req.workflowId())
                .triggerOnSuccess(req.triggerOnSuccess())
                .triggerOnFailure(req.triggerOnFailure())
                .triggerAlways(req.triggerAlways())
                .messageTemplate(req.messageTemplate())
                .build();
        return repository.save(config);
    }

    @Transactional
    public NotificationConfig enable(String id) {
        NotificationConfig config = findById(id);
        config.setEnabled(true);
        return repository.save(config);
    }

    @Transactional
    public NotificationConfig disable(String id) {
        NotificationConfig config = findById(id);
        config.setEnabled(false);
        return repository.save(config);
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("NotificationConfig", id);
        }
        repository.deleteById(id);
    }

    /**
     * Deliberately not {@code @Transactional}: a webhook waits on a foreign server (5 s to connect,
     * 10 s for the answer), and with HikariCP pool-size 1 an enclosing transaction would hold the
     * only DB connection all that time (TODO ab7f042a). The configs are read in a short read burst –
     * they have no lazy associations, so they stay usable after it.
     */
    @Async
    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        List<NotificationConfig> configs = readOnlyTransactionTemplate.execute(status ->
                repository.findEnabledForWorkflow(event.getWorkflowId()));
        if (configs == null || configs.isEmpty()) return;

        for (NotificationConfig config : configs) {
            if (!shouldFire(config, event.getStatus())) continue;

            Map<String, String> vars = buildVariables(event);
            String message = resolveTemplate(config.getMessageTemplate(), vars, event);

            try {
                switch (config.getChannelType()) {
                    case WEBHOOK -> sendWebhook(config, message, event);
                    case DESKTOP -> sendDesktop(message, event);
                }
            } catch (Exception e) {
                log.error("Notification failed for config {}: {}", config.getId(), e.getMessage());
            }
        }
    }

    private boolean shouldFire(NotificationConfig config, RunStatus status) {
        if (config.isTriggerAlways()) return true;
        return switch (status) {
            case COMPLETED, COMPLETED_WITH_ERRORS -> config.isTriggerOnSuccess();
            case FAILED, INTERRUPTED -> config.isTriggerOnFailure();
            default -> false;
        };
    }

    private Map<String, String> buildVariables(WorkflowRunCompletedEvent event) {
        Map<String, String> vars = new HashMap<>();
        vars.put("workflow_name", event.getWorkflowName());
        vars.put("workflow_id", event.getWorkflowId());
        vars.put("run_id", event.getRunId());
        vars.put("status", event.getStatus().name());
        Object errorMsg = event.getError().get("message");
        vars.put("error_message", errorMsg != null ? String.valueOf(errorMsg) : "");
        return vars;
    }

    private String resolveTemplate(String template, Map<String, String> vars, WorkflowRunCompletedEvent event) {
        if (template == null || template.isBlank()) {
            // Default message
            boolean isError = event.getStatus() == RunStatus.FAILED || event.getStatus() == RunStatus.INTERRUPTED;
            String emoji = isError ? "❌" : "✅";
            return emoji + " Workflow *" + event.getWorkflowName() + "* finished with status: " + event.getStatus().name();
        }
        String resolved = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return resolved;
    }

    private void sendWebhook(NotificationConfig config, String message, WorkflowRunCompletedEvent event) {
        if (config.getWebhookUrl() == null || config.getWebhookUrl().isBlank()) {
            log.warn("Notification config {} has WEBHOOK type but no webhookUrl configured", config.getId());
            return;
        }

        try {
            // Slack/Discord/Teams compatible JSON payload
            boolean isError = event.getStatus() == RunStatus.FAILED || event.getStatus() == RunStatus.INTERRUPTED;
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", message);

            // Slack attachment for rich display
            Map<String, Object> attachment = new HashMap<>();
            attachment.put("color", isError ? "danger" : "good");
            attachment.put("text", message);
            attachment.put("ts", Instant.now().getEpochSecond());
            payload.put("attachments", List.of(attachment));

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getWebhookUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Webhook notification returned status {}: {}", response.statusCode(), response.body());
            } else {
                log.debug("Webhook notification sent for run {}", event.getRunId());
            }
        } catch (Exception e) {
            log.error("Failed to send webhook notification for config {}: {}", config.getId(), e.getMessage());
        }
    }

    private void sendDesktop(String message, WorkflowRunCompletedEvent event) {
        if (systemTrayService == null) {
            log.debug("Desktop notification skipped: tray not available");
            return;
        }
        boolean isError = event.getStatus() == RunStatus.FAILED || event.getStatus() == RunStatus.INTERRUPTED;
        systemTrayService.showNotification("MySuricates", message, isError);
    }
}
