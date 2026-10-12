package com.automation.controller;

import com.automation.dto.response.DeadLetterResponse;
import com.automation.entity.DeadLetterEntry;
import com.automation.mapper.DeadLetterMapper;
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
    private final DeadLetterMapper dlqMapper;

    /** Lists dead letter entries page by page, most recent failure first; optional {@code status} filter. */
    @GetMapping
    public Page<DeadLetterResponse> list(
            @RequestParam(required = false) DeadLetterEntry.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dlqService.list(status, PageRequest.of(page, size)).map(dlqMapper::toResponse);
    }

    /** Returns one dead letter entry. */
    @GetMapping("/{id}")
    public DeadLetterResponse get(@PathVariable String id) {
        return dlqMapper.toResponse(dlqService.findById(id));
    }

    /**
     * Starts the workflow again with the input context of the failed run and marks the entry as retried.
     * If the workflow or the failed run no longer exists, nothing starts, but the entry is still marked retried.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<DeadLetterResponse> retry(@PathVariable String id) {
        return ResponseEntity.ok(dlqMapper.toResponse(dlqRetryService.retry(id)));
    }

    /** Discards a dead letter entry: it is marked {@code DISCARDED}, not deleted; answers 204. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(@PathVariable String id) {
        dlqService.discard(id);
        return ResponseEntity.noContent().build();
    }
}
