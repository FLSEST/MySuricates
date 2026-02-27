package com.automation.dto.response;

import java.util.List;

public record SyncPreview(
        List<SyncPreviewItem> projects,
        List<SyncPreviewItem> workflows,
        List<SyncPreviewItem> skills
) {}
