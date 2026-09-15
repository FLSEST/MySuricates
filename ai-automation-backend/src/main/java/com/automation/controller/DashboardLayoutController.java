package com.automation.controller;

import com.automation.dto.response.DashboardLayoutVersionResponse;
import com.automation.dto.response.DashboardLayoutVersionSummary;
import com.automation.dto.response.PageResponse;
import com.automation.entity.DashboardLayoutVersion;
import com.automation.mapper.DashboardLayoutVersionMapper;
import com.automation.repository.DashboardLayoutVersionListItem;
import com.automation.service.dashboard.DashboardLayoutVersionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Version history of the dashboard layout. Core feature — no module gate. */
@RestController
@RequestMapping("/api/v1/dashboard/layouts")
@RequiredArgsConstructor
public class DashboardLayoutController {

    private final DashboardLayoutVersionService service;
    private final DashboardLayoutVersionMapper mapper;
    private final ObjectMapper objectMapper;

    @GetMapping
    public PageResponse<DashboardLayoutVersionSummary> list(@RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        Page<DashboardLayoutVersionListItem> result = service.list(page, size);
        return new PageResponse<>(mapper.toSummaries(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    public DashboardLayoutVersionResponse get(@PathVariable String id) throws JsonProcessingException {
        DashboardLayoutVersion version = service.get(id);
        return new DashboardLayoutVersionResponse(version.getId(), version.getCreatedAt(), version.getDeviceLabel(),
                version.getName(), version.getFormatVersion(), version.getViewCount(), version.getContentHash(),
                objectMapper.readTree(version.getContent()));
    }

    /** The body is validated field by field in DashboardLayoutValidator (unknown fields → 400). */
    @PostMapping
    public ResponseEntity<DashboardLayoutVersionSummary> save(@RequestBody JsonNode body) {
        DashboardLayoutVersionService.SaveResult result = service.save(body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(mapper.toSummary(result.version()));
    }

    @PatchMapping("/{id}")
    public DashboardLayoutVersionSummary rename(@PathVariable String id, @RequestBody JsonNode body) {
        return mapper.toSummary(service.rename(id, body));
    }
}
