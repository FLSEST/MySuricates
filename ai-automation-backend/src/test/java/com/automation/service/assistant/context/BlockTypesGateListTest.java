package com.automation.service.assistant.context;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Koppelt die Blocktyp-Liste des Hygiene-Gates an die Registry im Backend (TODO bdc784e1).
 *
 * <p>{@code HYGIENE_POOL_BLOCK_TYPES} in {@code scripts/lib/hygiene-rules.sh} wurde von Hand
 * gepflegt und erst nachträglich von {@code scripts/tests/test-public-hygiene.sh} (Check 13) mit
 * {@link BlockTypesIndexSection#BLOCK_TYPES} verglichen. Ein neuer Blocktyp fiel damit erst auf,
 * wenn jemand die Hygiene-Tests laufen liess — die Reihenfolge war verkehrt: erst Drift, dann
 * Vergleich.
 *
 * <p>Vorbild ist {@code PoolSanitizerKeysTest} mit {@code scripts/lib/pool-sanitizer.generated.sh}:
 * der Test erzeugt die Bash-Datei aus der einen Quelle, das Skript liest sie. Wer einen Blocktyp
 * hinzufügt, ohne neu zu erzeugen, macht diesen Test rot.
 */
class BlockTypesGateListTest {

    private static final Path GENERATED = Path.of("..", "scripts", "lib", "block-types.generated.sh");

    private static final String WRITE_PROPERTY = "block.types.write";

    private static final String REGENERATE_HINT =
            "Neu erzeugen: cd ai-automation-backend && ./mvnw test -Dtest=BlockTypesGateListTest -Dblock.types.write=true";

    @Test
    void blockTypesAreLowerCaseKebabAndUnique() {
        List<String> types = types();

        assertThat(types).isNotEmpty().doesNotHaveDuplicates();
        assertThat(types).allSatisfy(type -> assertThat(type)
                .as("Blocktypen stehen in der Definition klein und mit Bindestrich (compress-ai)")
                .matches("[a-z]+(-[a-z]+)*"));
    }

    @Test
    void generatedGateListMatchesTheRegistry() throws IOException {
        String expected = render();

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(GENERATED.getParent());
            Files.writeString(GENERATED, expected);
        }

        assertThat(GENERATED).as("%s fehlt. %s", GENERATED, REGENERATE_HINT).exists();
        assertThat(Files.readString(GENERATED))
                .as("%s ist nicht mehr das, was BlockTypesIndexSection.BLOCK_TYPES ergibt. %s",
                        GENERATED, REGENERATE_HINT)
                .isEqualTo(expected);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Sortiert, nicht in Registry-Reihenfolge: das Gate vergleicht sortiert, und eine sortierte
     * Datei aendert sich nur, wenn wirklich ein Typ dazukommt oder wegfaellt — nicht schon, wenn
     * jemand die Registry-Zeilen umstellt.
     */
    private static String render() {
        StringBuilder out = new StringBuilder("""
                #!/usr/bin/env bash
                # generiert von BlockTypesGateListTest — nicht von Hand ändern
                #
                # Quelle: com.automation.service.assistant.context.BlockTypesIndexSection.BLOCK_TYPES.
                # Neu erzeugen:
                #   cd ai-automation-backend && ./mvnw test -Dtest=BlockTypesGateListTest -Dblock.types.write=true
                #
                # Die Blocktypen, die eine ausgelieferte Definition verwenden darf: eine Quelle,
                # zwei Seiten (Registry im Backend, Hygiene-Gate in den Skripten).
                # Eingelesen von scripts/lib/hygiene-rules.sh; reine Daten, keine Funktionen.
                #
                # shellcheck shell=bash

                HYGIENE_POOL_BLOCK_TYPES=(
                """);
        List<String> types = types();
        types.sort(Comparator.naturalOrder());
        for (String type : types) {
            out.append("  ").append(type).append("\n");
        }
        return out.append(")\n").toString();
    }

    private static List<String> types() {
        List<String> types = new ArrayList<>();
        BlockTypesIndexSection.BLOCK_TYPES.forEach(entry -> types.add(entry.type()));
        return types;
    }
}
