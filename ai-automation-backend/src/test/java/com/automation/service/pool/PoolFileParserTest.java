package com.automation.service.pool;

import com.automation.architecture.ShippedDefinitions;
import com.automation.dto.IntegrityCheck;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolFileParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path write(String filename, String json) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, json);
        return file;
    }

    private PoolWorkflowEntry parse(Path file, String locale) throws IOException {
        return PoolFileParser.parse(file, objectMapper, locale);
    }

    /** TODO b18939a6: one checksum over the raw file — the same for every language the steps resolve to. */
    @Nested
    class Integrity {

        @Test
        void bilingualFile_hasOneChecksumForBothLanguages() throws Exception {
            String content = """
                    {"name": {"de": "Zwei", "en": "Two"}, "i18n": ["de", "en"],
                     "blocks": [{"type": "simple", "config": {"prompt": {"de": "Hallo", "en": "Hello"}}}]%s}
                    """;
            Path file = write("bilingual.json", content.formatted(""));
            String checksum = parse(file, "de").integrity().actual();
            write("bilingual.json", content.formatted(", \"integrity\": \"" + checksum + "\""));

            assertThat(parse(file, "de").integrity().status()).isEqualTo(IntegrityCheck.Status.VERIFIED);
            assertThat(parse(file, "en").integrity().status()).isEqualTo(IntegrityCheck.Status.VERIFIED);
        }

        @Test
        void fileWithoutChecksum_isMissing_stillLoads() throws Exception {
            Path file = write("plain.json", """
                    {"name": {"en": "Plain"}, "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
                    """);

            assertThat(parse(file, "en").integrity().status()).isEqualTo(IntegrityCheck.Status.MISSING);
        }
    }

    @Nested
    class ValidFiles {

        @Test
        void minimalFile_fillsDefaults() throws Exception {
            Path file = write("minimal.json", """
                    {"name": {"en": "Minimal"}, "blocks": [{"type": "shell", "config": {"command": "ls"}}]}
                    """);

            PoolWorkflowEntry entry = parse(file, "en");

            assertThat(entry.id()).isEqualTo("minimal");
            assertThat(entry.filename()).isEqualTo("minimal.json");
            assertThat(entry.description()).isEmpty();
            assertThat(entry.icon()).isEqualTo("📋");
            assertThat(entry.tags()).isEmpty();
            assertThat(entry.version()).isEqualTo("1.0.0");
            assertThat(entry.scope()).isNull();
            assertThat(entry.defaultContext()).isNull();
            assertThat(entry.metadata()).isNull();
        }

        @Test
        void allFields_areRead() throws Exception {
            Path file = write("full.json", """
                    {"name": {"de": "Voll", "en": "Full"}, "description": {"de": "B", "en": "D"},
                     "icon": "🔍", "category": "CODE", "scope": "portable", "tags": ["a", "b"],
                     "instructions": "Be brief", "model": "opus", "effort": "high", "version": "2.1.0",
                     "author": "Tester", "defaultContext": {"project_path": "/p"}, "metadata": {"k": 1},
                     "blocks": [{"type": "simple", "name": "ask", "config": {"prompt": "hi"}}]}
                    """);

            PoolWorkflowEntry entry = parse(file, "de");

            assertThat(entry.name()).containsEntry("de", "Voll").containsEntry("en", "Full");
            assertThat(entry.description()).containsEntry("en", "D");
            assertThat(entry.icon()).isEqualTo("🔍");
            assertThat(entry.category()).isEqualTo("CODE");
            assertThat(entry.scope()).isEqualTo("portable");
            assertThat(entry.tags()).containsExactly("a", "b");
            assertThat(entry.instructions()).isEqualTo("Be brief");
            assertThat(entry.model()).isEqualTo("opus");
            assertThat(entry.effort()).isEqualTo("high");
            assertThat(entry.version()).isEqualTo("2.1.0");
            assertThat(entry.author()).isEqualTo("Tester");
            assertThat(entry.defaultContext()).containsEntry("project_path", "/p");
            assertThat(entry.metadata()).containsEntry("k", 1);
            assertThat(entry.blocks()).extracting(BlockDefinition::getName).containsExactly("ask");
        }

        @Test
        void undeclaredScopeWithWrongType_staysUndeclared() throws Exception {
            Path file = write("scope.json", """
                    {"name": {"en": "S"}, "scope": 3, "blocks": []}
                    """);

            assertThat(parse(file, "en").scope()).isNull();
        }

        /**
         * Every tracked pool file must parse; the untracked ones are the maintainer's own. The plain
         * mapper here fails on unknown block fields on purpose: Spring's mapper drops them silently,
         * so a key beside {@code config} instead of in it (like {@code on_failure}) would never act.
         */
        @Test
        void everyShippedPoolFile_parses() throws Exception {
            Path pool = Files.isDirectory(Paths.get("../workflow-pool")) ? Paths.get("../workflow-pool")
                    : Paths.get("workflow-pool");
            assertThat(pool).as("workflow-pool nicht gefunden ab " + Paths.get("").toAbsolutePath()).isDirectory();
            List<Path> gitTracked = ShippedDefinitions.poolFiles(pool);
            assertThat(gitTracked).as("tracked pool files").isNotEmpty();

            for (Path file : gitTracked) {
                String filename = file.getFileName().toString();
                for (String locale : List.of("de", "en")) {
                    PoolWorkflowEntry entry = PoolFileParser.parse(file, objectMapper, locale);
                    assertThat(entry).as(filename + " (" + locale + ")").isNotNull();
                    assertThat(entry.name()).as(filename).containsKeys("de", "en");
                    assertThat(entry.blocks()).as(filename).isNotEmpty();
                }
            }
        }

    }

    @Nested
    class MissingRequiredFields {

        @Test
        void missingName_isReported() throws Exception {
            Path file = write("no-name.json", """
                    {"scope": "repo", "blocks": [{"type": "shell", "config": {}}]}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .isInstanceOf(PoolFileParser.PoolFileFormatException.class)
                    .hasMessage("Pool file no-name.json: field 'name' is missing");
        }

        @Test
        void nullName_countsAsMissing() throws Exception {
            Path file = write("null-name.json", """
                    {"name": null, "scope": "repo", "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en")).hasMessageContaining("field 'name' is missing");
        }

        @Test
        void emptyName_isReported() throws Exception {
            Path file = write("empty-name.json", """
                    {"name": {}, "scope": "repo", "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'name' is empty, expected an object {\"de\": \"…\", \"en\": \"…\"}");
        }

        @Test
        void missingBlocks_isReported() throws Exception {
            Path file = write("no-blocks.json", """
                    {"name": {"en": "No blocks"}, "scope": "repo"}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessage("Pool file no-blocks.json: field 'blocks' is missing");
        }
    }

    @Nested
    class BrokenFiles {

        @Test
        void brokenJson_throwsTheJacksonError() throws Exception {
            Path file = write("broken.json", "{\"name\": {\"en\": \"x\"}, \"blocks\": [");

            assertThatThrownBy(() -> parse(file, "en")).isInstanceOf(JsonProcessingException.class);
        }

        @Test
        void emptyFile_namesTheFile() throws Exception {
            Path file = write("empty.json", "");

            assertThatThrownBy(() -> parse(file, "en"))
                    .isInstanceOf(PoolFileParser.PoolFileFormatException.class)
                    .hasMessageContaining("empty.json")
                    .hasMessageContaining("top level must be a JSON object");
        }

        @Test
        void topLevelArray_namesTheFoundType() throws Exception {
            Path file = write("array.json", "[{\"name\": {\"en\": \"x\"}}]");

            assertThatThrownBy(() -> parse(file, "en"))
                    .isInstanceOf(PoolFileParser.PoolFileFormatException.class)
                    .hasMessageContaining("array.json")
                    .hasMessageContaining("found array");
        }

        /**
         * The shape of {@code tech-radar.json} and {@code urlaubs-scout.json}: a plain workflow export
         * (string name and description, a projectId, no scope) copied into the pool. Before, this was
         * a bare ClassCastException; now one message names everything to fix.
         */
        @Test
        void plainWorkflowExport_namesEveryProblemAndThePoolRuleNotesAtOnce() throws Exception {
            Path file = write("tech-radar.json", """
                    {"name": "Tech-Radar", "description": "Weekly digest", "projectId": "p-1",
                     "blocks": [{"type": "shell", "config": {"command": "date"}}]}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .isInstanceOf(PoolFileParser.PoolFileFormatException.class)
                    .hasMessage("Pool file tech-radar.json: "
                            + "field 'name' must be an object {\"de\": \"…\", \"en\": \"…\"}, found string \"Tech-Radar\"; "
                            + "field 'description' must be an object {\"de\": \"…\", \"en\": \"…\"}, found string \"Weekly digest\". "
                            + "Also against the pool rules (docs/workflow-pool-kuratierung.md): "
                            + "instance bindings the pool export would remove: projectId; "
                            + "field 'scope' is missing, expected one of 'repo', 'portable', 'external'");
        }

        @Test
        void everyWrongField_isNamedInOneMessage() throws Exception {
            Path file = write("many.json", """
                    {"name": "N", "scope": "repo", "blocks": {"type": "shell"}, "tags": ["ok", 3],
                     "model": ["opus"], "defaultContext": "x", "metadata": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessage("Pool file many.json: "
                            + "field 'name' must be an object {\"de\": \"…\", \"en\": \"…\"}, found string \"N\"; "
                            + "field 'blocks' must be an array of blocks, found object; "
                            + "field 'tags' must be an array of strings, found array; "
                            + "field 'model' must be a string, found array; "
                            + "field 'defaultContext' must be an object, found string \"x\"; "
                            + "field 'metadata' must be an object, found array");
        }

        @Test
        void poolRuleNotesAloneDoNotReject_theFileStillLoads() throws Exception {
            Path file = write("bound.json", """
                    {"name": {"de": "N", "en": "N"}, "projectId": "p-1", "blocks": []}
                    """);

            PoolWorkflowEntry entry = parse(file, "en");

            assertThat(entry.scope()).isNull();
            assertThat(entry.name()).containsEntry("en", "N");
        }

        @Test
        void poolRuleNotes_followTheRules_placeholderAndDeclaredScopeAreFine() throws Exception {
            Path file = write("portable.json", """
                    {"name": "N", "scope": "portable", "projectId": "{{ project_id }}", "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageNotContaining("pool rules")
                    .hasMessageEndingWith("found string \"N\"");
        }

        @Test
        void poolRuleNotes_unknownScope_andManyBindingsAreCut() throws Exception {
            StringBuilder blocks = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                blocks.append(i == 0 ? "" : ", ").append("{\"type\": \"sub-workflow\", \"config\": {\"workflow_id\": \"w").append(i).append("\"}}");
            }
            Path file = write("bound.json", "{\"name\": \"N\", \"scope\": \"global\", \"blocks\": [" + blocks + "]}");

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("instance bindings the pool export would remove: blocks[0].config.workflow_id, "
                            + "blocks[1].config.workflow_id, blocks[2].config.workflow_id, blocks[3].config.workflow_id, "
                            + "blocks[4].config.workflow_id and 2 more; ")
                    .hasMessageEndingWith("field 'scope' must be one of 'repo', 'portable', 'external', found string \"global\"");
        }

        @Test
        void nonStringValueInName_namesTheNestedPath() throws Exception {
            Path file = write("nested.json", """
                    {"name": {"de": "N", "en": 5}, "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'name.en' must be a string, found number");
        }

        @Test
        void blockWithUnconvertibleField_namesThePathInTheBlocks() throws Exception {
            Path file = write("bad-block.json", """
                    {"name": {"en": "N"}, "blocks": [{"type": "shell", "config": {}}, {"type": "shell", "config": "not a map"}]}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .isInstanceOf(PoolFileParser.PoolFileFormatException.class)
                    .hasMessageContaining("bad-block.json: field 'blocks[1].config' is not valid: ");
        }

        /**
         * The mistake in walkthrough-illustrationen.json: on_failure beside config instead of in it. Only this
         * test's strict mapper used to reject it — the application's mapper dropped it without a word, so the run
         * stopped anyway. Since d3aa0b28 the block keeps the field on every mapper, and the analyzer names it
         * after the install, with the block it sits on: reported, not blocked, like every analyzer finding.
         */
        @Test
        void unknownBlockField_isKept_andTheAnalyzerNamesIt() throws Exception {
            Path file = write("beside.json", """
                    {"name": {"en": "N"}, "blocks": [{"type": "foreach", "config": {}, "on_failure": "continue"}]}
                    """);

            PoolWorkflowEntry entry = parse(file, "en");

            assertThat(entry.blocks().get(0).unknownFields()).containsEntry("on_failure", "continue");
            assertThat(new com.automation.service.workflow.analyzer.WorkflowAnalyzer().analyze(entry.blocks(), null))
                    .anySatisfy(finding -> {
                        assertThat(finding.rule()).isEqualTo("unknown-block-field");
                        assertThat(finding.blockIndex()).isZero();
                        assertThat(finding.detailEn()).startsWith("on_failure sits next to config instead of inside it");
                    });
        }

        @Test
        void localizedInstructionsWithoutMarker_isNamed() throws Exception {
            Path file = write("instr.json", """
                    {"name": {"en": "N"}, "instructions": {"de": "D", "en": "E"}, "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'instructions' must be a string, found object");
        }

        @Test
        void messageStaysOneLine_evenWithLineBreaksInTheFoundValue() throws Exception {
            Path file = write("lines.json", """
                    {"name": {"en": "N"}, "description": "first line\\nsecond line", "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("found string \"first line second line\"")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("\n"));
        }
    }

    @Nested
    class LocaleFallback {

        private static final String BILINGUAL = """
                {"i18n": ["de", "en"],
                 "name": {"de": "Beispiel", "en": "Example"},
                 "instructions": {"de": "Deutsch", "en": "English"},
                 "blocks": [{"type": "shell", "name": {"de": "messen", "en": "measure"},
                             "config": {"command": "date", "note": {"de": "Hinweis", "en": ""}}}]}
                """;

        @Test
        void german_resolvesStepsToGerman_keepsNameMap() throws Exception {
            PoolWorkflowEntry entry = parse(write("bi.json", BILINGUAL), "de");

            assertThat(entry.instructions()).isEqualTo("Deutsch");
            assertThat(entry.blocks().get(0).getName()).isEqualTo("messen");
            assertThat(entry.name()).isEqualTo(Map.of("de", "Beispiel", "en", "Example"));
        }

        @Test
        void regionTag_isNormalizedToTheLanguage() throws Exception {
            assertThat(parse(write("bi.json", BILINGUAL), "de-AT").instructions()).isEqualTo("Deutsch");
        }

        @Test
        void unsupportedOrMissingLocale_fallsBackToEnglish() throws Exception {
            Path file = write("bi.json", BILINGUAL);

            assertThat(parse(file, "fr").instructions()).isEqualTo("English");
            assertThat(parse(file, null).instructions()).isEqualTo("English");
            assertThat(parse(file, " ").blocks().get(0).getName()).isEqualTo("measure");
        }

        @Test
        void blankEnglishText_fallsBackToGerman() throws Exception {
            PoolWorkflowEntry entry = parse(write("bi.json", BILINGUAL), "en");

            assertThat(entry.blocks().get(0).getConfig().get("note")).isEqualTo("Hinweis");
        }

        @Test
        void withoutMarker_deEnMapsInBlocksStayData() throws Exception {
            Path file = write("plain.json", """
                    {"name": {"en": "Plain"}, "blocks": [{"type": "shell", "config": {"labels": {"de": "A", "en": "B"}}}]}
                    """);

            assertThat(parse(file, "de").blocks().get(0).getConfig().get("labels"))
                    .isEqualTo(Map.of("de", "A", "en", "B"));
        }
    }
}
