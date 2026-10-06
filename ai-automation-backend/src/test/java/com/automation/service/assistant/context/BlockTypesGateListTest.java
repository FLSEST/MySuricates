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
 * Couples the block type list of the hygiene gate to the registry in the backend (TODO bdc784e1).
 *
 * <p>{@code HYGIENE_POOL_BLOCK_TYPES} in {@code scripts/lib/hygiene-rules.sh} was maintained by hand
 * and only afterwards compared by {@code scripts/tests/test-public-hygiene.sh} (check 13) with
 * {@link BlockTypesIndexSection#BLOCK_TYPES}. A new block type was thus only noticed
 * when someone ran the hygiene tests — the order was wrong: first drift, then
 * comparison.
 *
 * <p>Modeled on {@code PoolSanitizerKeysTest} with {@code scripts/lib/pool-sanitizer.generated.sh}:
 * the test generates the Bash file from the one source, the script reads it. Whoever adds a block type
 * without regenerating turns this test red.
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
     * Sorted, not in registry order: the gate compares sorted, and a sorted
     * file only changes when a type really is added or removed — not already when
     * someone reorders the registry lines.
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
