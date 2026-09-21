package com.automation.service.pool;

import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads one pool file as a {@link PoolWorkflowEntry} in one language. A bilingual file
 * ({@code "i18n": ["de","en"]}) gets its blocks and instructions resolved to {@code locale}
 * ({@link LocalizedJson}); name and description stay maps. Extracted from {@link WorkflowPoolService}.
 */
@Slf4j
final class PoolFileParser {

    private PoolFileParser() {
    }

    /** @return the entry, or {@code null} when a required field is missing */
    static PoolWorkflowEntry parse(Path file, ObjectMapper objectMapper, String locale) throws IOException {
        JsonNode root = objectMapper.readTree(file.toFile());
        JsonNode resolved = LocalizedJson.resolveDefinition(root, LocalizedJson.normalize(locale));
        Map<String, Object> raw = objectMapper.convertValue(resolved, new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        Map<String, String> name = (Map<String, String>) raw.get("name");
        if (name == null || name.isEmpty()) {
            log.warn("Pool file missing required 'name' field: {}", file.getFileName());
            return null;
        }

        Object blocksRaw = raw.get("blocks");
        if (blocksRaw == null) {
            log.warn("Pool file missing required 'blocks' field: {}", file.getFileName());
            return null;
        }
        List<BlockDefinition> blocks = objectMapper.convertValue(blocksRaw, new TypeReference<>() {});

        String filename = file.getFileName().toString();
        String id = filename.replace(".json", "");

        @SuppressWarnings("unchecked")
        Map<String, String> description = raw.containsKey("description")
                ? (Map<String, String>) raw.get("description")
                : Map.of();

        String icon = (String) raw.getOrDefault("icon", "📋");
        String category = (String) raw.get("category");
        // Bewusst ohne Default: ein Eintrag ohne 'scope' soll im Pool-Browser als
        // "nicht deklariert" erkennbar sein, nicht als portabel durchgehen.
        String scope = raw.get("scope") instanceof String s ? s : null;

        @SuppressWarnings("unchecked")
        List<String> tags = raw.containsKey("tags")
                ? (List<String>) raw.get("tags")
                : List.of();

        String instructions = (String) raw.get("instructions");
        String model = (String) raw.get("model");
        String effort = (String) raw.get("effort");
        String version = (String) raw.getOrDefault("version", "1.0.0");
        String author = (String) raw.get("author");

        @SuppressWarnings("unchecked")
        Map<String, Object> defaultContext = raw.containsKey("defaultContext")
                ? (Map<String, Object>) raw.get("defaultContext")
                : null;

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = raw.containsKey("metadata")
                ? (Map<String, Object>) raw.get("metadata")
                : null;

        return new PoolWorkflowEntry(
                id, name, description, icon, category, scope, tags,
                blocks, instructions, model, effort, version, author,
                defaultContext, metadata, filename
        );
    }
}
