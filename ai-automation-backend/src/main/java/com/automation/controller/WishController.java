package com.automation.controller;

import com.automation.dto.request.CreateWishRequest;
import com.automation.dto.request.WishRespondRequest;
import com.automation.dto.response.WishResponse;
import com.automation.entity.Wish;
import com.automation.mapper.WishMapper;
import com.automation.service.wish.WishFulfillmentLoop;
import com.automation.service.wish.WishService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/wishes")
@RequiredArgsConstructor
public class WishController {

    private final WishService wishService;
    private final WishFulfillmentLoop fulfillmentLoop;
    private final WishMapper wishMapper;

    @PostMapping
    public ResponseEntity<WishResponse> createWish(@Valid @RequestBody CreateWishRequest request) {
        Wish wish = wishService.create(request.text(), request.projectId(), request.maxIterations(), request.referenceWorkflowIds(), request.autoRefine());
        fulfillmentLoop.fulfillAsync(wish.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(wishMapper.toResponse(wish));
    }

    @GetMapping
    public List<WishResponse> listWishes() {
        return wishMapper.toResponseList(wishService.findAll());
    }

    @GetMapping("/{id}")
    public WishResponse getWish(@PathVariable String id) {
        return wishMapper.toResponse(wishService.findById(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelWish(@PathVariable String id) {
        fulfillmentLoop.cancel(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/respond")
    public WishResponse respondToWish(@PathVariable String id, @Valid @RequestBody WishRespondRequest request) {
        Wish wish = wishService.respond(id, request);
        return wishMapper.toResponse(wish);
    }

    @PostMapping("/{id}/crystallize")
    public Map<String, String> crystallizeWish(@PathVariable String id) {
        String templateId = wishService.crystallize(id);
        return Map.of("templateId", templateId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWish(@PathVariable String id) {
        wishService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
