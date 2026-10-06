package com.automation.service.system.settings;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A topic block of runtime-changeable settings. {@code ExecutionSettingsService} collects all
 * groups (order via {@code @Order}) and keeps only persisting, validating and
 * merging - a new setting changes its group, not the service.
 *
 * <p>Keys and bounds remain central in {@code ExecutionSettingKeys}; the service
 * checks every value before a group sees it.
 */
public interface RuntimeSettingsGroup {

    /** Canonical key → setter, in the order in which DB values take effect at startup. */
    Map<String, Consumer<String>> appliers();

    /** Enters the current values (camelCase keys, as the settings page reads them). */
    void currentValues(Map<String, Object> out);

    /**
     * May this (already validated) value be set? Runs for a whole batch before
     * any value is applied; throws on prohibition.
     */
    default void guard(String key, String value) {
    }
}
