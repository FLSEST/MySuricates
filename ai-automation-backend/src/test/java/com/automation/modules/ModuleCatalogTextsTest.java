package com.automation.modules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module display texts live once, as JSON per language next to the frontend
 * ({@code ai-automation-frontend/src/data/modules.<lang>.json}). Two consumers read them: the
 * frontend imports the JSON directly, and {@code scripts/setup.sh} reads the Bash file generated
 * here — setup runs before any build, so it can rely on neither a backend nor {@code jq}.
 *
 * <p>This test is the guard for both: every {@link ModuleCatalog} key must be described in every
 * language (and no language may carry an unknown key), and the checked-in
 * {@code scripts/lib/module-catalog.generated.sh} must be exactly what the catalog plus those texts
 * produce. Regenerate with {@code -Dmodule.catalog.write=true}.
 */
class ModuleCatalogTextsTest {

    /** Languages the module texts are maintained in. A new file alone is not enough — list it here. */
    private static final List<String> LANGUAGES = List.of("de", "en");

    private static final Path FRONTEND_DATA = Path.of("..", "ai-automation-frontend", "src", "data");
    private static final Path GENERATED = Path.of("..", "scripts", "lib", "module-catalog.generated.sh");
    private static final String WRITE_PROPERTY = "module.catalog.write";
    private static final String REGENERATE_HINT = "Neu erzeugen: cd ai-automation-backend && ./mvnw test"
            + " -Dtest=ModuleCatalogTextsTest -D" + WRITE_PROPERTY + "=true";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path textFile(String language) {
        return FRONTEND_DATA.resolve("modules." + language + ".json");
    }

    /** {@code key -> {name, description}}, in file order. */
    private static Map<String, Map<String, String>> readTexts(String language) throws IOException {
        Path path = textFile(language);
        assertThat(path).as("Modultexte für '%s' fehlen (%s)", language, path).exists();
        return MAPPER.readValue(Files.readString(path), new TypeReference<LinkedHashMap<String, Map<String, String>>>() {
        });
    }

