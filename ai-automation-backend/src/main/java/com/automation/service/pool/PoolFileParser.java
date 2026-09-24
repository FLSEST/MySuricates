package com.automation.service.pool;

import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads one pool file as a {@link PoolWorkflowEntry} in one language. A bilingual file
 * ({@code "i18n": ["de","en"]}) gets its blocks and instructions resolved to {@code locale}
 * ({@link LocalizedJson}); name and description stay maps. Extracted from {@link WorkflowPoolService}.
 *
 * <p>A file it cannot read is rejected with all its problems at once, so one round of corrections
 * is enough.
 */
final class PoolFileParser {

    /** At most this many instance bindings are named in a rejection; the gate lists them all. */
    private static final int MAX_NAMED_BINDINGS = 5;

    private PoolFileParser() {
    }

    /**
     * A file that is no pool entry, e.g. a plain workflow export ({@code "name": "Tech-Radar"}
     * instead of {@code {"de": …, "en": …}}) copied into the pool. The message is one line: the
     * file, every problem with its field path and the type found, and — since the file needs fixing
     * anyway — what the pool rules object to although the parser tolerates it ({@link #poolRuleNotes}).
     */
    static final class PoolFileFormatException extends IOException {

        PoolFileFormatException(Path file, List<String> problems, List<String> poolRuleNotes) {
            super(oneLine("Pool file " + file.getFileName() + ": " + String.join("; ", problems)
                    + (poolRuleNotes.isEmpty() ? "" : ". Also against the pool rules "
                    + "(docs/workflow-pool-kuratierung.md): " + String.join("; ", poolRuleNotes))));
        }
    }

    /**
     * @return the entry, never {@code null}
     * @throws PoolFileFormatException naming every problem of the file (missing or wrongly typed field)
     * @throws IOException             when the file cannot be read or is no JSON
     */
    static PoolWorkflowEntry parse(Path file, ObjectMapper objectMapper, String locale) throws IOException {
        JsonNode root = objectMapper.readTree(file.toFile());
        if (root == null || !root.isObject()) {
            throw new PoolFileFormatException(file,
                    List.of("top level must be a JSON object, found " + typeOf(root)), List.of());
        }
        JsonNode resolved = LocalizedJson.resolveDefinition(root, LocalizedJson.normalize(locale));
        Map<String, Object> raw = objectMapper.convertValue(resolved, new TypeReference<>() {});
        Fields fields = new Fields(raw);

        Map<String, String> name = fields.localized("name");
        if (raw.get("name") == null) {
            fields.problem("field 'name' is missing");
        } else if (name != null && name.isEmpty()) {
            fields.problem("field 'name' is empty, expected " + Fields.LOCALIZED);
        }
        Map<String, String> description = fields.localized("description");
        List<BlockDefinition> blocks = fields.blocks(objectMapper);
        String icon = fields.string("icon");
        String category = fields.string("category");
        // Bewusst ohne Default und ohne Typprüfung: ein Eintrag ohne 'scope' soll im Pool-Browser als
        // "nicht deklariert" erkennbar sein, nicht als portabel durchgehen.
        String scope = raw.get("scope") instanceof String s ? s : null;
        List<String> tags = fields.stringList("tags");
        String instructions = fields.string("instructions");
        String model = fields.string("model");
        String effort = fields.string("effort");
        String version = fields.string("version");
        String author = fields.string("author");
        Map<String, Object> defaultContext = fields.object("defaultContext");
        Map<String, Object> metadata = fields.object("metadata");

        if (!fields.problems.isEmpty()) {
            throw new PoolFileFormatException(file, fields.problems, poolRuleNotes(raw));
        }

        String filename = file.getFileName().toString();
        return new PoolWorkflowEntry(
                filename.replace(".json", ""), name, description == null ? Map.of() : description,
                icon == null ? "📋" : icon, category, scope, tags == null ? List.of() : tags,
                blocks, instructions, model, effort, version == null ? "1.0.0" : version, author,
                defaultContext, metadata, filename
        );
    }

