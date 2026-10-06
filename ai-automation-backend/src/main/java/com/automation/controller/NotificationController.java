package com.automation.controller;

import com.automation.dto.request.CreateNotificationConfigRequest;
import com.automation.dto.response.NotificationConfigResponse;
import com.automation.mapper.NotificationConfigMapper;
import com.automation.service.notification.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Notification configs that send a webhook or desktop message when a workflow run finishes. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final NotificationConfigMapper mapper;

    /** Lists all notification configs. */
    @GetMapping
    public List<NotificationConfigResponse> listAll() {
        return mapper.toResponseList(service.findAll());
    }

    /** Returns one notification config. */
    @GetMapping("/{id}")
    public NotificationConfigResponse getById(@PathVariable String id) {
        return mapper.toResponse(service.findById(id));
    }

    /**
     * Creates a notification config (201); {@code name} and {@code channelType} ({@code WEBHOOK} or {@code DESKTOP}) are required.
     * Without {@code workflowId} it fires for every workflow; {@code messageTemplate} may use placeholders like {@code {{status}}}.
     * {@code triggerOnSuccess} covers COMPLETED and COMPLETED_WITH_ERRORS, {@code triggerOnFailure} FAILED and
     * INTERRUPTED, {@code triggerAlways} any status. Placeholders, written without spaces: {@code {{workflow_name}}},
     * {@code {{workflow_id}}}, {@code {{run_id}}}, {@code {{status}}}, {@code {{error_message}}}; without a template a
     * default message is sent. A {@code WEBHOOK} config without {@code webhookUrl} is accepted but sends nothing.
     */
    @PostMapping
    public ResponseEntity<NotificationConfigResponse> create(
            @Valid @RequestBody CreateNotificationConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.create(request)));
    }

    /** Switches the notification config on. */
    @PostMapping("/{id}/enable")
    public NotificationConfigResponse enable(@PathVariable String id) {
        return mapper.toResponse(service.enable(id));
    }

    /** Switches the notification config off. */
    @PostMapping("/{id}/disable")
    public NotificationConfigResponse disable(@PathVariable String id) {
        return mapper.toResponse(service.disable(id));
    }

    /** Deletes the notification config (204). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
