package com.automation.controller;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.SyncTargetResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.staging.StagedSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Definition sync of the staged setup: Dev → Test / Prod (TODO 84a78bb7). The targets come from the
 * startup configuration ({@code automation.staging.targets}), written by {@code scripts/setup.sh --mode staged}.
 */
@RequiresModule(ModuleCatalog.STAGING)
@RestController
@RequestMapping("/api/v1/staging/targets")
@RequiredArgsConstructor
public class StagedSyncController {

    private final StagedSyncService stagedSyncService;

    /**
     * Lists the configured sync targets ({@code test}, {@code prod}) with URL, key presence and reachability.
     * Targets that are not configured are left out.
     */
    @GetMapping
    public List<SyncTargetResponse> targets() {
        return stagedSyncService.targets();
    }

    /**
     * Previews what a sync from this instance to {@code test} or {@code prod} would transfer; for {@code prod} only sealed definitions.
     * A target that is not configured, has no key file or is unreachable is 409.
     */
    @GetMapping("/{target}/sync-preview")
    public SyncPreview syncPreview(@PathVariable String target) {
        return stagedSyncService.preview(target);
    }

    /**
     * Transfers projects, workflows, skills and chains to {@code test} or {@code prod}; an optional body narrows the selection by ids.
     * For {@code prod} the selection is narrowed to sealed definitions; errors as for the preview.
     */
    @PostMapping("/{target}/sync-definitions")
    public SyncResult syncDefinitions(@PathVariable String target,
                                      @RequestBody(required = false) SyncDefinitionsRequest request) {
        return stagedSyncService.sync(target, request);
    }
}
