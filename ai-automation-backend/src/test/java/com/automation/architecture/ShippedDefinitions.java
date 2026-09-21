package com.automation.architecture;

import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Language variants of shipped definitions, for the gates (TODO a129a661).
 *
 * <p>A bilingual template or pool file ({@code "i18n": ["de", "en"]}) carries {@code {de, en}} maps
 * in its steps. A gate that reads a step field as a string would skip such a map silently and lose
 * coverage without anyone noticing. So the gates check every language variant, resolved with the
 * same {@link LocalizedJson} the loader and the pool use.
 */
public final class ShippedDefinitions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One language variant of a file; {@code label} is {@code file.json} or {@code file.json[de]}. */
    public record Variant(String label, JsonNode root) {}

    /** Config fields that can be run through a shell, see {@link #isShellField}. */
    public static final List<String> SHELL_FIELDS =
            List.of("command", "condition", "then_command", "else_command", "items_command", "condition_command");

    private ShippedDefinitions() {
    }

    /**
     * Whether {@code field} on a block of {@code type} (with {@code config}) is actually run
     * through a shell. {@code items_command} on {@code foreach} always is (it only ever collects
     * iteration targets, see {@code ForEachBlockExecutor}); every other shell field depends on the
     * block being type {@code shell} itself, or carrying {@code execution_mode: shell} (foreach,
     * while, conditional, retry, and a switch-nested shell/simple block — {@code ExecutionMode}).
     */
    public static boolean isShellField(String field, String type, JsonNode config) {
        if (!SHELL_FIELDS.contains(field)) {
            return false;
        }
        if ("items_command".equals(field)) {
            return "foreach".equals(type);
        }
        return "shell".equals(type) || "shell".equals(config.path("execution_mode").asText(null));
    }

    /** One variant per declared language, or the file itself when it has no {@code i18n} marker. */
    public static List<Variant> variants(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        String name = file.getFileName().toString();
        List<String> locales = LocalizedJson.declaredLocales(root);
        if (locales.isEmpty()) {
            return List.of(new Variant(name, root));
        }
        return locales.stream()
                .map(locale -> new Variant(name + "[" + locale + "]", LocalizedJson.resolveDefinition(root, locale)))
                .toList();
    }
}
