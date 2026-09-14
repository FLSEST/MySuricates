package com.automation.modules;

import com.automation.repository.AppSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists module switches made in the settings UI ({@code app_settings}, one JSON object
 * {@code {"webhooks": true}}). Only read when {@code automation.modules.ui-editable} is on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModuleOverrideStore {

    static final String SETTINGS_KEY = "modules.ui-overrides";
    private static final TypeReference<Map<String, Boolean>> TYPE = new TypeReference<>() {};

    private final AppSettingsRepository settings;
    private final ObjectMapper objectMapper;

    /** Stored overrides; unknown or unreadable entries are skipped with a warning, never fatal. */
    public Map<ModuleCatalog, Boolean> load() {
        Map<ModuleCatalog, Boolean> result = new EnumMap<>(ModuleCatalog.class);
        String raw = settings.get(SETTINGS_KEY).orElse(null);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        try {
            objectMapper.readValue(raw, TYPE).forEach((key, value) -> ModuleCatalog.byKey(key).ifPresentOrElse(
                    module -> result.put(module, Boolean.TRUE.equals(value)),
                    () -> log.warn("Ignoring stored UI override for unknown module '{}'", key)));
        } catch (JsonProcessingException e) {
            log.warn("Ignoring unreadable {} setting: {}", SETTINGS_KEY, e.getOriginalMessage());
        }
        return result;
    }

    public void save(Map<ModuleCatalog, Boolean> overrides) {
        if (overrides.isEmpty()) {
            settings.delete(SETTINGS_KEY);
            return;
        }
        Map<String, Boolean> byKey = new LinkedHashMap<>();
        overrides.forEach((module, enabled) -> byKey.put(module.key(), enabled));
        try {
            settings.set(SETTINGS_KEY, objectMapper.writeValueAsString(byKey));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize module overrides", e);
        }
    }
}
