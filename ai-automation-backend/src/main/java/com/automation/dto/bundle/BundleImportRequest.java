package com.automation.dto.bundle;

import java.util.Map;

/**
 * {@code POST /api/v1/projects/import}: the bundle, where it goes, a target for every path token and
 * what happens to a workflow whose name already exists in the target project.
 *
 * @param onConflict {@code skip} (default) or {@code rename}
 */
public record BundleImportRequest(ProjectBundle bundle, TargetProject project,
                                  Map<String, String> pathMappings, String onConflict) {

    public BundleImportRequest {
        pathMappings = pathMappings == null ? Map.of() : pathMappings;
    }

    /**
     * {@code mode = new}: a new project named {@code name} at {@code path} (may be empty);
     * {@code mode = existing}: into the project {@code projectId}.
     */
    public record TargetProject(String mode, String name, String path, String projectId) {}
}
