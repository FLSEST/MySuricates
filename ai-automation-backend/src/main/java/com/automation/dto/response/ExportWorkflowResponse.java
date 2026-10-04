package com.automation.dto.response;

import com.automation.entity.BlockDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExportWorkflowResponse(
        String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        String version,
        Map<String, Object> defaultContext,
        Map<String, Object> metadata,
        Instant exportedAt,
        /** {@code sha256:<hex>} over the content the import takes over ({@code DefinitionIntegrity#ofExport}). */
        String integrity
) {
    /** Backwards-compat constructor for call-sites written before the integrity checksum (TODO b18939a6). */
    public ExportWorkflowResponse(String name, String description, String icon, List<BlockDefinition> blocks,
                                  String instructions, String model, String version,
                                  Map<String, Object> defaultContext, Map<String, Object> metadata,
                                  Instant exportedAt) {
        this(name, description, icon, blocks, instructions, model, version, defaultContext, metadata,
                exportedAt, null);
    }

    public ExportWorkflowResponse withIntegrity(String checksum) {
        return new ExportWorkflowResponse(name, description, icon, blocks, instructions, model, version,
                defaultContext, metadata, exportedAt, checksum);
    }
}
