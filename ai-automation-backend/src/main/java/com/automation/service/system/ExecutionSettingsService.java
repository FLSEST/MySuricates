package com.automation.service.system;

import com.automation.repository.AppSettingsRepository;
import com.automation.service.system.settings.RuntimeSettingsGroup;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies runtime-tunable execution settings: DB overrides at startup, validated updates from
 * the settings UI, and the current-values snapshot. Which keys exist and what values are legal
 * lives in {@link ExecutionSettingKeys}; the key → runtime-setter tables live in the
 * {@link RuntimeSettingsGroup} beans (one per topic), so a new setting touches its group, not
 * this class.
 */
@Service
@Slf4j
public class ExecutionSettingsService {

    private final AppSettingsRepository appSettingsRepository;
    private final List<RuntimeSettingsGroup> groups;

    /** Canonical key → runtime setter, in the order the DB overrides are applied at startup. */
    private final Map<String, Consumer<String>> appliers;

    /**
     * Nur für den Wächter-Test (TODO fc0994d6): Ein Applier ohne Eintrag in
     * {@link ExecutionSettingKeys#BOUNDS} ist über die öffentliche API unerreichbar, weil
     * {@code validateAndApply} zuerst {@code resolve(key)} ruft — die sieben
     * {@code request-metrics-*}-Schlüssel waren so monatelang tote Registrierungen.
     */
    Set<String> applierKeys() {
        return appliers.keySet();
    }

    /** {@code groups} in {@code @Order}-Reihenfolge, wie Spring sie injiziert. */
    public ExecutionSettingsService(AppSettingsRepository appSettingsRepository, List<RuntimeSettingsGroup> groups) {
        this.appSettingsRepository = appSettingsRepository;
        this.groups = List.copyOf(groups);
        this.appliers = mergeAppliers(this.groups);
    }

    private static Map<String, Consumer<String>> mergeAppliers(List<RuntimeSettingsGroup> groups) {
        Map<String, Consumer<String>> merged = new LinkedHashMap<>();
        for (RuntimeSettingsGroup group : groups) {
            group.appliers().forEach((key, applier) -> {
                if (merged.putIfAbsent(key, applier) != null) {
                    throw new IllegalStateException("Setting '" + key + "' is registered by two groups (second: "
                            + group.getClass().getSimpleName() + ")");
                }
            });
        }
        return merged;
    }

    @PostConstruct
    public void init() {
        // Apply DB overrides if present
        appliers.forEach(this::applyIfPresent);

        Map<String, Object> current = getCurrentSettings();
        log.info("Execution settings initialized: maxConcurrent={}, maxCalls={}, timeout={}s, delay={}ms, chainTimeout={}min, rateLimit={}/{}rpm, assistantChatTimeout={}s",
                current.get("executionMaxConcurrent"), current.get("claudeMaxConcurrentCalls"),
                current.get("claudeTimeoutSeconds"), current.get("claudeInterCallDelayMs"),
                current.get("chainWorkflowTimeoutMinutes"),
                Integer.valueOf(1).equals(current.get("rateLimitEnabled")) ? "ON" : "OFF",
                current.get("rateLimitRequestsPerMinute"), current.get("assistantChatTimeoutSeconds"));
    }

    public void updateAll(Map<String, String> settings) {
        // Refuse before anything is applied — a batch must not land half-way (TODO 8dbd7e10)
        settings.forEach(this::check);
        for (var entry : settings.entrySet()) {
            validateAndApply(entry.getKey(), entry.getValue());
        }
    }

    public void validateAndApply(String key, String value) {
        key = check(key, value);

        appSettingsRepository.set(key, value);
        appliers.get(key).accept(value);
        log.info("Execution setting updated: {} = {}", key, value);
    }

    /** Löst den Schlüssel auf, prüft den Wert und fragt jede Gruppe, ob er gesetzt werden darf. */
    private String check(String key, String value) {
        String canonical = ExecutionSettingKeys.resolve(key);
        ExecutionSettingKeys.validate(canonical, value);
        groups.forEach(group -> group.guard(canonical, value));
        return canonical;
    }

    public Map<String, Object> getCurrentSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        groups.forEach(group -> group.currentValues(result));
        return result;
    }

    /**
     * Ein einzelner unbrauchbarer DB-Wert darf den Start nicht kippen: {@link #init} laeuft in
     * {@code @PostConstruct}, eine Exception hier waere ein Startabbruch. Deshalb umschliesst der
     * Fang auch das Anwenden selbst, nicht nur die Pruefung.
     */
    private void applyIfPresent(String key, Consumer<String> applier) {
        appSettingsRepository.get(key).filter(v -> !v.isBlank()).ifPresent(v -> {
            try {
                ExecutionSettingKeys.validate(key, v);
                applier.accept(v);
                log.debug("Applied DB override for {}: {}", key, v);
            } catch (RuntimeException e) {
                log.warn("Ignoring unusable DB value for {}: {} ({})", key, v, e.toString());
            }
        });
    }
}
