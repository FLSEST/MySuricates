package com.automation.service.dashboard;

import com.automation.config.DashboardLayoutConfig;
import com.automation.dto.response.DashboardLayoutVersionListItem;
import com.automation.entity.DashboardLayoutVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.DashboardLayoutVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Version history of the dashboard layout (spec E1). Pure DB work, so plain @Transactional is fine.
 */
@Service
@RequiredArgsConstructor
public class DashboardLayoutVersionService {

    private final DashboardLayoutVersionRepository repository;
    private final DashboardLayoutValidator validator;
    private final DashboardLayoutConfig config;

    /** The stored (or deduplicated) version and whether a new row was written. */
    public record SaveResult(DashboardLayoutVersion version, boolean created) {
    }

    @Transactional(readOnly = true)
    public Page<DashboardLayoutVersionListItem> list(int page, int size) {
        return repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
    }

    @Transactional(readOnly = true)
    public DashboardLayoutVersion get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DashboardLayoutVersion", id));
    }

    /**
     * Stores a snapshot. Same content as the newest version → nothing new is written. Otherwise the
     * client must be based on the newest version, unless it forces the save after the user decided
     * to keep the local state; a stale base means another device saved in between (409).
     */
    @Transactional
    public SaveResult save(JsonNode body) {
        ValidatedLayoutSave save = validator.validateSave(body);
        Optional<DashboardLayoutVersionListItem> latest = repository.findFirstByOrderByCreatedAtDescIdDesc();
        if (latest.isPresent() && latest.get().getContentHash().equals(save.contentHash())) {
            return new SaveResult(get(latest.get().getId()), false);
        }
        if (latest.isPresent() && !save.force() && !latest.get().getId().equals(save.baseVersionId())) {
            throw new IllegalStateException("The dashboard layout was changed on another device");
        }
        Instant createdAt = Instant.now();
        if (latest.isPresent() && !createdAt.isAfter(latest.get().getCreatedAt())) {
            // Keeps "newest" unambiguous when two saves land in the same stored millisecond.
            createdAt = latest.get().getCreatedAt().plusMillis(1);
        }
        DashboardLayoutVersion version = repository.save(DashboardLayoutVersion.builder()
                .createdAt(createdAt)
                .deviceLabel(save.deviceLabel())
                .formatVersion(save.formatVersion())
                .viewCount(save.viewCount())
                .contentHash(save.contentHash())
                .content(save.canonicalContent())
                .build());
        // A configured retention of 0 (or negative) must never delete the version just saved above.
        repository.pruneUnnamed(Math.max(1, config.getRetentionCount()));
        return new SaveResult(version, true);
    }

    @Transactional
    public DashboardLayoutVersion rename(String id, JsonNode body) {
        String name = validator.validateRename(body);
        DashboardLayoutVersion version = get(id);
        version.setName(name);
        return repository.save(version);
    }
}
