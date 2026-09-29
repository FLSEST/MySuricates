package com.automation.modules;

import com.automation.exception.ErrorCode;
import com.automation.exception.ModuleChangeException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Which optional modules are enabled.
 *
 * <p>Startup resolution per module: explicit override ({@code automation.modules.<name>} /
 * {@code automation.hardening.<name>}) &gt; preset (feature modules only) &gt; catalog default.
 * Unknown names, unparsable values and unmet {@code requires} fail the startup — nothing
 * enables itself.
 *
 * <p>With {@code automation.modules.ui-editable=true} feature modules can also be switched at
 * runtime (Settings → Modules). Those UI overrides win over the startup configuration, take
 * effect immediately and are validated against {@code requires} like startup values. Hardening
 * switches are never switchable at runtime: they protect against exactly the caller who holds
 * the API key. With the flag off, stored UI overrides are ignored.
 */
public final class ModuleRegistry {

    /**
     * @param enabled    effective state
     * @param configured state from the startup configuration alone
     * @param overridden true if a UI override decides the state
     */
    public record ModuleStatus(ModuleCatalog module, boolean enabled, boolean configured, boolean overridden) {}

    private static final String PRESET_KEY = "preset";
    /** Reserved key below {@code automation.modules}, not a module name. */
    public static final String UI_EDITABLE_PROPERTY = ModuleKind.FEATURE.propertyPrefix() + ".ui-editable";

    private final ModulePreset preset;
    private final boolean uiEditable;
    private final Map<ModuleCatalog, Boolean> configured;
    private volatile Map<ModuleCatalog, Boolean> overrides = Map.of();
    private volatile Map<ModuleCatalog, Boolean> effective;

