package com.automation.controller;

import com.automation.dto.request.CreateWebhookRequest;
import com.automation.dto.response.WebhookTriggerResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.WorkflowRun;
import com.automation.mapper.WebhookMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.security.WebhookRateLimiter;
import com.automation.service.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookMapper webhookMapper;
    private final WorkflowMapper workflowMapper;
    private final WebhookRateLimiter webhookRateLimiter;

    // --- Management endpoints ---

    @GetMapping("/api/v1/webhooks")
    public List<WebhookTriggerResponse> listWebhooks(HttpServletRequest request) {
        return webhookService.findAll().stream()
                .map(w -> toResponseWithUrl(w, request, TokenVisibility.MASK))
                .toList();
    }

    @GetMapping("/api/v1/webhooks/{id}")
    public WebhookTriggerResponse getWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.findById(id), request, TokenVisibility.MASK);
    }

    @GetMapping("/api/v1/webhooks/workflow/{workflowId}")
    public List<WebhookTriggerResponse> getByWorkflow(
            @PathVariable String workflowId, HttpServletRequest request) {
        return webhookService.findByWorkflowId(workflowId).stream()
                .map(w -> toResponseWithUrl(w, request, TokenVisibility.MASK))
                .toList();
    }

    @PostMapping("/api/v1/webhooks")
    public ResponseEntity<WebhookTriggerResponse> createWebhook(
            @Valid @RequestBody CreateWebhookRequest req, HttpServletRequest request) {
        var webhook = webhookService.create(req.workflowId(), req.description(), req.defaultContext());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseWithUrl(webhook, request, TokenVisibility.REVEAL));
    }

    @PostMapping("/api/v1/webhooks/{id}/enable")
    public WebhookTriggerResponse enableWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.enable(id), request, TokenVisibility.MASK);
    }

    @PostMapping("/api/v1/webhooks/{id}/disable")
    public WebhookTriggerResponse disableWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.disable(id), request, TokenVisibility.MASK);
    }

    /**
     * Rotates a webhook's secret token. Like {@link #createWebhook}, the response is the
     * only place the new token is ever revealed in full — every other management endpoint
     * masks it.
     */
    @PostMapping("/api/v1/webhooks/{id}/regenerate-token")
    public WebhookTriggerResponse regenerateToken(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.regenerateToken(id), request, TokenVisibility.REVEAL);
    }

    @DeleteMapping("/api/v1/webhooks/{id}")
    public ResponseEntity<Void> deleteWebhook(@PathVariable String id) {
        webhookService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Public trigger endpoint (no API key required) ---

    @PostMapping("/api/v1/webhook/trigger/{token}")
    public ResponseEntity<WorkflowRunResponse> triggerWebhook(
            @PathVariable String token,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        if (!webhookRateLimiter.tryAcquire(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        WorkflowRun run = webhookService.trigger(token, payload, headers);
        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    /**
     * Builds the response for a webhook. The secret token (and the URL derived from it) is
     * only shown in full when {@code reveal} is true — that is exclusively the case right
     * after creation or regeneration. Every other management response masks it to the first
     * 8 characters, since the full token is a bearer credential for the public trigger
     * endpoint.
     */
    /** Whether a management response carries the full bearer token or its masked prefix. */
    enum TokenVisibility { REVEAL, MASK }

    private WebhookTriggerResponse toResponseWithUrl(WebhookTrigger w, HttpServletRequest request,
                                                     TokenVisibility visibility) {
        WebhookTriggerResponse mapped = webhookMapper.toResponse(w);
        String baseUrl = request.getScheme() + "://" + request.getServerName()
                + ":" + request.getServerPort();
        boolean reveal = visibility == TokenVisibility.REVEAL;
        String token = w.getSecretToken() != null ? w.getSecretToken() : "";
        String displayToken = reveal ? token : maskToken(token);
        String webhookUrl = baseUrl + "/api/v1/webhook/trigger/" + displayToken;

        return new WebhookTriggerResponse(
                mapped.id(), mapped.workflowId(), mapped.workflowName(),
                displayToken, webhookUrl, reveal, mapped.enabled(),
                mapped.description(), mapped.defaultContext(),
                mapped.lastTriggeredAt(), mapped.triggerCount(), mapped.createdAt()
        );
    }

    private static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";   // never let a "null" literal end up in the URL
        }
        int visibleLength = Math.min(8, token.length());
        return token.substring(0, visibleLength) + "…";
    }
}
