package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Antwort der Korrekturrunde: lesen, Struktur vergleichen, weggelassene Felder ergänzen. */
class CorrectionAnswerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static Map<String, Object> nested(String type) {
        return Map.of("type", type, "config", Map.of("command", "x"));
    }

    @Nested
    class ShapeChange {

        @Test
        void gleicheStrukturErgibtNull() {
            var original = List.of(block("shell", Map.of("command", "a")), block("simple", Map.of("command", "b")));
            var corrected = List.of(block("shell", Map.of("command", "set -e; a")), block("simple", Map.of("command", "c")));

            assertThat(CorrectionAnswer.shapeChange(original, corrected)).isNull();
        }

        @Test
        void meldetGeaenderteBlockzahl() {
            var original = List.of(block("shell", Map.of()), block("simple", Map.of()));
            var corrected = List.of(block("simple", Map.of()));

            assertThat(CorrectionAnswer.shapeChange(original, corrected)).isEqualTo("Blockzahl 2 → 1");
        }

        @Test
        void meldetGeaendertenTypMitEinsBasierterPosition() {
            var original = List.of(block("shell", Map.of()), block("simple", Map.of()));
            var corrected = List.of(block("shell", Map.of()), block("agentic", Map.of()));

            assertThat(CorrectionAnswer.shapeChange(original, corrected)).isEqualTo("Block 2: Typ simple → agentic");
        }

        @Test
        void meldetWeggefalleneVerschachtelteBloecke() {
            var original = List.of(block("foreach", Map.of("blocks", List.of(nested("shell"), nested("simple")))));
            var corrected = List.of(block("foreach", Map.of("blocks", List.of(nested("shell")))));

            assertThat(CorrectionAnswer.shapeChange(original, corrected))
                    .isEqualTo("Block 1: verschachtelte Blöcke 2 → 1");
        }

        @Test
        void meldetVerschobeneVerschachtelteBloeckeAuchBeiGleicherZahl() {
            var original = List.of(block("switch", Map.of(
                    "cases", List.of(Map.of("blocks", List.of(nested("shell")))))));
            var corrected = List.of(block("switch", Map.of("default", List.of(nested("shell")))));

            assertThat(CorrectionAnswer.shapeChange(original, corrected))
                    .isEqualTo("Block 1: verschachtelter Block verschoben (cases[0] › Block 1 → default › Block 1)");
        }

        @Test
        void verschachtelteTypenDarfDieKorrekturAendern() {
            var original = List.of(block("retry", Map.of("blocks", List.of(nested("simple")))));
            var corrected = List.of(block("retry", Map.of("blocks", List.of(nested("shell")))));

            assertThat(CorrectionAnswer.shapeChange(original, corrected)).isNull();
        }

        @Test
        void tieferVerschachtelteBloeckeZaehlenMit() {
            Map<String, Object> inner = Map.of("type", "foreach", "config",
                    Map.of("blocks", List.of(nested("shell"), nested("shell"))));
            Map<String, Object> innerKuerzer = Map.of("type", "foreach", "config",
                    Map.of("blocks", List.of(nested("shell"))));
            var original = List.of(block("conditional", Map.of("blocks", List.of(inner))));
            var corrected = List.of(block("conditional", Map.of("blocks", List.of(innerKuerzer))));

            assertThat(CorrectionAnswer.shapeChange(original, corrected))
                    .isEqualTo("Block 1: verschachtelte Blöcke 3 → 2");
        }

        @Test
        void beideLeerSindGleich() {
            assertThat(CorrectionAnswer.shapeChange(List.of(), List.of())).isNull();
        }
    }

    @Nested
    class KeepOmittedFields {

        @Test
        void uebernimmtNameSkillsUndCapabilitiesWoDieKorrekturSieWeglaesst() {
            BlockDefinition a = block("simple", Map.of());
            a.setName("report");
            a.setSkillIds(List.of("skill-1"));
            a.setRequiredCapabilities(List.of("git"));
            BlockDefinition b = block("simple", Map.of());
            b.setName(" ");
            b.setSkillIds(null);
            b.setRequiredCapabilities(new ArrayList<>());

            CorrectionAnswer.keepOmittedFields(List.of(a), List.of(b));

            assertThat(b.getName()).isEqualTo("report");
            assertThat(b.getSkillIds()).containsExactly("skill-1").isNotSameAs(a.getSkillIds());
            assertThat(b.getRequiredCapabilities()).containsExactly("git").isNotSameAs(a.getRequiredCapabilities());
        }

        @Test
        void laesstGesetzteWerteDerKorrekturStehen() {
            BlockDefinition a = block("simple", Map.of());
            a.setName("alt");
            a.setSkillIds(List.of("alt-skill"));
            a.setRequiredCapabilities(List.of("alt-cap"));
            BlockDefinition b = block("simple", Map.of());
            b.setName("neu");
            b.setSkillIds(List.of("neu-skill"));
            b.setRequiredCapabilities(List.of("neu-cap"));

            CorrectionAnswer.keepOmittedFields(List.of(a), List.of(b));

            assertThat(b.getName()).isEqualTo("neu");
            assertThat(b.getSkillIds()).containsExactly("neu-skill");
            assertThat(b.getRequiredCapabilities()).containsExactly("neu-cap");
        }

        @Test
        void ohneWertImOriginalBleibtDieKorrekturUnveraendert() {
            BlockDefinition a = block("simple", Map.of());
            a.setName(null);
            a.setSkillIds(null);
            a.setRequiredCapabilities(null);
            BlockDefinition b = block("simple", Map.of());
            b.setName(null);
            b.setSkillIds(null);
            b.setRequiredCapabilities(null);

            CorrectionAnswer.keepOmittedFields(List.of(a), List.of(b));

            assertThat(b.getName()).isNull();
            assertThat(b.getSkillIds()).isNull();
            assertThat(b.getRequiredCapabilities()).isNull();
        }

        @Test
        void ordnetJePositionZu() {
            BlockDefinition a1 = block("shell", Map.of());
            a1.setName("eins");
            BlockDefinition a2 = block("simple", Map.of());
            a2.setName("zwei");
            BlockDefinition b1 = block("shell", Map.of());
            BlockDefinition b2 = block("simple", Map.of());

            CorrectionAnswer.keepOmittedFields(List.of(a1, a2), List.of(b1, b2));

            assertThat(b1.getName()).isEqualTo("eins");
            assertThat(b2.getName()).isEqualTo("zwei");
        }
    }

    @Nested
    class Parse {

        @Test
        void liestEinArray() {
            var blocks = CorrectionAnswer.parse("[{\"type\":\"shell\",\"name\":\"a\",\"config\":{\"command\":\"ls\"}}]", MAPPER);

            assertThat(blocks).singleElement().satisfies(b -> {
                assertThat(b.getType()).isEqualTo("shell");
                assertThat(b.getName()).isEqualTo("a");
                assertThat(b.getConfig()).containsEntry("command", "ls");
            });
        }

        @Test
        void liestEinObjektMitBlocksInCodeFences() {
            String answer = "```json\n{\"blocks\":[{\"type\":\"simple\",\"config\":{\"command\":\"x\"}},"
                    + "{\"type\":\"shell\",\"config\":{\"command\":\"y\"}}]}\n```";

            assertThat(CorrectionAnswer.parse(answer, MAPPER)).extracting(BlockDefinition::getType)
                    .containsExactly("simple", "shell");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "Ich kann das nicht korrigieren.", "[{\"type\":", "{\"andere\": 1}"})
        void liefertLeerWennNichtsBrauchbaresDarinSteht(String answer) {
            assertThat(CorrectionAnswer.parse(answer, MAPPER)).isEmpty();
        }
    }
}
