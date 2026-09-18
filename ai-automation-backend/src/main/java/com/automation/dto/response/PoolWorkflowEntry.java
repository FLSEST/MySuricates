package com.automation.dto.response;

import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;

public record PoolWorkflowEntry(
        String id,
        Map<String, String> name,
        Map<String, String> description,
        String icon,
        String category,
        /** {@code repo} | {@code portable}; {@code null}, wenn der Eintrag es nicht deklariert. */
        String scope,
        List<String> tags,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        String version,
        String author,
        Map<String, Object> defaultContext,
        Map<String, Object> metadata,
        String filename
) {}
