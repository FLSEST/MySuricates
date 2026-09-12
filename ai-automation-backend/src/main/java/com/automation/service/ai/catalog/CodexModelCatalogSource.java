package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers codex models from two offline sources, freshest first:
 * <ol>
 *   <li>{@code <codexHome>/models_cache.json} — written by codex after its last server sync
 *       ({@code {fetched_at, etag, client_version, models[]}})</li>
 *   <li>{@code codex debug models --bundled} — the catalog compiled into the installed binary
 *       ({@code {models[]}})</li>
 * </ol>
 * Only models with {@code visibility == "list"} are offered, ordered by {@code priority}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodexModelCatalogSource implements ModelCatalogSource {

    public static final String SOURCE_CACHE = "codex-cache";
    public static final String SOURCE_CLI = "codex-cli";
    static final String CACHE_FILE = "models_cache.json";
    static final List<String> COMMAND = List.of("codex", "debug", "models", "--bundled");

    private final ModelCatalogConfig config;
    private final CliCommandRunner runner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String providerId() {
        return "CODEX";
    }

    @Override
    public ModelCatalog fetch() {
        ModelCatalog fromCache = readCacheFile();
        if (fromCache != null) {
            return fromCache;
        }
        try {
            String output = runner.run(COMMAND, config.getProbeTimeoutSeconds());
            JsonNode root = objectMapper.readTree(output);
            List<ModelCatalog.ModelOption> models = parseModels(root);
            if (models.isEmpty()) {
                return ModelCatalog.unavailable(providerId(), SOURCE_CLI, "codex reported no listable models");
            }
            return ModelCatalog.available(providerId(), SOURCE_CLI, Instant.now(), models);
        } catch (IOException e) {
            log.warn("codex model catalog unavailable: {}", e.getMessage());
            return ModelCatalog.unavailable(providerId(), SOURCE_CLI, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("codex model catalog probe failed", e);
            return ModelCatalog.unavailable(providerId(), SOURCE_CLI, e.toString());
        }
    }

    /** Returns the catalog from {@code models_cache.json}, or null when the file is absent/empty/broken. */
    private ModelCatalog readCacheFile() {
        Path file = Path.of(config.getCodexHome(), CACHE_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            List<ModelCatalog.ModelOption> models = parseModels(root);
            if (models.isEmpty()) {
                return null;
            }
            return ModelCatalog.available(providerId(), SOURCE_CACHE, parseFetchedAt(root), models);
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable {}: {}", file, e.getMessage());
            return null;
        }
    }

    static Instant parseFetchedAt(JsonNode root) {
        JsonNode node = root.get("fetched_at");
        if (node == null || node.isNull()) {
            return Instant.now();
        }
        try {
            if (node.isNumber()) {
                return Instant.ofEpochSecond(node.asLong());
            }
            return Instant.parse(node.asText());
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    /** Keeps {@code visibility == "list"} entries, ordered by ascending {@code priority}. */
    static List<ModelCatalog.ModelOption> parseModels(JsonNode root) {
        List<ModelCatalog.ModelOption> result = new ArrayList<>();
        JsonNode models = root == null ? null : root.get("models");
        if (models == null || !models.isArray()) {
            return result;
        }
        List<JsonNode> listed = new ArrayList<>();
        for (JsonNode m : models) {
            String slug = text(m, "slug");
            if (slug == null || slug.isBlank()) {
                continue;
            }
            if (!"list".equalsIgnoreCase(text(m, "visibility"))) {
                continue;
            }
            listed.add(m);
        }
        listed.sort(Comparator.comparingInt(m -> m.path("priority").asInt(Integer.MAX_VALUE)));
        for (JsonNode m : listed) {
            String slug = text(m, "slug");
            String label = text(m, "display_name");
            JsonNode levels = m.get("supported_reasoning_levels");
            Boolean thinking = levels != null && levels.isArray() ? levels.size() > 0 : null;
            JsonNode ctx = m.get("context_window");
            Integer context = ctx != null && ctx.isNumber() ? ctx.asInt() : null;
            result.add(new ModelCatalog.ModelOption(
                    slug,
                    label == null || label.isBlank() ? slug : label,
                    text(m, "description"),
                    context,
                    thinking));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
