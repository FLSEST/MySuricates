package com.automation.service.webhook;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.TriggerSource;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WebhookTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.executor.ExecutionContext;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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
    private final com.automation.service.workflow.WorkflowService workflowService;
    private final GitWebhookPayloadParser gitPayloadParser;
    private final ActivityLogService activityLogService;

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

        WebhookTrigger webhook = WebhookTrigger.builder()
                .workflow(workflow)
                .secretToken(generateToken())
                .description(description)
                .defaultContext(defaultContext != null ? defaultContext : new HashMap<>())
                .enabled(true)
                .triggerCount(0)
                .build();

        WebhookTrigger saved = webhookRepository.save(webhook);
        log.info("Created webhook trigger: {} for workflow {}",
                saved.getId(), workflowId);
        activityLogService.info(LogCategory.WEBHOOK, "Webhook created for workflow: " + workflow.getName(), "WebhookService", saved.getId());
        return saved;
    }

    /**
     * Rotates the secret token of an existing webhook. The new token is only ever
     * revealed in this call's response — every other read masks it. Trigger stats
     * ({@code lastTriggeredAt}, {@code triggerCount}) are left untouched.
     */
    @Transactional
    public WebhookTrigger regenerateToken(String id) {
        WebhookTrigger webhook = findById(id);
        webhook.setSecretToken(generateToken());
        WebhookTrigger saved = webhookRepository.save(webhook);
        log.info("Regenerated webhook token: {}", id);
        activityLogService.warn(LogCategory.SECURITY,
                "Webhook token regenerated for workflow: " + webhook.getWorkflow().getName(),
                "WebhookService", id);
        return saved;
    }

    private static String generateToken() {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
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
        activityLogService.warn(LogCategory.WEBHOOK, "Webhook deleted: " + id, "WebhookService", id);
    }

    /**
     * Trigger a workflow execution via webhook token.
     * Merges provided payload with default context.
     * Git provider headers (X-GitHub-Event, X-Gitlab-Event, X-Gitea-Event) are used
     * to auto-extract standardized {@code git_*} context variables from the payload.
     */
    @Transactional
    public WorkflowRun trigger(String token, Map<String, Object> payload, HttpHeaders headers) {
        WebhookTrigger webhook = webhookRepository.findBySecretToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("WebhookTrigger", "token"));

        if (!webhook.isEnabled()) {
            throw new IllegalStateException("Webhook trigger is disabled");
        }

        // Merge default context with payload (payload overrides)
        Map<String, Object> context = new HashMap<>(webhook.getDefaultContext());
        // Everything the caller controls is untrusted: shell blocks always escape these variables
        // (the trigger endpoint is public — only the token guards it).
        Set<String> untrustedKeys = new LinkedHashSet<>();
        if (payload != null) {
            if (payload.size() > 50) {
                throw new IllegalArgumentException("Webhook payload exceeds maximum of 50 fields");
            }
            for (String key : payload.keySet()) {
                if (key == null || key.startsWith("__")) {
                    throw new IllegalArgumentException("Webhook payload keys starting with '__' are reserved");
                }
            }
            context.putAll(payload);
            untrustedKeys.addAll(payload.keySet());
        }

        // Extract git_* fields from recognized Git provider payloads (non-overriding)
        Map<String, Object> gitFields = gitPayloadParser.parse(payload, headers != null ? headers : HttpHeaders.EMPTY);
        gitFields.forEach(context::putIfAbsent);
        untrustedKeys.addAll(gitFields.keySet());
        if (!untrustedKeys.isEmpty()) {
            context.put(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.copyOf(untrustedKeys));
        }

        // Load workflow with eagerly initialized activatedSkills — webhook.getWorkflow()
        // comes from an EAGER relationship but leaves the LAZY ManyToMany uninitialized,
        // which causes LazyInitializationException in WorkflowExecutor.execute().
        Workflow workflow = workflowService.findByIdEager(webhook.getWorkflow().getId());
        WorkflowRun run = workflowExecutor.execute(workflow, context, TriggerSource.WEBHOOK);

        webhookRepository.incrementTriggerCount(webhook.getId(), Instant.now());

        log.info("Webhook {} triggered workflow {}, run: {}",
                webhook.getId(), workflow.getId(), run.getId());
        activityLogService.info(LogCategory.WEBHOOK, "Webhook triggered: " + workflow.getName(), "WebhookService", run.getId(), workflow.getId());

        return run;
    }
}
