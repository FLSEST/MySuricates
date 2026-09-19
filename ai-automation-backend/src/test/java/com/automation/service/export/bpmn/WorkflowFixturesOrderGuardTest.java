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
 * Haelt fest, dass die BPMN-Fixtures keine Map mit JVM-zufaelliger Reihenfolge bauen (TODO df5c689d).
 *
 * <p>Die Falle: {@code Map.of} iteriert pro JVM in zufaelliger Reihenfolge
 * ({@code ImmutableCollections.SALT}). Bei einer Block-Config faellt das nicht auf — {@link
 * WorkflowFixtures#block} kopiert sie in eine {@code LinkedHashMap} um. Eine verschachtelte Map in
 * einer Liste (Switch-Cases, Branch-Bloecke) geht dagegen unveraendert ins serialisierte Config-JSON
 * und damit ins Golden-File. Der Fehler zeigt sich dann nicht hier, sondern als sporadisch rotes
 * Golden-File auf einer anderen JVM — der teuerste Fehlertyp, den es gibt.
 *
 * <p>Warum eine Quelltext-Pruefung und kein zweiter JVM-Lauf: Ein zweiter Lauf beweist die
 * Stabilitaet nur fuer die beiden gezogenen SALT-Werte und kostet jedes Mal eine Fork-Laufzeit.
 * Diese Pruefung schliesst die Ursache aus, laeuft in Millisekunden und braucht keine neue
 * Abhaengigkeit. Wer eine Map braucht, nimmt {@link WorkflowFixtures#ordered} — auch fuer einen
 * einzigen Eintrag, damit die Regel keine Ausnahme kennt, ueber die man nachdenken muss.
 */
class WorkflowFixturesOrderGuardTest {

    private static final Path FIXTURES =
            Path.of("src", "test", "java", "com", "automation", "service", "export", "bpmn",
                    "WorkflowFixtures.java");

    /** Map-Quellen ohne zugesicherte Reihenfolge, je Muster der Grund. */
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
                continue;   // Erklaerungen duerfen die Muster nennen, dieser Test tut es selbst.
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

    /** Und sie ueberlebt den Weg durch block(), der die Config umkopiert. */
    @Test
    void blockKeepsConfigOrder() {
        var block = WorkflowFixtures.block("shell", "test", WorkflowFixtures.ordered(
                "zulu", "a", "alpha", "b", "mike", "c"));

        assertThat(block.getConfig().keySet()).containsExactly("zulu", "alpha", "mike");
    }
}
