package com.automation.service.webhook;

import com.automation.entity.WebhookTrigger;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WebhookTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WebhookTriggerRepository webhookRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutor workflowExecutor;

    @Transactional(readOnly = true)
    public List<WebhookTrigger> findAll() {
        return webhookRepository.findAll();
    }

    @Transactional(readOnly = true)
    public WebhookTrigger findById(String id) {
        return webhookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WebhookTrigger", id));
    }

    @Transactional(readOnly = true)
    public List<WebhookTrigger> findByWorkflowId(String workflowId) {
        return webhookRepository.findByWorkflowId(workflowId);
    }

    @Transactional
    public WebhookTrigger create(String workflowId, String description, Map<String, Object> defaultContext) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        WebhookTrigger webhook = WebhookTrigger.builder()
                .workflow(workflow)
                .secretToken(token)
                .description(description)
                .defaultContext(defaultContext != null ? defaultContext : new HashMap<>())
                .enabled(true)
                .triggerCount(0)
                .build();

        WebhookTrigger saved = webhookRepository.save(webhook);
        log.info("Created webhook trigger: {} for workflow {} (token: {}...)",
                saved.getId(), workflowId, token.substring(0, 8));
        return saved;
    }

    @Transactional
    public WebhookTrigger enable(String id) {
        WebhookTrigger webhook = findById(id);
        webhook.setEnabled(true);
        return webhookRepository.save(webhook);
    }

    @Transactional
    public WebhookTrigger disable(String id) {
        WebhookTrigger webhook = findById(id);
        webhook.setEnabled(false);
        return webhookRepository.save(webhook);
    }

    @Transactional
    public void delete(String id) {
        WebhookTrigger webhook = findById(id);
        webhookRepository.delete(webhook);
        log.info("Deleted webhook trigger: {}", id);
    }

    /**
     * Trigger a workflow execution via webhook token.
     * Merges provided payload with default context.
     */
    @Transactional
    public WorkflowRun trigger(String token, Map<String, Object> payload) {
        WebhookTrigger webhook = webhookRepository.findBySecretToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("WebhookTrigger", "token"));

        if (!webhook.isEnabled()) {
            throw new IllegalStateException("Webhook trigger is disabled");
        }

        // Merge default context with payload (payload overrides)
        Map<String, Object> context = new HashMap<>(webhook.getDefaultContext());
        if (payload != null) {
            context.putAll(payload);
        }

        WorkflowRun run = workflowExecutor.execute(webhook.getWorkflow(), context);

        webhookRepository.incrementTriggerCount(webhook.getId(), Instant.now());

        log.info("Webhook {} triggered workflow {}, run: {}",
                webhook.getId(), webhook.getWorkflow().getId(), run.getId());

        return run;
    }
}
