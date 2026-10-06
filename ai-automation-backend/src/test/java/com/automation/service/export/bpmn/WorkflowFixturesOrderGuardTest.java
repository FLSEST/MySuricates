package com.automation.service.export.bpmn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the BPMN fixtures never build a map with JVM-random iteration order (TODO df5c689d).
 *
 * <p>The trap: {@code Map.of} iterates in random order per JVM
 * ({@code ImmutableCollections.SALT}). A block config does not show this — {@link
 * WorkflowFixtures#block} copies it into a {@code LinkedHashMap}. A nested map in
 * a list (switch cases, branch blocks), however, goes unchanged into the serialized config JSON
 * and thus into the golden file. The failure then shows up not here but as a sporadically red
 * golden file on another JVM — the most expensive kind of failure there is.
 *
 * <p>Why a source check and no second JVM run: A second run proves
 * stability only for the two drawn SALT values and costs a fork's runtime every time.
 * This check rules out the cause, runs in milliseconds and needs no new
 * dependency. Whoever needs a map uses {@link WorkflowFixtures#ordered} — even for a
 * single entry, so the rule has no exception one has to think about.
 */
class WorkflowFixturesOrderGuardTest {

    private static final Path FIXTURES =
            Path.of("src", "test", "java", "com", "automation", "service", "export", "bpmn",
                    "WorkflowFixtures.java");

    /** Map sources without a guaranteed order, with the reason per pattern. */
    private static final Map<String, String> FORBIDDEN = Map.of(
            "Map.of(", "iteriert pro JVM zufaellig (ImmutableCollections.SALT)",
            "Map.ofEntries(", "iteriert pro JVM zufaellig (ImmutableCollections.SALT)",
            "new HashMap<>(", "Hash-Reihenfolge statt Einfuegereihenfolge",
            "Map.copyOf(", "liefert eine Map.of-Kopie, also zufaellige Reihenfolge");

    @Test
    void fixturesUseOnlyOrderedMaps() throws IOException {
        assertThat(FIXTURES).as("Fixture-Quelltext nicht gefunden — Pfad angepasst?").exists();

        List<String> findings = new ArrayList<>();
        List<String> lines = Files.readAllLines(FIXTURES);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                continue;   // Explanations may name the patterns, this test does so itself.
            }
            int lineNumber = i + 1;
            for (Map.Entry<String, String> rule : FORBIDDEN.entrySet()) {
                if (line.contains(rule.getKey())) {
                    findings.add("Zeile %d: %s — %s"
                            .formatted(lineNumber, rule.getKey(), rule.getValue()));
                }
            }
        }

        assertThat(findings)
                .as("In WorkflowFixtures ist nur ordered(...) als Map-Quelle erlaubt (TODO df5c689d)."
                        + " Gefunden: %s", findings)
                .isEmpty();
    }

    /** Die Zusicherung selbst: ordered() haelt die Einfuegereihenfolge. */
    @Test
    void orderedKeepsInsertionOrder() {
        Map<String, Object> map = WorkflowFixtures.ordered(
                "zulu", 1, "alpha", 2, "mike", 3, "bravo", 4);

        assertThat(map.keySet()).containsExactly("zulu", "alpha", "mike", "bravo");
    }

    /** And it survives the trip through block(), which copies the config. */
    @Test
    void blockKeepsConfigOrder() {
        var block = WorkflowFixtures.block("shell", "test", WorkflowFixtures.ordered(
                "zulu", "a", "alpha", "b", "mike", "c"));

        assertThat(block.getConfig().keySet()).containsExactly("zulu", "alpha", "mike");
    }
}
