package com.automation.service.pool;

import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
            List<String> gitTracked = trackedPoolFiles(pool);
            assertThat(gitTracked).as("tracked pool files").isNotEmpty();

            for (String filename : gitTracked) {
                for (String locale : List.of("de", "en")) {
                    PoolWorkflowEntry entry = PoolFileParser.parse(pool.resolve(filename), objectMapper, locale);
                    assertThat(entry).as(filename + " (" + locale + ")").isNotNull();
                    assertThat(entry.name()).as(filename).containsKeys("de", "en");
                    assertThat(entry.blocks()).as(filename).isNotEmpty();
                }
            }
        }

        private List<String> trackedPoolFiles(Path pool) throws Exception {
            Process git = new ProcessBuilder("git", "ls-files", "--", "*.json")
                    .directory(pool.toFile()).redirectErrorStream(true).start();
            String out = new String(git.getInputStream().readAllBytes());
            if (git.waitFor() == 0 && !out.isBlank()) {
                return out.lines().map(String::strip).filter(l -> !l.contains("/")).toList();
            }
            // Kein git (etwa in einem Quell-Archiv): dann zählen alle Dateien im Verzeichnis.
            List<String> all = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(pool, "*.json")) {
                stream.forEach(p -> all.add(p.getFileName().toString()));
            }
            return all;
        }
    }

    @Nested
    class MissingRequiredFields {

        @Test
        void missingName_returnsNull() throws Exception {
            Path file = write("no-name.json", """
                    {"blocks": [{"type": "shell", "config": {}}]}
                    """);

            assertThat(parse(file, "en")).isNull();
        }

        @Test
        void emptyName_returnsNull() throws Exception {
            Path file = write("empty-name.json", """
                    {"name": {}, "blocks": []}
                    """);

            assertThat(parse(file, "en")).isNull();
        }

        @Test
        void missingBlocks_returnsNull() throws Exception {
            Path file = write("no-blocks.json", """
                    {"name": {"en": "No blocks"}}
                    """);

            assertThat(parse(file, "en")).isNull();
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
         * (string name, projectId) copied into the pool. Before, this was a bare ClassCastException.
         */
        @Test
        void plainStringName_namesFileFieldAndExpectedShape() throws Exception {
            Path file = write("tech-radar.json", """
                    {"name": "Tech-Radar", "description": "Weekly digest", "projectId": "p-1",
                     "blocks": [{"type": "shell", "config": {"command": "date"}}]}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .isInstanceOf(PoolFileParser.PoolFileFormatException.class)
                    .hasMessage("Pool file tech-radar.json: field 'name' must be an object "
                            + "{\"de\": \"…\", \"en\": \"…\"}, found string \"Tech-Radar\"");
        }

        @Test
        void plainStringDescription_isNamed() throws Exception {
            Path file = write("desc.json", """
                    {"name": {"de": "N", "en": "N"}, "description": "plain", "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'description' must be an object");
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
        void blocksAsObject_isNamed() throws Exception {
            Path file = write("blocks.json", """
                    {"name": {"en": "N"}, "blocks": {"type": "shell"}}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'blocks' must be an array of blocks, found object");
        }

        @Test
        void blockWithUnconvertibleField_namesBlocks() throws Exception {
            Path file = write("bad-block.json", """
                    {"name": {"en": "N"}, "blocks": [{"type": "shell", "config": "not a map"}]}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .isInstanceOf(PoolFileParser.PoolFileFormatException.class)
                    .hasMessageContaining("bad-block.json: field 'blocks' is not a valid block list");
        }

        @Test
        void tagsWithNonStrings_isNamed() throws Exception {
            Path file = write("tags.json", """
                    {"name": {"en": "N"}, "tags": ["ok", 3], "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'tags' must be an array of strings, found array");
        }

        @Test
        void wrongTypedStringField_isNamed() throws Exception {
            Path file = write("model.json", """
                    {"name": {"en": "N"}, "model": ["opus"], "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'model' must be a string, found array");
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
        void defaultContextAsString_isNamed() throws Exception {
            Path file = write("ctx.json", """
                    {"name": {"en": "N"}, "defaultContext": "x", "blocks": []}
                    """);

            assertThatThrownBy(() -> parse(file, "en"))
                    .hasMessageContaining("field 'defaultContext' must be an object, found string");
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
