package com.automation.service.dialog;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DialogTextRulesTest {

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).config(config).build();
    }

    @Nested
    class Deduplicate {

        @Test
        void faltetDirektAufeinanderfolgendeGleicheBloeckeZusammen() {
            BlockDefinition a1 = block("shell", Map.of("command", "mvn test"));
            BlockDefinition a2 = block("shell", Map.of("command", "mvn test"));
            BlockDefinition b = block("simple", Map.of("prompt", "fasse zusammen"));

            assertThat(DialogTextRules.deduplicate(List.of(a1, a2, b))).containsExactly(a1, b);
        }

        @Test
        void behaeltGleicheBloeckeDieNichtNebeneinanderStehen() {
            BlockDefinition a1 = block("shell", Map.of("command", "mvn test"));
            BlockDefinition b = block("simple", Map.of("prompt", "x"));
            BlockDefinition a2 = block("shell", Map.of("command", "mvn test"));

            assertThat(DialogTextRules.deduplicate(List.of(a1, b, a2))).containsExactly(a1, b, a2);
        }

        @Test
        void unterscheidetNachTypUndNachConfig() {
            BlockDefinition shell = block("shell", Map.of("command", "ls -la"));
            BlockDefinition simple = block("simple", Map.of("command", "ls -la"));
            BlockDefinition other = block("simple", Map.of("command", "pwd"));

            assertThat(DialogTextRules.deduplicate(List.of(shell, simple, other)))
                    .containsExactly(shell, simple, other);
        }

        @Test
        void vergleichtNullConfigsAlsGleich() {
            BlockDefinition a1 = block("shell", null);
            BlockDefinition a2 = block("shell", null);

            assertThat(DialogTextRules.deduplicate(List.of(a1, a2))).containsExactly(a1);
        }

        @Test
        void laengereWiederholungBleibtEinBlock() {
            BlockDefinition a = block("shell", Map.of("command", "make"));

            assertThat(DialogTextRules.deduplicate(List.of(a, a, a, a))).containsExactly(a);
        }

        @Test
        void leereListeBleibtLeer() {
            assertThat(DialogTextRules.deduplicate(List.of())).isEmpty();
        }
    }

    @Nested
    class IsInternalCommand {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "ls", "ls -la", "cat pom.xml", "echo hallo", "pwd",
                "git log | head -5", "mvn test 2>&1 | tail -20"})
        void erkenntExplorativeBefehle(String cmd) {
            assertThat(DialogTextRules.isInternalCommand(cmd)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"mvn test", "npm run build", "lsof -i :9090", "catalog-sync",
                "git log | headless", "docker compose up -d"})
        void laesstArbeitsbefehleDurch(String cmd) {
            assertThat(DialogTextRules.isInternalCommand(cmd)).isFalse();
        }
    }

    @Nested
    class IsMetaCommentary {

        @ParameterizedTest
        @ValueSource(strings = {"Let me check the logs", "I'll now run the tests", "I'm going to fix it",
                "I can see the error", "I notice a typo", "Looking at the output", "LET ME look"})
        void erkenntSelbstkommentareOhneGrossKleinschreibung(String text) {
            assertThat(DialogTextRules.isMetaCommentary(text)).isTrue();
        }

        @Test
        void nullGiltAlsKommentar() {
            assertThat(DialogTextRules.isMetaCommentary(null)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Die Tests sind grün.", "The build failed because of X", "",
                "We should let me know"})
        void laesstInhaltDurch(String text) {
            assertThat(DialogTextRules.isMetaCommentary(text)).isFalse();
        }
    }

    @Nested
    class ExtractJson {

        @Test
        void schneidetVomErstenOeffnendenBisZumLetztenSchliessendenKlammer() {
            String answer = "Hier ist der Vorschlag:\n```json\n{\"a\": {\"b\": 1}}\n```\nFertig.";

            assertThat(DialogTextRules.extractJson(answer)).isEqualTo("{\"a\": {\"b\": 1}}");
        }

        @Test
        void gibtDenTextUnveraendertZurueckOhneObjekt() {
            assertThat(DialogTextRules.extractJson("kein json")).isEqualTo("kein json");
        }

        @Test
        void gibtDenTextZurueckWennDieKlammernVerkehrtStehen() {
            assertThat(DialogTextRules.extractJson("} dazwischen {")).isEqualTo("} dazwischen {");
        }

        @Test
        void nurOeffnendeKlammerReichtNicht() {
            assertThat(DialogTextRules.extractJson("{\"a\": 1")).isEqualTo("{\"a\": 1");
        }
    }

    @Nested
    class Truncate {

        @Test
        void nullWirdLeererText() {
            assertThat(DialogTextRules.truncate(null, 10)).isEmpty();
        }

        @Test
        void textBisZurGrenzeBleibtUnveraendert() {
            assertThat(DialogTextRules.truncate("12345", 5)).isEqualTo("12345");
        }

        @Test
        void laengererTextWirdGekuerztUndMarkiert() {
            assertThat(DialogTextRules.truncate("123456", 5)).isEqualTo("12345...");
        }
    }
}
