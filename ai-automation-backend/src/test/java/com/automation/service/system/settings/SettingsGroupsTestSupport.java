package com.automation.service.system.settings;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Small helpers for the tests of the RuntimeSettingsGroup classes. */
final class SettingsGroupsTestSupport {

    private SettingsGroupsTestSupport() {}

    static void apply(RuntimeSettingsGroup group, String key, String value) {
        Consumer<String> applier = group.appliers().get(key);
        if (applier == null) throw new AssertionError("Kein Applier für " + key);
        applier.accept(value);
    }

    static Map<String, Object> values(RuntimeSettingsGroup group) {
        Map<String, Object> out = new HashMap<>();
        group.currentValues(out);
        return out;
    }
}
