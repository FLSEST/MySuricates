package com.automation.modules;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable, startup-time decision which optional modules are enabled.
 *
 * <p>Resolution per module: explicit override ({@code automation.modules.<name>} /
 * {@code automation.hardening.<name>}) &gt; preset (feature modules only) &gt; catalog default.
 * Unknown names, unparsable values and unmet {@code requires} fail the startup — nothing
 * enables itself.
 */
public final class ModuleRegistry {

    public record ModuleStatus(ModuleCatalog module, boolean enabled) {}

    private static final String PRESET_KEY = "preset";

    private final ModulePreset preset;
    private final Map<ModuleCatalog, Boolean> enabled;

    private ModuleRegistry(ModulePreset preset, Map<ModuleCatalog, Boolean> enabled) {
        this.preset = preset;
        this.enabled = Collections.unmodifiableMap(new EnumMap<>(enabled));
    }

    public static ModuleRegistry fromEnvironment(Environment environment) {
        Binder binder = Binder.get(environment);
        Map<String, String> modules = binder
                .bind(ModuleKind.FEATURE.propertyPrefix(), Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
        Map<String, String> hardening = binder
                .bind(ModuleKind.HARDENING.propertyPrefix(), Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
        return of(modules, hardening);
    }

    /**
     * @param modules   raw entries below {@code automation.modules} (including {@code preset})
     * @param hardening raw entries below {@code automation.hardening}
     */
    public static ModuleRegistry of(Map<String, String> modules, Map<String, String> hardening) {
        Map<String, String> featureEntries = new LinkedHashMap<>(modules);
        ModulePreset preset = ModulePreset.parse(featureEntries.remove(PRESET_KEY));

        Map<ModuleCatalog, Boolean> result = new EnumMap<>(ModuleCatalog.class);
        for (ModuleCatalog module : ModuleCatalog.values()) {
            result.put(module, module.kind() == ModuleKind.FEATURE ? preset.enables(module) : module.defaultEnabled());
        }
        applyOverrides(ModuleKind.FEATURE, featureEntries, result);
        applyOverrides(ModuleKind.HARDENING, hardening, result);
        validateRequires(result);
        return new ModuleRegistry(preset, result);
    }

    /** Everything on — for tests and tooling that must see the whole platform. */
    public static ModuleRegistry allEnabled() {
        Map<ModuleCatalog, Boolean> all = new EnumMap<>(ModuleCatalog.class);
        Arrays.stream(ModuleCatalog.values()).forEach(m -> all.put(m, true));
        return new ModuleRegistry(ModulePreset.FULL, all);
    }

    public boolean isEnabled(ModuleCatalog module) {
        return enabled.get(module);
    }

    public ModulePreset preset() {
        return preset;
    }

    public List<ModuleStatus> describe() {
        return Arrays.stream(ModuleCatalog.values())
                .map(m -> new ModuleStatus(m, enabled.get(m)))
                .toList();
    }

    /** One line for the startup log. */
    public String summary() {
        String on = Arrays.stream(ModuleCatalog.values())
                .filter(enabled::get)
                .map(ModuleCatalog::key)
                .collect(Collectors.joining(", "));
        return "preset=" + preset.key() + ", enabled=[" + on + "]";
    }

    private static void applyOverrides(ModuleKind kind, Map<String, String> entries,
                                       Map<ModuleCatalog, Boolean> result) {
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            ModuleCatalog module = ModuleCatalog.byRelaxedKey(kind, entry.getKey())
                    .orElseThrow(() -> new IllegalStateException("Unknown module '" + entry.getKey()
                            + "' under " + kind.propertyPrefix() + " — known: " + knownKeys(kind)));
            result.put(module, parseBoolean(module, entry.getValue()));
        }
    }

    private static boolean parseBoolean(ModuleCatalog module, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(
                    module.property() + " must be true or false, got '" + raw + "'");
        };
    }

    private static void validateRequires(Map<ModuleCatalog, Boolean> result) {
        List<String> problems = new ArrayList<>();
        for (ModuleCatalog module : ModuleCatalog.values()) {
            if (!result.get(module)) {
                continue;
            }
            for (ModuleCatalog required : module.requires()) {
                if (!result.get(required)) {
                    problems.add("Module '" + module.key() + "' requires '" + required.key() + "' — set "
                            + required.property() + "=true or " + module.property() + "=false");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    private static String knownKeys(ModuleKind kind) {
        return Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.kind() == kind)
                .map(ModuleCatalog::key)
                .collect(Collectors.joining(", "));
    }
}