    private static Set<String> catalogKeys() {
        return Arrays.stream(ModuleCatalog.values())
                .map(ModuleCatalog::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void everyLanguageDescribesExactlyTheCatalog() throws IOException {
        Set<String> expected = catalogKeys();

        for (String language : LANGUAGES) {
            Map<String, Map<String, String>> texts = readTexts(language);

            assertThat(texts.keySet())
                    .as("modules.%s.json deckt den Katalog nicht ab (%s)", language, REGENERATE_HINT)
                    .containsExactlyInAnyOrderElementsOf(expected);

            texts.forEach((key, entry) -> {
                assertThat(entry.get("name"))
                        .as("modules.%s.json → %s.name", language, key)
                        .isNotNull().isNotBlank();
                assertThat(entry.get("description"))
                        .as("modules.%s.json → %s.description", language, key)
                        .isNotNull().isNotBlank();
            });
        }
    }

    @Test
    void languagesCoverEachOtherBothWays() throws IOException {
        for (String a : LANGUAGES) {
            for (String b : LANGUAGES) {
                assertThat(readTexts(a).keySet())
                        .as("Schlüssel aus modules.%s.json fehlen in modules.%s.json", b, a)
                        .containsExactlyInAnyOrderElementsOf(readTexts(b).keySet());
            }
        }
    }

    @Test
    void generatedBashFileIsUpToDate() throws IOException {
        Map<String, Map<String, Map<String, String>>> byLanguage = new LinkedHashMap<>();
        for (String language : LANGUAGES) {
            byLanguage.put(language, readTexts(language));
        }
        String expected = render(byLanguage);

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(GENERATED.getParent());
            Files.writeString(GENERATED, expected);
        }

        assertThat(GENERATED).as("%s fehlt. %s", GENERATED, REGENERATE_HINT).exists();
        assertThat(Files.readString(GENERATED))
                .as("%s ist nicht mehr das, was Katalog + Modultexte ergeben. %s", GENERATED, REGENERATE_HINT)
                .isEqualTo(expected);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private static String render(Map<String, Map<String, Map<String, String>>> byLanguage) {
        StringBuilder out = new StringBuilder();
        out.append("""
                #!/usr/bin/env bash
                # generiert von ModuleCatalogTextsTest — nicht von Hand ändern
                #
                # Quelle: com.automation.modules.ModuleCatalog (Schlüssel, Stufe, Reifegrad, Default, Risiken,
                # Abhängigkeiten) und die Anzeigetexte aus ai-automation-frontend/src/data/modules.<lang>.json.
                # Neu erzeugen:
                #   cd ai-automation-backend && ./mvnw test -Dtest=ModuleCatalogTextsTest -Dmodule.catalog.write=true
                #
                # Reine Daten, keine Funktionen — eingelesen von scripts/lib/module-preset.sh, das vor jedem
                # Build läuft und deshalb weder ein Backend noch jq voraussetzen darf.
                """);

        out.append('\n').append(array("MODULE_CATALOG_LANGS", byLanguage.keySet().stream().toList()));
        out.append('\n').append(array("MODULE_CATALOG_KEYS", keys(m -> true)));
        out.append('\n').append(array("MODULE_CATALOG_FEATURE_KEYS", keys(m -> m.kind() == ModuleKind.FEATURE)));
        out.append('\n').append(array("MODULE_CATALOG_HARDENING_KEYS", keys(m -> m.kind() == ModuleKind.HARDENING)));

        out.append('\n').append(map("MODULE_CATALOG_TIER", m -> lower(m.tier().name())));
        out.append('\n').append(map("MODULE_CATALOG_MATURITY", m -> lower(m.maturity().name())));
        out.append('\n').append(map("MODULE_CATALOG_DEFAULT", m -> Boolean.toString(m.defaultEnabled())));
        out.append('\n').append(map("MODULE_CATALOG_PROPERTY", ModuleCatalog::property));
        out.append('\n').append(map("MODULE_CATALOG_RISKS", m -> m.risks().stream()
                .map(r -> lower(r.name())).sorted().collect(Collectors.joining(" "))));
        out.append('\n').append(map("MODULE_CATALOG_REQUIRES", m -> m.requires().stream()
                .map(ModuleCatalog::key).collect(Collectors.joining(" "))));

        out.append('\n').append(texts("MODULE_TEXT_NAME", byLanguage, "name"));
        out.append('\n').append(texts("MODULE_TEXT_DESC", byLanguage, "description"));
        return out.toString();
    }

    private static List<String> keys(java.util.function.Predicate<ModuleCatalog> filter) {
        return Arrays.stream(ModuleCatalog.values()).filter(filter).map(ModuleCatalog::key).toList();
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String array(String name, List<String> values) {
        StringBuilder sb = new StringBuilder(name).append("=(\n");
        values.forEach(v -> sb.append("  ").append(v).append('\n'));
        return sb.append(")\n").toString();
    }

    private static String map(String name, Function<ModuleCatalog, String> value) {
        StringBuilder sb = new StringBuilder("declare -gA ").append(name).append("=(\n");
        for (ModuleCatalog module : ModuleCatalog.values()) {
            sb.append("  [").append(module.key()).append("]=\"").append(quote(value.apply(module))).append("\"\n");
        }
        return sb.append(")\n").toString();
    }

    /** One associative array per field, keyed {@code <lang>:<module>} so no bash namerefs are needed. */
    private static String texts(String name, Map<String, Map<String, Map<String, String>>> byLanguage, String field) {
        StringBuilder sb = new StringBuilder("declare -gA ").append(name).append("=(\n");
        byLanguage.forEach((language, texts) -> {
            sb.append("  # ").append(language).append('\n');
            for (ModuleCatalog module : ModuleCatalog.values()) {
                String value = texts.getOrDefault(module.key(), Map.of()).getOrDefault(field, "");
                sb.append("  [").append(language).append(':').append(module.key()).append("]=\"")
                        .append(quote(value)).append("\"\n");
            }
        });
        return sb.append(")\n").toString();
    }

    /** Everything bash would still expand inside double quotes. */
    private static String quote(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
    }
}
