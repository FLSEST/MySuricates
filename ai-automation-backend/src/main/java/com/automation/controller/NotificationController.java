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

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final NotificationConfigMapper mapper;

    @GetMapping
    public List<NotificationConfigResponse> listAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public NotificationConfigResponse getById(@PathVariable String id) {
        return mapper.toResponse(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<NotificationConfigResponse> create(
            @Valid @RequestBody CreateNotificationConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.create(request)));
    }

    @PostMapping("/{id}/enable")
    public NotificationConfigResponse enable(@PathVariable String id) {
        return mapper.toResponse(service.enable(id));
    }

    @PostMapping("/{id}/disable")
    public NotificationConfigResponse disable(@PathVariable String id) {
        return mapper.toResponse(service.disable(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
