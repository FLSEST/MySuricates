package com.automation.dto.response;

import com.automation.dto.IntegrityCheck;
import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;

public record PoolWorkflowEntry(
        String id,
        Map<String, String> name,
        Map<String, String> description,
        String icon,
        String category,
        /** {@code repo} | {@code portable}; {@code null} if the entry does not declare it. */
        String scope,
        List<String> tags,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        /** Reasoning effort (low/medium/high/xhigh); {@code null} if the entry names none. */
        String effort,
        String version,
        String author,
        Map<String, Object> defaultContext,
        Map<String, Object> metadata,
        String filename,
        /** The file's {@code integrity} checksum against its content (TODO b18939a6); {@code null} = not checked. */
        IntegrityCheck integrity
) {
    /** Backwards-compat constructor for call-sites written before the integrity check (TODO b18939a6). */
    public PoolWorkflowEntry(String id, Map<String, String> name, Map<String, String> description,
                             String icon, String category, String scope, List<String> tags,
                             List<BlockDefinition> blocks, String instructions, String model, String effort,
                             String version, String author, Map<String, Object> defaultContext,
                             Map<String, Object> metadata, String filename) {
        this(id, name, description, icon, category, scope, tags, blocks, instructions, model,
                effort, version, author, defaultContext, metadata, filename, null);
    }

    /** Backwards-compat constructor for call-sites written before {@code effort} existed (96640e6f). */
    public PoolWorkflowEntry(String id, Map<String, String> name, Map<String, String> description,
                             String icon, String category, String scope, List<String> tags,
                             List<BlockDefinition> blocks, String instructions, String model,
                             String version, String author, Map<String, Object> defaultContext,
                             Map<String, Object> metadata, String filename) {
        this(id, name, description, icon, category, scope, tags, blocks, instructions, model,
                null, version, author, defaultContext, metadata, filename, null);
    }
}
