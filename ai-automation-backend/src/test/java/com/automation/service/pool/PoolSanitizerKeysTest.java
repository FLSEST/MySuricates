package com.automation.service.pool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Koppelt die Instanz-Schlüsselliste des Exports an die des Hygiene-Gates (TODO 96640e6f).
 *
 * <p>{@link PoolExportSanitizer} entfernt diese Schlüssel beim Export, {@code
 * scripts/ci/pool-neutrality.sh} beanstandet sie im getrackten Bestand — vorher zwei getrennte
 * Listen ohne Kopplung: ein neuer Schlüssel im Sanitizer fehlte im Gate und umgekehrt, und
 * niemandem fiel es auf.
 *
 * <p>Vorbild ist {@code ModuleCatalogTextsTest} mit {@code scripts/lib/module-catalog.generated.sh}:
 * der Test rendert die Bash-Zeile, das Skript liest die erzeugte Datei. Driftet eine Seite, wird
 * dieser Test rot.
 */
class PoolSanitizerKeysTest {

    private static final Path GENERATED = Path.of("..", "scripts", "lib", "pool-sanitizer.generated.sh");

    private static final String WRITE_PROPERTY = "pool.sanitizer.write";

    private static final String REGENERATE_HINT =
            "Neu erzeugen: cd ai-automation-backend && ./mvnw test -Dtest=PoolSanitizerKeysTest -Dpool.sanitizer.write=true";

    @Test
    void sanitizerKeysAreCamelCaseAndUnique() {
        List<String> keys = PoolExportSanitizer.instanceReferenceKeys();

        assertThat(keys).isNotEmpty().doesNotHaveDuplicates();
        assertThat(keys).allSatisfy(key -> assertThat(key)
                .as("Schlüssel stehen in camelCase, damit die Bash-Liste beide Schreibweisen erzeugen kann")
                .matches("[a-z]+([A-Z][a-z]+)*"));
    }

    @Test
    void everySanitizerKeyIsAlsoCheckedByTheGate() throws IOException {
        String expected = render();

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(GENERATED.getParent());
            Files.writeString(GENERATED, expected);
        }

        assertThat(GENERATED).as("%s fehlt. %s", GENERATED, REGENERATE_HINT).exists();
        assertThat(Files.readString(GENERATED))
                .as("%s ist nicht mehr das, was PoolExportSanitizer ergibt. %s", GENERATED, REGENERATE_HINT)
                .isEqualTo(expected);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private static String render() {
        StringBuilder out = new StringBuilder("""
                #!/usr/bin/env bash
                # generiert von PoolSanitizerKeysTest — nicht von Hand ändern
                #
                # Quelle: com.automation.service.pool.PoolExportSanitizer (Instanz-Referenzschlüssel).
                # Neu erzeugen:
                #   cd ai-automation-backend && ./mvnw test -Dtest=PoolSanitizerKeysTest -Dpool.sanitizer.write=true
                #
                # Was der Export entfernt, beanstandet das Gate im Bestand: eine Quelle, zwei Seiten.
                # Eingelesen von scripts/lib/hygiene-rules.sh; reine Daten, keine Funktionen.
                #
                # Verglichen wird auf beiden Seiten normalisiert (klein, ohne '_' und '-'), siehe
                # PoolExportSanitizer.normalize und normalize_key in scripts/ci/pool-neutrality.sh.
                # Auch eine Schreibweise, die hier nicht wortwoertlich steht (project-id), zaehlt.
                #
                # shellcheck shell=bash

                HYGIENE_POOL_INSTANCE_KEYS=(
                """);
        for (String key : PoolExportSanitizer.instanceReferenceKeys()) {
            out.append("  \"").append(key).append("\" \"").append(snake(key)).append("\"\n");
        }
        return out.append(")\n").toString();
    }

    /** {@code sourceRunId} → {@code source_run_id}: beide Schreibweisen kommen in Definitionen vor. */
    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
