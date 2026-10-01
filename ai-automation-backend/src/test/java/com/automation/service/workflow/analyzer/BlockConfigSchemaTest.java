package com.automation.service.workflow.analyzer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema-Zuordnung je Blocktyp aus der mit dem Editor geteilten Datei. */
class BlockConfigSchemaTest {

    private static BlockConfigSchema parse(String json) throws IOException {
        return BlockConfigSchema.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** Kleines Schema: ein KI-Typ, ein Nicht-KI-Typ, ein gemeinsamer und ein geteilter Schlüssel. */
    private static final String MINI = """
            {
              "onFailure": {"values": [{"value": "stop"}, {"value": "continue"}]},
              "commonFields": {"label": {}, "provider": {}},
              "ai": {"blockTypes": ["simple"], "fields": {"model": {}, "provider": {"values": [{"value": "CLAUDE"}], "ignoreCase": true}}},
              "sharedFields": {
                "mode": {"values": [{"value": "ai"}, {"value": "shell"}], "legacy": "claude"},
                "image": {"onlyWith": {"mode": "shell"}}
              },
              "blockTypes": {
                "simple": {"required": ["command"], "fields": {"command": {}, "model": {"legacyFor": "x"}}},
                "loop": {"fields": {"mode": "#mode", "image": "#image"}}
              }
            }
            """;

    @Nested
    class AusgeliefertesSchema {

        private final BlockConfigSchema schema = BlockConfigSchema.get();

        @Test
        void kenntAlleZwanzigBlocktypen() {
            assertThat(schema.blockTypes()).containsExactlyInAnyOrder(
                    "simple", "shell", "retry", "conditional", "foreach", "while", "switch", "agentic",
                    "approval", "sub-workflow", "skill-capability", "human-input", "integration", "document",
                    "todo-create", "todo-update", "todo-close", "compress", "compress-ai", "dashboard-report");
            assertThat(BlockConfigSchema.get()).isSameAs(schema);
        }

        @Test
        void unbekannteTypenHabenKeinSchema() {
            assertThat(schema.knowsType(null)).isFalse();
            assertThat(schema.knowsType("SIMPLE")).isFalse();
            assertThat(schema.fieldsFor("gibt-es-nicht")).isEmpty();
            assertThat(schema.fieldsFor(null)).isEmpty();
            assertThat(schema.ownKeys(null)).isEmpty();
            assertThat(schema.requiredKeys("gibt-es-nicht")).isEmpty();
        }

        @Test
        void jederTypEndetMitOnFailureUndTraegtDieGemeinsamenSchluessel() {
            for (String type : schema.blockTypes()) {
                List<String> keys = List.copyOf(schema.fieldsFor(type).keySet());
                assertThat(keys).as(type).endsWith("on_failure").containsAll(schema.commonKeys());
            }
        }

        @Test
        void kiSchluesselNurBeiKiBlocktypen() {
            assertThat(schema.aiKeys()).contains("provider", "model", "effort");
            assertThat(schema.fieldsFor("simple")).containsKeys("provider", "model", "effort");
            assertThat(schema.fieldsFor("shell")).doesNotContainKeys("model", "effort");
            assertThat(schema.aiBlockTypes()).contains("simple", "agentic", "compress-ai").doesNotContain("shell");
        }

        @Test
        void pflichtschluesselJeTyp() {
            assertThat(schema.requiredKeys("shell")).containsExactly("command");
            assertThat(schema.requiredKeys("switch")).containsExactly("expression", "cases");
            assertThat(schema.requiredKeys("integration")).containsExactly("provider", "action");
            assertThat(schema.requiredKeys("approval")).isEmpty();
        }

        @Test
        void eigeneSchluesselOhneKiGemeinsameUndOnFailure() {
            assertThat(schema.ownKeys("simple")).contains("command").doesNotContain("model", "label", "on_failure");
        }

        @Test
        void geteilteFelderWerdenAufgeloest() {
            var mode = schema.fieldsFor("retry").get("execution_mode");
            assertThat(mode.values()).containsExactly("ai", "shell");
            assertThat(mode.legacy()).isEqualTo("claude");
            assertThat(mode.allows("claude")).isTrue();

            var image = schema.fieldsFor("foreach").get("container_image");
            assertThat(image.onlyWith()).containsEntry("execution_mode", "shell");
        }

        @Test
        void legacyNameUndGrossKleinschreibung() {
            assertThat(schema.fieldsFor("sub-workflow").get("workflowId").legacyFor()).isEqualTo("workflow_id");
            var priority = schema.fieldsFor("todo-create").get("priority");
            assertThat(priority.ignoreCase()).isTrue();
            assertThat(priority.allows(" high ")).isTrue();
            assertThat(priority.allows("URGENT")).isFalse();
        }
    }

    @Nested
    class Field {

        @Test
        void ohneFesteWerteIstAllesErlaubt() {
            var field = new BlockConfigSchema.Field("command", List.of(), null, false, null, Map.of());

            assertThat(field.allows("irgendwas")).isTrue();
            assertThat(field.allowedValues()).isEmpty();
        }

        @Test
        void vergleichtOhneIgnoreCaseExakt() {
            var field = new BlockConfigSchema.Field("status", List.of("GREEN", "RED"), null, false, null, Map.of());

            assertThat(field.allows("GREEN")).isTrue();
            assertThat(field.allows("green")).isFalse();
            assertThat(field.allows(" GREEN")).isFalse();
        }

        @Test
        void ignoreCaseIgnoriertAuchRandleerzeichen() {
            var field = new BlockConfigSchema.Field("provider", List.of("CLAUDE"), null, true, null, Map.of());

            assertThat(field.allows(" claude ")).isTrue();
            assertThat(field.allows("codex")).isFalse();
        }

        @Test
        void legacyWertGiltAlsErlaubtUndStehtAmEnde() {
            var field = new BlockConfigSchema.Field("mode", List.of("ai", "shell"), "claude", false, null, Map.of());

            assertThat(field.allowedValues()).containsExactly("ai", "shell", "claude");
            assertThat(field.allows("claude")).isTrue();
            assertThat(field.values()).containsExactly("ai", "shell");
        }
    }

    @Nested
    class Parse {

        @Test
        void stelltSchluesselInEditorReihenfolgeZusammen() throws IOException {
            var schema = parse(MINI);

            // eigene Schlüssel, dann KI-Schlüssel, dann gemeinsame, zuletzt on_failure; Doppelte behält die erste Quelle
            assertThat(schema.fieldsFor("simple")).containsOnlyKeys("command", "model", "provider", "label", "on_failure");
            assertThat(List.copyOf(schema.fieldsFor("simple").keySet()))
                    .containsExactly("command", "model", "provider", "label", "on_failure");
            assertThat(schema.fieldsFor("simple").get("model").legacyFor()).isEqualTo("x");
            assertThat(schema.fieldsFor("simple").get("provider").ignoreCase()).isTrue();
            assertThat(schema.fieldsFor("simple").get("on_failure").values()).containsExactly("stop", "continue");
        }

        @Test
        void nichtKiTypBekommtKeineKiSchluessel() throws IOException {
            var schema = parse(MINI);

            assertThat(List.copyOf(schema.fieldsFor("loop").keySet()))
                    .containsExactly("mode", "image", "label", "provider", "on_failure");
            assertThat(schema.fieldsFor("loop").get("provider").values()).isEmpty();
            assertThat(schema.requiredKeys("loop")).isEmpty();
        }

        @Test
        void loestVerweiseAufGeteilteFelderAuf() throws IOException {
            var schema = parse(MINI);

            var mode = schema.fieldsFor("loop").get("mode");
            assertThat(mode.key()).isEqualTo("mode");
            assertThat(mode.values()).containsExactly("ai", "shell");
            assertThat(mode.legacy()).isEqualTo("claude");
            assertThat(schema.fieldsFor("loop").get("image").onlyWith()).containsExactly(Map.entry("mode", "shell"));
        }

        @Test
        void unbekannterVerweisScheitertLaut() {
            String json = """
                    {"blockTypes": {"loop": {"fields": {"mode": "#fehlt"}}}}
                    """;

            assertThatThrownBy(() -> parse(json))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("#fehlt").hasMessageContaining("mode");
        }

        @Test
        void schemaOhneBlocktypenScheitertLaut() {
            assertThatThrownBy(() -> parse("{\"blockTypes\": {}}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keine Blocktypen");
        }

        @Test
        void zurueckgegebeneSammlungenSindUnveraenderlich() throws IOException {
            var schema = parse(MINI);

            assertThatThrownBy(() -> schema.blockTypes().add("neu")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> schema.ownKeys("simple").add("neu")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> schema.requiredKeys("simple").add("neu")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> schema.aiBlockTypes().add("neu")).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