    /**
     * What {@code scripts/ci/pool-neutrality.sh} would report for the file although the parser
     * accepts it: instance bindings the pool export removes (PROJECT-ID, INSTANCE-REF, UUID —
     * {@link PoolExportSanitizer}) and a missing or unknown {@code scope} (SCOPE). Only part of a
     * rejection; a file with nothing but these still loads, as it always did.
     */
    private static List<String> poolRuleNotes(Map<String, Object> raw) {
        List<String> notes = new ArrayList<>();
        PoolExportSanitizer sanitizer = new PoolExportSanitizer();
        sanitizer.scrub(raw);
        List<String> bindings = sanitizer.removedPaths();
        if (!bindings.isEmpty()) {
            int shown = Math.min(bindings.size(), MAX_NAMED_BINDINGS);
            String more = bindings.size() > shown ? " and " + (bindings.size() - shown) + " more" : "";
            notes.add("instance bindings the pool export would remove: "
                    + String.join(", ", bindings.subList(0, shown)) + more);
        }
        Object scope = raw.get("scope");
        if (scope == null) {
            notes.add("field 'scope' is missing, expected one of " + PoolScope.allowedValues());
        } else if (Arrays.stream(PoolScope.values()).noneMatch(s -> s.jsonValue().equals(scope))) {
            notes.add("field 'scope' must be one of " + PoolScope.allowedValues() + ", found " + typeOf(scope));
        }
        return notes;
    }

    /** Typed reads of the top-level fields: a wrong type is recorded as a problem and reads as absent. */
    private static final class Fields {

        static final String LOCALIZED = "an object {\"de\": \"…\", \"en\": \"…\"}";

        private final Map<String, Object> raw;
        private final List<String> problems = new ArrayList<>();

        Fields(Map<String, Object> raw) {
            this.raw = raw;
        }

        void problem(String problem) {
            problems.add(problem);
        }

        /** {@code {"de": "…", "en": "…"}}; {@code null} when absent or wrong. */
        Map<String, String> localized(String field) {
            Object value = raw.get(field);
            if (value == null) {
                return null;
            }
            if (!(value instanceof Map<?, ?> map)) {
                return wrongType(field, LOCALIZED, value);
            }
            boolean texts = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getValue() instanceof String)) {
                    wrongType(field + "." + entry.getKey(), "a string", entry.getValue());
                    texts = false;
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, String> localized = texts ? (Map<String, String>) map : null;
            return localized;
        }

        String string(String field) {
            Object value = raw.get(field);
            if (value == null || value instanceof String) {
                return (String) value;
            }
            return wrongType(field, "a string", value);
        }

        List<String> stringList(String field) {
            Object value = raw.get(field);
            if (value == null) {
                return null;
            }
            if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
                return wrongType(field, "an array of strings", value);
            }
            @SuppressWarnings("unchecked")
            List<String> strings = (List<String>) list;
            return strings;
        }

        Map<String, Object> object(String field) {
            Object value = raw.get(field);
            if (value == null) {
                return null;
            }
            if (!(value instanceof Map<?, ?>)) {
                return wrongType(field, "an object", value);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return map;
        }

        List<BlockDefinition> blocks(ObjectMapper objectMapper) {
            Object value = raw.get("blocks");
            if (value == null) {
                problem("field 'blocks' is missing");
                return null;
            }
            if (!(value instanceof List<?>)) {
                return wrongType("blocks", "an array of blocks", value);
            }
            try {
                return objectMapper.convertValue(value, new TypeReference<>() {});
            } catch (IllegalArgumentException e) {
                problem(blockProblem(e));
                return null;
            }
        }

        /** Names the path Jackson stopped at, e.g. {@code blocks[3].on_failure}. */
        private static String blockProblem(IllegalArgumentException e) {
            if (!(e.getCause() instanceof JsonMappingException mapping)) {
                return "field 'blocks' is not a valid block list: " + e.getMessage();
            }
            StringBuilder path = new StringBuilder("blocks");
            for (JsonMappingException.Reference reference : mapping.getPath()) {
                if (reference.getIndex() >= 0) {
                    path.append('[').append(reference.getIndex()).append(']');
                } else if (reference.getFieldName() != null) {
                    path.append('.').append(reference.getFieldName());
                }
            }
            return "field '" + path + "' is not valid: " + mapping.getOriginalMessage();
        }

        private <T> T wrongType(String field, String expected, Object found) {
            problem("field '" + field + "' must be " + expected + ", found " + typeOf(found));
            return null;
        }
    }

    private static String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof JsonNode node) {
            return node.getNodeType().name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof String s) {
            return "string \"" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "\"";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value.getClass().getSimpleName();
    }

    /** The log line stays one line even when a found value or a Jackson message holds a line break. */
    private static String oneLine(String text) {
        return text.replaceAll("\\s*\\R\\s*", " ");
    }
}
