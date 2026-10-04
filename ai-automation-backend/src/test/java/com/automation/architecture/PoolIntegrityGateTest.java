package com.automation.architecture;

import com.automation.dto.IntegrityCheck;
import com.automation.service.workflow.DefinitionIntegrity;
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
 * {@code ./mvnw test -Dtest=PoolIntegrityGateTest -Dpool.integrity.write=true}
 *
 * <p>The write mode only replaces or appends the one {@code "integrity"} line, so the file's own layout
 * stays as it is; it re-reads the result and fails if anything but that field changed.
 */
class PoolIntegrityGateTest {

    private static final String WRITE_HINT = "./mvnw test -Dtest=PoolIntegrityGateTest -Dpool.integrity.write=true";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The top-level field as the write mode writes it: on its own line, indented by two spaces. */
    private static final Pattern FIELD_LINE =
            Pattern.compile("(?m)^  \"" + DefinitionIntegrity.FIELD + "\"\\s*:\\s*\"[^\"]*\"");

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
            ObjectNode expected = before.deepCopy();
            expected.put(DefinitionIntegrity.FIELD, checksum);
            assertThat(MAPPER.readTree(updated)).as("write mode changed more than the integrity field of %s", file)
                    .isEqualTo(expected);
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

    /** The text of a pool file with its top-level {@code integrity} line set to {@code checksum}. */
    static String withChecksum(String text, String checksum) {
        String line = "  \"" + DefinitionIntegrity.FIELD + "\": \"" + checksum + "\"";
        Matcher existing = FIELD_LINE.matcher(text);
        if (existing.find()) {
            return text.substring(0, existing.start()) + line + text.substring(existing.end());
        }
        int close = text.lastIndexOf('}');
        int lastValueEnd = close - 1;
        while (lastValueEnd >= 0 && Character.isWhitespace(text.charAt(lastValueEnd))) {
            lastValueEnd--;
        }
        return text.substring(0, lastValueEnd + 1) + ",\n" + line + "\n" + text.substring(close);
    }

    private static List<Path> poolFiles() throws IOException {
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        assumeThat(pool).as("workflow-pool/ not reachable from the working directory").isNotNull();
        List<Path> files = ShippedDefinitions.poolFiles(pool);
        assertThat(files).as("no pool files under %s", pool).isNotEmpty();
        return files;
    }
}
