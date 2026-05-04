package com.automation.service.ai;

import com.automation.config.HostPiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * Reads the user's local pi runtime config to determine which backend providers
 * are actually authenticated/configured on this host. Used by the UI to filter
 * the static pi-model preset list down to backends the user can actually call.
 *
 * <p>No CLI invocation — purely reads files under {@code ~/.pi/agent}:
 * <ul>
 *   <li>{@code auth.json}    — top-level keys are authenticated provider names (OAuth or API-key)</li>
 *   <li>{@code settings.json} — defaultProvider + defaultModel</li>
 *   <li>{@code models.json}  — optional, custom-provider definitions (e.g. ollama, lmstudio, vllm)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PiCatalogService {

    private final HostPiConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PiCatalog getCatalog() {
        Path baseDir = Path.of(config.getConfigDir());
        if (!Files.isDirectory(baseDir)) {
            log.debug("Pi config dir not found at {}", baseDir);
            return PiCatalog.notInstalled();
        }

        List<String> authenticated = readAuthenticatedProviders(baseDir.resolve("auth.json"));
        Settings settings = readSettings(baseDir.resolve("settings.json"));
        List<String> custom = readCustomProviders(baseDir.resolve("models.json"));

        return new PiCatalog(
                true,
                authenticated,
                custom,
                settings.defaultProvider(),
                settings.defaultModel()
        );
    }

    private List<String> readAuthenticatedProviders(Path authFile) {
        if (!Files.isRegularFile(authFile)) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(authFile.toFile());
            if (!root.isObject()) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<>();
            Iterator<String> it = root.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
            }
            // Sort so order is stable across calls (auth.json field order is preserved by Jackson but
            // a TreeSet → List gives a predictable UI ordering for the user).
            return new ArrayList<>(new TreeSet<>(names));
        } catch (IOException e) {
            log.warn("Failed to read pi auth.json at {}: {}", authFile, e.toString());
            return Collections.emptyList();
        }
    }

    private Settings readSettings(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) {
            return Settings.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(settingsFile.toFile());
            return new Settings(
                    textOrNull(root.get("defaultProvider")),
                    textOrNull(root.get("defaultModel"))
            );
        } catch (IOException e) {
            log.warn("Failed to read pi settings.json at {}: {}", settingsFile, e.toString());
            return Settings.empty();
        }
    }

    /**
     * Custom-provider names from models.json. Schema is flexible; we look for top-level
     * provider keys, falling back to scanning entries with a "provider" field.
     */
    private List<String> readCustomProviders(Path modelsFile) {
        if (!Files.isRegularFile(modelsFile)) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(modelsFile.toFile());
            TreeSet<String> names = new TreeSet<>();
            if (root.isObject()) {
                JsonNode providers = root.get("providers");
                if (providers != null && providers.isObject()) {
                    providers.fieldNames().forEachRemaining(names::add);
                } else {
                    // Top-level keys treated as provider names when there is no explicit "providers" wrapper.
                    root.fieldNames().forEachRemaining(names::add);
                }
            } else if (root.isArray()) {
                for (JsonNode entry : root) {
                    String p = textOrNull(entry.get("provider"));
                    if (p != null) names.add(p);
                }
            }
            return new ArrayList<>(names);
        } catch (IOException e) {
            log.warn("Failed to read pi models.json at {}: {}", modelsFile, e.toString());
            return Collections.emptyList();
        }
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && node.isTextual()) ? node.asText() : null;
    }

    private record Settings(String defaultProvider, String defaultModel) {
        static Settings empty() { return new Settings(null, null); }
    }

    public record PiCatalog(
            boolean installed,
            List<String> authenticatedProviders,
            List<String> customProviders,
            String defaultProvider,
            String defaultModel
    ) {
        public static PiCatalog notInstalled() {
            return new PiCatalog(false, List.of(), List.of(), null, null);
        }
    }
}
