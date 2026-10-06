package com.automation.controller;

import com.automation.entity.DeadLetterEntry;
import com.automation.service.dlq.DeadLetterRetryService;
import com.automation.service.dlq.DeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Dead letter queue: failed workflow runs, kept for retry or discard. */
@RestController
@RequestMapping("/api/v1/dlq")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService dlqService;
    private final DeadLetterRetryService dlqRetryService;

    /** Lists dead letter entries page by page, most recent failure first; optional {@code status} filter. */
    @GetMapping
    public Page<DeadLetterEntry> list(
            @RequestParam(required = false) DeadLetterEntry.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dlqService.list(status, PageRequest.of(page, size));
    }

    /** Returns one dead letter entry. */
    @GetMapping("/{id}")
    public DeadLetterEntry get(@PathVariable String id) {
        return dlqService.findById(id);
    }

    /**
     * Starts the workflow again with the input context of the failed run and marks the entry as retried.
     * If the workflow or the failed run no longer exists, nothing starts, but the entry is still marked retried.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<DeadLetterEntry> retry(@PathVariable String id) {
        return ResponseEntity.ok(dlqRetryService.retry(id));
    }

    /** Discards a dead letter entry: it is marked {@code DISCARDED}, not deleted; answers 204. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(@PathVariable String id) {
        dlqService.discard(id);
        return ResponseEntity.noContent().build();
    }
}
