package com.automation.service.workflow;

import com.automation.dto.IntegrityCheck;
import com.automation.dto.request.ImportWorkflowRequest;
import com.automation.dto.response.ExportWorkflowResponse;
import com.automation.entity.BlockDefinition;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Integrity checksum of an exported definition — pool file or workflow export file (TODO b18939a6).
 *
 * <p>The export writes {@code "integrity": "sha256:<hex>"}, the import computes it again and refuses a
 * file whose content no longer matches; a file without the field is imported with a warning, so older
 * exports and hand-written files keep working. Field name after npm's {@code package-lock.json}
 * ({@code integrity}), value format after OCI content digests ({@code sha256:<hex>}) — the hex form the
 * rest of the platform already uses ({@code definition_hash}, built-in skill ledger).
 *
 * <p>Canonical form as in {@link WorkflowDefinitionHasher} (its mapper is reused): object keys sorted,
 * empty values (null, "", [], {}) dropped, array order kept, numbers re-read so the reading mapper's
 * float settings cannot change a checksum; the {@code integrity} field itself is not part of it.
 * Formatting, key order and whitespace of the file therefore do not matter, its content does.
 *
 * <p><b>What it protects against:</b> transport errors and accidental changes (a hand edit, a merge, a
 * tool rewriting the file). <b>Not</b> against deliberate tampering: whoever changes the file can
 * compute the checksum anew. That would need a signature with a key outside the file; for the shipped
 * pool the git history is the guarantor (docs/workflow-pool-kuratierung.md → Prüfsumme).
 */
@Slf4j
public final class DefinitionIntegrity {

    public static final String FIELD = "integrity";
    public static final String PREFIX = "sha256:";
    /** What an import takes over unchanged — the fields the export/import checksum covers. */
    private static final List<String> IMPORT_FIELDS = List.of(
            "description", "icon", "blocks", "instructions", "model", "version", "defaultContext", "metadata");

    private DefinitionIntegrity() {
    }

    /** {@code sha256:<hex>} over the canonical form of {@code document} without its {@code integrity} field. */
    public static String compute(Object document) {
        return PREFIX + WorkflowDefinitionHasher.sha256Hex(canonicalJson(document));
    }

    /** Checks the {@code integrity} field a parsed file carries against the rest of the file. */
    public static IntegrityCheck check(JsonNode document) {
        JsonNode declared = document == null ? null : document.get(FIELD);
        String value = declared == null || declared.isNull() ? null
                : declared.isTextual() ? declared.asText() : declared.toString();
        return check(value, document);
    }

    /** Checks {@code declared} against {@code document}; blank counts as missing. */
    public static IntegrityCheck check(String declared, Object document) {
        String actual = compute(document);
        if (declared == null || declared.isBlank()) {
            return new IntegrityCheck(IntegrityCheck.Status.MISSING, null, actual);
        }
        boolean matches = actual.equals(declared.trim().toLowerCase(Locale.ROOT));
        return new IntegrityCheck(matches ? IntegrityCheck.Status.VERIFIED : IntegrityCheck.Status.MISMATCH,
                declared, actual);
    }

    // ── Workflow export file (GET /workflows/export/{id} ↔ POST /workflows/import) ──────────────

    /**
     * Checksum of a workflow export. Covers what the import takes over unchanged — description, icon,
     * blocks, instructions, model, version, default context, metadata — not the name (the import dialog
     * lets the user rename) and not {@code exportedAt}.
     */
    public static String ofExport(ExportWorkflowResponse export) {
        return compute(exportDocument(export.description(), export.icon(), export.blocks(), export.instructions(),
                export.model(), export.version(), export.defaultContext(), export.metadata()));
    }

    /**
     * Checksum of a {@code POST /workflows/import} body built as a map (instance sync, TODO b18939a6):
     * over the same fields as {@link #ofExport}, taken from the body as it is sent.
     */
    public static String ofImportBody(Map<String, Object> body) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (String field : IMPORT_FIELDS) {
            document.put(field, body.get(field));
        }
        return compute(document);
    }

    /**
     * Refuses an import whose checksum does not match ({@link ErrorMessage#IMPORT_INTEGRITY_MISMATCH}, 400)
     * and logs a warning for one without a checksum.
     */
    public static IntegrityCheck verifyImport(ImportWorkflowRequest request) {
        IntegrityCheck check = check(request.integrity(), exportDocument(request.description(), request.icon(),
                request.blocks(), request.instructions(), request.model(), request.version(),
                request.defaultContext(), request.metadata()));
        if (check.mismatch()) {
            throw new LocalizedArgumentException(ErrorMessage.IMPORT_INTEGRITY_MISMATCH, Map.of(
                    "name", String.valueOf(request.name()), "declared", check.declared(), "actual", check.actual()));
        }
        if (check.status() == IntegrityCheck.Status.MISSING) {
            log.warn("Workflow import '{}' carries no integrity checksum — its content could not be verified",
                    request.name());
        }
        return check;
    }

    private static Map<String, Object> exportDocument(String description, String icon, List<BlockDefinition> blocks,
                                                      String instructions, String model, String version,
                                                      Map<String, Object> defaultContext, Map<String, Object> metadata) {
        List<Object> values = java.util.Arrays.asList(description, icon, blocks, instructions, model, version,
                defaultContext, metadata);
        Map<String, Object> document = new LinkedHashMap<>();
        for (int i = 0; i < IMPORT_FIELDS.size(); i++) {
            document.put(IMPORT_FIELDS.get(i), values.get(i));
        }
        return document;
    }

    // ── Canonical form ──────────────────────────────────────────────────────────────────────────

    static String canonicalJson(Object document) {
        try {
            // Through text and back: POJOs become a tree, and every number is read by the same mapper.
            JsonNode tree = WorkflowDefinitionHasher.CANONICAL.readTree(
                    WorkflowDefinitionHasher.CANONICAL.writeValueAsString(document));
            if (tree instanceof ObjectNode object) {
                object.remove(FIELD);
            }
            JsonNode normalized = normalize(tree);
            return WorkflowDefinitionHasher.CANONICAL.writeValueAsString(
                    normalized == null ? JsonNodeFactory.instance.objectNode() : normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Definition is not serialisable: " + e.getOriginalMessage(), e);
        }
    }

    /** Sorted keys, empty members dropped (recursively); {@code null} for an empty value. */
    private static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = normalize(node.get(name));
                if (value != null) {
                    sorted.set(name, value);
                }
            }
            return sorted.isEmpty() ? null : sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                JsonNode element = normalize(it.next());
                // Elements keep their place: an empty element stays as null instead of shifting the rest.
                array.add(element == null ? JsonNodeFactory.instance.nullNode() : element);
            }
            return array.isEmpty() ? null : array;
        }
        if (node.isTextual() && node.asText().isEmpty()) {
            return null;
        }
        return node;
    }
}
