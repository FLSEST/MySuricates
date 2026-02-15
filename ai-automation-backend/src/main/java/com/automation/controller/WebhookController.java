package com.automation.controller;

import com.automation.dto.request.CreateWebhookRequest;
import com.automation.dto.response.WebhookTriggerResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.WorkflowRun;
import com.automation.mapper.WebhookMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    // --- Management endpoints ---

    @GetMapping("/api/v1/webhooks")
    public List<WebhookTriggerResponse> listWebhooks(HttpServletRequest request) {
        return webhookService.findAll().stream()
                .map(w -> toResponseWithUrl(w, request))
                .toList();
    }

    @GetMapping("/api/v1/webhooks/{id}")
    public WebhookTriggerResponse getWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.findById(id), request);
    }

    @GetMapping("/api/v1/webhooks/workflow/{workflowId}")
    public List<WebhookTriggerResponse> getByWorkflow(
            @PathVariable String workflowId, HttpServletRequest request) {
        return webhookService.findByWorkflowId(workflowId).stream()
                .map(w -> toResponseWithUrl(w, request))
                .toList();
    }

    @PostMapping("/api/v1/webhooks")
    public ResponseEntity<WebhookTriggerResponse> createWebhook(
            @Valid @RequestBody CreateWebhookRequest req, HttpServletRequest request) {
        var webhook = webhookService.create(req.workflowId(), req.description(), req.defaultContext());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseWithUrl(webhook, request));
    }

    @PostMapping("/api/v1/webhooks/{id}/enable")
    public WebhookTriggerResponse enableWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.enable(id), request);
    }

    @PostMapping("/api/v1/webhooks/{id}/disable")
    public WebhookTriggerResponse disableWebhook(@PathVariable String id, HttpServletRequest request) {
        return toResponseWithUrl(webhookService.disable(id), request);
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
            @RequestBody(required = false) Map<String, Object> payload) {
        WorkflowRun run = webhookService.trigger(token, payload);
        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    private WebhookTriggerResponse toResponseWithUrl(WebhookTrigger w, HttpServletRequest request) {
        WebhookTriggerResponse mapped = webhookMapper.toResponse(w);
        String baseUrl = request.getScheme() + "://" + request.getServerName()
                + ":" + request.getServerPort();
        String webhookUrl = baseUrl + "/api/v1/webhook/trigger/" + w.getSecretToken();

        return new WebhookTriggerResponse(
                mapped.id(), mapped.workflowId(), mapped.workflowName(),
                mapped.secretToken(), webhookUrl, mapped.enabled(),
                mapped.description(), mapped.defaultContext(),
                mapped.lastTriggeredAt(), mapped.triggerCount(), mapped.createdAt()
        );
    }
}
