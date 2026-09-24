package com.automation.service.system;

/**
 * A setting was written through {@link SystemSettingsService#setSetting}. Services that cache a setting
 * listen for its key and re-read it — before, the settings service called each of them directly, which
 * tied {@code service.system} to every consumer package.
 *
 * <p>Published synchronously once the value is stored. The write is a single auto-commit statement
 * without a surrounding transaction, so a plain {@code @EventListener} reads the new value, and an
 * exception in a listener reaches the caller as the direct call did. Listeners only re-read a cached
 * value; anything slow belongs in an {@code @Async} listener.
 */
public record AppSettingChangedEvent(String key) {}
