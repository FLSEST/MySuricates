package com.automation.controller;

import com.automation.entity.DeadLetterEntry;
import com.automation.service.dlq.DeadLetterRetryService;
import com.automation.service.dlq.DeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dlq")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService dlqService;
    private final DeadLetterRetryService dlqRetryService;

    @GetMapping
    public Page<DeadLetterEntry> list(
            @RequestParam(required = false) DeadLetterEntry.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dlqService.list(status, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public DeadLetterEntry get(@PathVariable String id) {
        return dlqService.findById(id);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<DeadLetterEntry> retry(@PathVariable String id) {
        return ResponseEntity.ok(dlqRetryService.retry(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(@PathVariable String id) {
        dlqService.discard(id);
        return ResponseEntity.noContent().build();
    }
}
