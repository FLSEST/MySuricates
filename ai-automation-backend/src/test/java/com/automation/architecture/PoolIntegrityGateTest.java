package com.automation.architecture;

import com.automation.dto.IntegrityCheck;
import com.automation.service.workflow.DefinitionIntegrity;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Integrity gate for the shipped pool (TODO b18939a6), after the model of {@code BuiltInSkillVersionGateTest}.
 *
 * <p>Every git-tracked file in {@code workflow-pool/} carries {@code "integrity": "sha256:<hex>"} over its
 * content ({@link DefinitionIntegrity}), and the install refuses a file whose content no longer matches.
 * So a hand edit of a pool file must take the checksum along — otherwise the file would ship and then be
 * refused on every install. This test makes that loud at build time instead of at install time.
 *
 * <p>Unlike the skill ledger the checksum lives in the file itself (Homebrew-style: the checking value in
 * the versioned text), so there is no separate list to keep in step. After a deliberate change, write
 * the checksums anew and commit them with the change:
 * {@code cd ai-automation-backend && ./mvnw test -Dtest='PoolIntegrityGateTest*' -Dpool.integrity.write=true}
 *
 * <p>The write mode only replaces or appends the one {@code "integrity"} line, so the file's own layout
 * stays as it is (any indentation); it re-reads the result and fails if anything but that field changed.
 * Files are read with duplicate-key detection: a second {@code "integrity"} key (a hand-pasted line
 * beside the generated one) would otherwise silently win or lose, so the gate fails on it.
 */
class PoolIntegrityGateTest {

    private static final String WRITE_HINT =
            "cd ai-automation-backend && ./mvnw test -Dtest='PoolIntegrityGateTest*' -Dpool.integrity.write=true";
    /** Strict: a key twice in one object (e.g. two "integrity" lines) is an error, not "last one wins". */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    /** An {@code "integrity"} line, whatever its indentation; which one is top-level decides the parse. */
    private static final Pattern FIELD_LINE =
            Pattern.compile("(?m)^([ \\t]*)\"" + DefinitionIntegrity.FIELD + "\"\\s*:\\s*\"[^\"]*\"");

    @BeforeAll
    static void writeChecksumsIfRequested() throws IOException {
        if (!Boolean.getBoolean("pool.integrity.write")) {
            return;
        }
        for (Path file : poolFiles()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode before = MAPPER.readTree(text);
            String checksum = DefinitionIntegrity.compute(before);
            String updated = withChecksum(text, checksum);
            if (!updated.equals(text)) {
                Files.writeString(file, updated, StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    void everyShippedPoolFile_carriesAMatchingChecksum() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : poolFiles()) {
            IntegrityCheck check = DefinitionIntegrity.check(MAPPER.readTree(file.toFile()));
            switch (check.status()) {
                case VERIFIED -> { }
                case MISSING -> problems.add(file.getFileName() + ": no \"integrity\" field");
                case MISMATCH -> problems.add("%s: declares %s, content gives %s"
                        .formatted(file.getFileName(), check.declared(), check.actual()));
            }
        }

        assertThat(problems)
                .as("""
                        Pool files whose integrity checksum is missing or no longer matches their content — the \
                        install would refuse them. After a deliberate change, write the checksums anew and commit \
                        them with it: %s""".formatted(WRITE_HINT))
                .isEmpty();
    }

    @Test
    void writeMode_replacesAnExistingLine_orAppendsTheField_keepingTheLayout() throws IOException {
        String withoutField = "{\n  \"name\" : {\n    \"en\" : \"X\"\n  },\n  \"blocks\" : [ ]\n}\n";
        String appended = withChecksum(withoutField, "sha256:aa");
        assertThat(appended).isEqualTo(
                "{\n  \"name\" : {\n    \"en\" : \"X\"\n  },\n  \"blocks\" : [ ],\n  \"integrity\": \"sha256:aa\"\n}\n");

        assertThat(withChecksum(appended, "sha256:bb")).isEqualTo(appended.replace("sha256:aa", "sha256:bb"));
    }

    @Test
    void writeMode_keepsAnyIndentation_andLeavesANestedKeyOfTheSameNameAlone() throws IOException {
        String file = "{\n    \"blocks\": [{\"config\": {\n        \"integrity\": \"keep\"}}],\n"
                + "\t\"integrity\":\"sha256:aa\"\n}\n";

        String updated = withChecksum(file, "sha256:bb");

        assertThat(updated).isEqualTo(file.replace("\t\"integrity\":\"sha256:aa\"", "\t\"integrity\": \"sha256:bb\""));
        assertThat(withChecksum("{\n    \"blocks\": [{\"config\": {\n        \"integrity\": \"keep\"}}]\n}\n", "sha256:cc"))
                .endsWith(",\n  \"integrity\": \"sha256:cc\"\n}\n").contains("\"integrity\": \"keep\"");
    }

    /** A second, hand-pasted "integrity" line must not slip through as "last one wins". */
    @Test
    void duplicateIntegrityKey_failsTheGateRead() {
        String file = "{\"name\": {\"en\": \"X\"},\n  \"integrity\": \"sha256:aa\",\n  \"integrity\": \"sha256:bb\"\n}";

        assertThatThrownBy(() -> MAPPER.readTree(file))
                .isInstanceOf(JsonParseException.class)
                .hasMessageContaining("Duplicate field 'integrity'");
        assertThatThrownBy(() -> withChecksum(file, "sha256:cc")).isInstanceOf(JsonParseException.class);
    }

    /**
     * The text of a pool file with its top-level {@code integrity} line set to {@code checksum}. An existing
     * line keeps its indentation; a key of the same name nested deeper (in a block config) is left alone —
     * the candidate whose replacement changes exactly the top-level field is the one. Fails when the result
     * differs from the file in anything but that field.
     */
    static String withChecksum(String text, String checksum) throws IOException {
        ObjectNode expected = (ObjectNode) MAPPER.readTree(text);
        expected.put(DefinitionIntegrity.FIELD, checksum);
        String field = "\"" + DefinitionIntegrity.FIELD + "\": \"" + checksum + "\"";
        Matcher candidate = FIELD_LINE.matcher(text);
        while (candidate.find()) {
            String replaced = text.substring(0, candidate.start()) + candidate.group(1) + field
                    + text.substring(candidate.end());
            if (MAPPER.readTree(replaced).equals(expected)) {
                return replaced;
            }
        }
        int close = text.lastIndexOf('}');
        int lastValueEnd = close - 1;
        while (lastValueEnd >= 0 && Character.isWhitespace(text.charAt(lastValueEnd))) {
            lastValueEnd--;
        }
        String appended = text.substring(0, lastValueEnd + 1) + ",\n  " + field + "\n" + text.substring(close);
        assertThat(MAPPER.readTree(appended)).as("write mode changed more than the integrity field").isEqualTo(expected);
        return appended;
    }

    private static List<Path> poolFiles() throws IOException {
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        assumeThat(pool).as("workflow-pool/ not reachable from the working directory").isNotNull();
        List<Path> files = ShippedDefinitions.poolFiles(pool);
        assertThat(files).as("no pool files under %s", pool).isNotEmpty();
        return files;
    }
}
