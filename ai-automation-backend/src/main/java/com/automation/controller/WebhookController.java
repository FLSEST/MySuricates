package com.automation.controller;

import com.automation.dto.request.CreateWebhookRequest;
import com.automation.dto.response.WebhookSigningSecretResponse;
import com.automation.dto.response.WebhookTriggerResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.WorkflowRun;
import com.automation.mapper.WebhookMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.security.WebhookRateLimiter;
import com.automation.service.webhook.WebhookService;
import com.automation.service.webhook.WebhookSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Webhooks: manage the tokens that start a workflow from outside, and the public trigger endpoint itself. */
@RequiresModule(ModuleCatalog.WEBHOOKS)
@RestController
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookMapper webhookMapper;
    private final WorkflowMapper workflowMapper;
    private final WebhookRateLimiter webhookRateLimiter;

    // --- Management endpoints ---

    /**
     * Lists all webhooks; tokens and URLs are masked to their first characters.
     * Masked means the first 8 characters plus {@code …}; only create and regenerate-token show the full token.
     */
    @GetMapping("/api/v1/webhooks")
    public List<WebhookTriggerResponse> listWebhooks(HttpServletRequest request) {
        return webhookService.findAll().stream()
                .map(w -> toResponseWithUrl(w, request, TokenVisibility.MASK))
                .toList();
    }

    /** Returns one webhook with masked token. */
    @GetMapping("/api/v1/webhooks/{id}")
    public WebhookTriggerResponse getWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.findById(id), request, TokenVisibility.MASK);
    }

    /** Lists the webhooks of a workflow with masked tokens. */
    @GetMapping("/api/v1/webhooks/workflow/{workflowId}")
    public List<WebhookTriggerResponse> getByWorkflow(
            @PathVariable String workflowId, HttpServletRequest request) {
        return webhookService.findByWorkflowId(workflowId).stream()
                .map(w -> toResponseWithUrl(w, request, TokenVisibility.MASK))
                .toList();
    }

    /**
     * Creates an enabled webhook for {@code workflowId} and returns it with 201.
     * Only this response shows the full token and URL ({@code tokenVisible: true}) – store them right away.
     */
    @PostMapping("/api/v1/webhooks")
    public ResponseEntity<WebhookTriggerResponse> createWebhook(
            @Valid @RequestBody CreateWebhookRequest req, HttpServletRequest request) {
        var webhook = webhookService.create(req.workflowId(), req.description(), req.defaultContext());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseWithUrl(webhook, request, TokenVisibility.REVEAL));
    }

    /** Enables a webhook. */
    @PostMapping("/api/v1/webhooks/{id}/enable")
    public WebhookTriggerResponse enableWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.enable(id), request, TokenVisibility.MASK);
    }

    /** Disables a webhook; calls to its trigger URL then return 409. */
    @PostMapping("/api/v1/webhooks/{id}/disable")
    public WebhookTriggerResponse disableWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.disable(id), request, TokenVisibility.MASK);
    }

    /**
     * Rotates a webhook's secret token. Like creating a webhook, the response is the only place the
     * new token is ever revealed in full — every other management endpoint masks it. The old token
     * stops working at once; {@code lastTriggeredAt} and {@code triggerCount} stay, and a
     * {@code SECURITY} entry is written to the activity log.
     */
    @PostMapping("/api/v1/webhooks/{id}/regenerate-token")
    public WebhookTriggerResponse regenerateToken(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.regenerateToken(id), request, TokenVisibility.REVEAL);
    }

    /**
     * Creates or rotates the signing secret; the response is the only place it is ever shown.
     * From then on every trigger call needs a valid signature; a {@code SECURITY} entry is logged.
     */
    @RequiresModule(ModuleCatalog.WEBHOOK_SIGNATURES)
    @PostMapping("/api/v1/webhooks/{id}/secret")
    public WebhookSigningSecretResponse generateSigningSecret(@PathVariable String id) {
        return new WebhookSigningSecretResponse(webhookService.generateSigningSecret(id), WebhookSignatureVerifier.HEADER);
    }

    /** Removes the signing secret, so trigger calls no longer need a signature; logged as {@code SECURITY}. */
    @RequiresModule(ModuleCatalog.WEBHOOK_SIGNATURES)
    @DeleteMapping("/api/v1/webhooks/{id}/secret")
    public ResponseEntity<Void> removeSigningSecret(@PathVariable String id) {
        webhookService.removeSigningSecret(id);
        return ResponseEntity.noContent().build();
    }

    /** Deletes a webhook; its token stops working. */
    @DeleteMapping("/api/v1/webhooks/{id}")
    public ResponseEntity<Void> deleteWebhook(@PathVariable String id) {
        webhookService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Public trigger endpoint (no API key required) ---

    /**
     * Starts the webhook's workflow without API key; the token in the path authorizes the call.
     * An optional JSON object body is merged over {@code defaultContext} (keys starting with {@code __} return 400);
     * with a signing secret the call needs a valid {@code X-Signature} header, else 401. A body that is no
     * JSON object is 400, an unknown token 404, a disabled webhook 409.
     */
    @PostMapping("/api/v1/webhook/trigger/{token}")
    public ResponseEntity<WorkflowRunResponse> triggerWebhook(
            @PathVariable String token,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] rawBody,
            HttpServletRequest request) {
        if (!webhookRateLimiter.tryAcquire(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        WorkflowRun run = webhookService.trigger(token, rawBody, headers);
        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    /** Whether a management response carries the full bearer token or its masked prefix. */
    enum TokenVisibility { REVEAL, MASK }

    /**
     * Builds the response for a webhook. The secret token (and the URL derived from it) is
     * only shown in full when {@code reveal} is true — that is exclusively the case right
     * after creation or regeneration. Every other management response masks it to the first
     * 8 characters, since the full token is a bearer credential for the public trigger
     * endpoint.
     */
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
                mapped.lastTriggeredAt(), mapped.triggerCount(), mapped.createdAt(),
                mapped.signatureRequired()
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