    private ModuleRegistry(ModulePreset preset, boolean uiEditable, Map<ModuleCatalog, Boolean> configured) {
        this.preset = preset;
        this.uiEditable = uiEditable;
        this.configured = Collections.unmodifiableMap(new EnumMap<>(configured));
        this.effective = this.configured;
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
     * @param modules   raw entries below {@code automation.modules} (including {@code preset} and
     *                  {@code ui-editable})
     * @param hardening raw entries below {@code automation.hardening}
     */
    public static ModuleRegistry of(Map<String, String> modules, Map<String, String> hardening) {
        Map<String, String> featureEntries = new LinkedHashMap<>(modules);
        ModulePreset preset = ModulePreset.parse(featureEntries.remove(PRESET_KEY));
        boolean uiEditable = removeUiEditable(featureEntries);

        Map<ModuleCatalog, Boolean> result = new EnumMap<>(ModuleCatalog.class);
        for (ModuleCatalog module : ModuleCatalog.values()) {
            result.put(module, module.kind() == ModuleKind.FEATURE ? preset.enables(module) : module.defaultEnabled());
        }
        applyOverrides(ModuleKind.FEATURE, featureEntries, result);
        applyOverrides(ModuleKind.HARDENING, hardening, result);
        List<String> problems = requiresProblems(result, false);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
        return new ModuleRegistry(preset, uiEditable, result);
    }

    /** Everything on — for tests and tooling that must see the whole platform. */
    public static ModuleRegistry allEnabled() {
        Map<ModuleCatalog, Boolean> all = new EnumMap<>(ModuleCatalog.class);
        Arrays.stream(ModuleCatalog.values()).forEach(m -> all.put(m, true));
        return new ModuleRegistry(ModulePreset.FULL, false, all);
    }

    public boolean isEnabled(ModuleCatalog module) {
        return effective.get(module);
    }

    public ModulePreset preset() {
        return preset;
    }

    /** {@code automation.modules.ui-editable}: may feature modules be switched at runtime? */
    public boolean uiEditable() {
        return uiEditable;
    }

    /** Current UI overrides (feature modules only; empty unless UI-editable). */
    public Map<ModuleCatalog, Boolean> uiOverrides() {
        return overrides;
    }

    public List<ModuleStatus> describe() {
        return Arrays.stream(ModuleCatalog.values()).map(this::status).toList();
    }

    public ModuleStatus status(ModuleCatalog module) {
        return new ModuleStatus(module, isEnabled(module), configured.get(module), overrides.containsKey(module));
    }

    /**
     * Validates a complete set of UI overrides without applying it. Overrides equal to the
     * startup value are dropped, so a later configuration change takes effect again.
     *
     * @return the normalized overrides to store and apply
     * @throws ModuleChangeException if the instance is not UI-editable, a hardening switch is
     *                               included, or the result violates {@code requires}
     */
    public Map<ModuleCatalog, Boolean> checkUiOverrides(Map<ModuleCatalog, Boolean> requested) {
        if (!uiEditable) {
            throw new ModuleChangeException(ErrorCode.MODULES_NOT_EDITABLE,
                    "Modules cannot be switched at runtime on this instance — set " + UI_EDITABLE_PROPERTY
                            + "=true in the startup configuration, or change automation.modules.* and restart",
                    Map.of("property", UI_EDITABLE_PROPERTY));
        }
        Map<ModuleCatalog, Boolean> normalized = new EnumMap<>(ModuleCatalog.class);
        for (Map.Entry<ModuleCatalog, Boolean> entry : requested.entrySet()) {
            ModuleCatalog module = entry.getKey();
            if (module.kind() != ModuleKind.FEATURE) {
                throw new ModuleChangeException(ErrorCode.INVALID_ARGUMENT, "'" + module.key()
                        + "' is a hardening switch and can only be changed in the startup configuration ("
                        + module.property() + ")");
            }
            if (!entry.getValue().equals(configured.get(module))) {
                normalized.put(module, entry.getValue());
            }
        }
        List<String> problems = requiresProblems(merge(normalized), true);
        if (!problems.isEmpty()) {
            throw new ModuleChangeException(ErrorCode.STATE_CONFLICT, String.join("; ", problems));
        }
        return Collections.unmodifiableMap(normalized);
    }

    /** Validates and applies UI overrides; effective immediately for every subsequent check. */
    public synchronized void applyUiOverrides(Map<ModuleCatalog, Boolean> requested) {
        Map<ModuleCatalog, Boolean> normalized = checkUiOverrides(requested);
        Map<ModuleCatalog, Boolean> merged = Collections.unmodifiableMap(merge(normalized));
        overrides = normalized;
        effective = merged;
    }

    /** One line for the startup log. */
    public String summary() {
        String on = Arrays.stream(ModuleCatalog.values())
                .filter(this::isEnabled)
                .map(ModuleCatalog::key)
                .collect(Collectors.joining(", "));
        String ui = !uiEditable ? "" : ", ui-editable=true, ui-overrides=" + overrides.entrySet().stream()
                .map(e -> e.getKey().key() + "=" + e.getValue())
                .collect(Collectors.joining(", ", "[", "]"));
        return "preset=" + preset.key() + ui + ", enabled=[" + on + "]";
    }

    private Map<ModuleCatalog, Boolean> merge(Map<ModuleCatalog, Boolean> uiOverrides) {
        Map<ModuleCatalog, Boolean> merged = new EnumMap<>(configured);
        merged.putAll(uiOverrides);
        return merged;
    }

    private static boolean removeUiEditable(Map<String, String> featureEntries) {
        boolean value = false;
        for (Iterator<Map.Entry<String, String>> it = featureEntries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> entry = it.next();
            if (normalize(entry.getKey()).equals("uieditable")) {
                value = parseBoolean(UI_EDITABLE_PROPERTY, entry.getValue());
                it.remove();
            }
        }
        return value;
    }

    private static void applyOverrides(ModuleKind kind, Map<String, String> entries,
                                       Map<ModuleCatalog, Boolean> result) {
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            ModuleCatalog module = ModuleCatalog.byRelaxedKey(kind, entry.getKey())
                    .orElseThrow(() -> new IllegalStateException("Unknown module '" + entry.getKey()
                            + "' under " + kind.propertyPrefix() + " — known: " + knownKeys(kind)));
            result.put(module, parseBoolean(module.property(), entry.getValue()));
        }
    }

    private static boolean parseBoolean(String property, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(property + " must be true or false, got '" + raw + "'");
        };
    }

    /** @param runtime word the fix for a settings toggle instead of for the startup configuration */
    private static List<String> requiresProblems(Map<ModuleCatalog, Boolean> result, boolean runtime) {
        List<String> problems = new ArrayList<>();
        for (ModuleCatalog module : ModuleCatalog.values()) {
            if (!result.get(module)) {
                continue;
            }
            for (ModuleCatalog required : module.requires()) {
                if (!result.get(required)) {
                    problems.add("Module '" + module.key() + "' requires '" + required.key() + "' — " + (runtime
                            ? "enable '" + required.key() + "' first, or disable '" + module.key() + "'"
                            : "set " + required.property() + "=true or " + module.property() + "=false"));
                }
            }
        }
        return problems;
    }

    private static String normalize(String s) {
        return s.replaceAll("[-._\\[\\]]", "").toLowerCase(Locale.ROOT);
    }

    private static String knownKeys(ModuleKind kind) {
        return Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.kind() == kind)
                .map(ModuleCatalog::key)
                .collect(Collectors.joining(", "));
    }
}
