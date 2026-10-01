package com.automation.service.workflow.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die Config-Schlüssel jedes Blocktyps — dieselbe Datei, aus der der Monaco-Config-Editor sein Schema baut
 * (TODO f5153c47). Quelle ist {@code ai-automation-frontend/src/data/blockConfigSchema.json}; der
 * Maven-Build kopiert sie nach {@code classpath:schema/} (siehe {@code pom.xml}, {@code deploy/Dockerfile}).
 * Eine zweite, handgepflegte Liste im Backend wäre genau die Drift, die dieser Weg verhindert.
 *
 * <p>Die Zusammenstellung folgt dem Editor ({@code propertiesFor} in {@code blockConfigSchema.ts}): die
 * Schlüssel des Typs, dann die KI-Schlüssel (nur bei KI-Blocktypen), dann die gemeinsamen, zuletzt
 * {@code on_failure}.
 */
public final class BlockConfigSchema {

    static final String RESOURCE = "schema/blockConfigSchema.json";

    /**
     * Ein Config-Schlüssel.
     *
     * @param values     erlaubte Werte, leer = freier Wert
     * @param legacy     alter Wert, der weiter gilt (z. B. {@code execution_mode: claude}), sonst {@code null}
     * @param ignoreCase der Executor vergleicht ohne Groß-/Kleinschreibung
     * @param legacyFor  alter Name eines anderen Schlüssels, den der Executor weiter liest, sonst {@code null}
     * @param onlyWith   der Executor liest den Schlüssel nur, wenn diese Schlüssel diese Werte haben
     *                   (z. B. {@code container_image} eines retry-Blocks nur mit {@code execution_mode: shell})
     */
    public record Field(String key, List<String> values, String legacy, boolean ignoreCase, String legacyFor,
                        Map<String, String> onlyWith) {

        /** Ob der Wert zu den erlaubten gehört; ohne feste Werte immer. */
        public boolean allows(String value) {
            if (values.isEmpty()) return true;
            for (String allowed : allowedValues()) {
                if (ignoreCase ? allowed.equalsIgnoreCase(value.strip()) : allowed.equals(value)) return true;
            }
            return false;
        }

        /** Erlaubte Werte samt Legacy-Wert — wie {@code allowedConfigValues} im Editor. */
        public List<String> allowedValues() {
            if (legacy == null) return values;
            List<String> all = new ArrayList<>(values);
            all.add(legacy);
            return all;
        }
    }

    private record Spec(List<String> required, List<String> requiredOneOf, Map<String, Field> fields) {}

    private static final class Holder {
        private static final BlockConfigSchema INSTANCE = load();
    }

    private final Map<String, Spec> blockTypes;
    private final Set<String> aiBlockTypes;
    private final Map<String, Field> aiFields;
    private final Map<String, Field> commonFields;
    private final Field onFailure;

    private BlockConfigSchema(JsonNode root) {
        Map<String, JsonNode> shared = new LinkedHashMap<>();
        root.path("sharedFields").properties().forEach(e -> shared.put(e.getKey(), e.getValue()));

        this.onFailure = field("on_failure", root.path("onFailure"), shared);
        this.commonFields = fields(root.path("commonFields"), shared);
        this.aiFields = fields(root.path("ai").path("fields"), shared);
        Set<String> ai = new LinkedHashSet<>();
        root.path("ai").path("blockTypes").forEach(t -> ai.add(t.asText()));
        this.aiBlockTypes = Collections.unmodifiableSet(ai);

        Map<String, Spec> types = new LinkedHashMap<>();
        root.path("blockTypes").properties().forEach(e -> {
            List<String> required = new ArrayList<>();
            e.getValue().path("required").forEach(r -> required.add(r.asText()));
            List<String> oneOf = new ArrayList<>();
            e.getValue().path("requiredOneOf").forEach(r -> oneOf.add(r.asText()));
            types.put(e.getKey(), new Spec(List.copyOf(required), List.copyOf(oneOf),
                    fields(e.getValue().path("fields"), shared)));
        });
        if (types.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " enthält keine Blocktypen");
        }
        this.blockTypes = Collections.unmodifiableMap(types);
    }

    /** Das Schema vom Classpath, einmal geladen. Fehlt die Datei, ist der Build kaputt — das soll laut scheitern. */
    public static BlockConfigSchema get() {
        return Holder.INSTANCE;
    }

    static BlockConfigSchema parse(InputStream json) throws IOException {
        return new BlockConfigSchema(new ObjectMapper().readTree(json));
    }

    private static BlockConfigSchema load() {
        try (InputStream in = BlockConfigSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " fehlt auf dem Classpath — der Maven-Build kopiert"
                        + " es aus ai-automation-frontend/src/data (execution sync-block-config-schema)");
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Block-Config-Schema nicht lesbar: " + RESOURCE, e);
        }
    }

    /** Ob es für den Blocktyp ein Schema gibt. Für andere Typen prüft der Analyzer keine Schlüssel. */
    public boolean knowsType(String type) {
        return type != null && blockTypes.containsKey(type);
    }

    public Set<String> blockTypes() {
        return blockTypes.keySet();
    }

    /** Alle Schlüssel, die der Typ kennt, in der Reihenfolge des Editors; leer für unbekannte Typen. */
    public Map<String, Field> fieldsFor(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        if (spec == null) return Map.of();
        Map<String, Field> all = new LinkedHashMap<>(spec.fields());
        if (aiBlockTypes.contains(type)) aiFields.forEach(all::putIfAbsent);
        commonFields.forEach(all::putIfAbsent);
        all.put(onFailure.key(), onFailure);
        return all;
    }

    /** Die Schlüssel des Typs selbst, ohne KI-, gemeinsame Schlüssel und {@code on_failure}. */
    public Set<String> ownKeys(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        return spec == null ? Set.of() : spec.fields().keySet();
    }

    public List<String> requiredKeys(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        return spec == null ? List.of() : spec.required();
    }

    /** Schlüssel, von denen genau einer gesetzt sein muss (todo-update: {@code id} oder {@code updates}); sonst leer. */
    public List<String> requiredOneOfKeys(String type) {
        Spec spec = type == null ? null : blockTypes.get(type);
        return spec == null ? List.of() : spec.requiredOneOf();
    }

    public Set<String> aiBlockTypes() {
        return aiBlockTypes;
    }

    public Set<String> aiKeys() {
        return aiFields.keySet();
    }

    public Set<String> commonKeys() {
        return commonFields.keySet();
    }

    private static Map<String, Field> fields(JsonNode node, Map<String, JsonNode> shared) {
        Map<String, Field> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), field(e.getKey(), e.getValue(), shared)));
        return Collections.unmodifiableMap(out);
    }

    /** Ein Feld; der Text {@code "#name"} verweist auf {@code sharedFields.name}. */
    private static Field field(String key, JsonNode node, Map<String, JsonNode> shared) {
        if (node.isTextual() && node.asText().startsWith("#")) {
            JsonNode resolved = shared.get(node.asText().substring(1));
            if (resolved == null) {
                throw new IllegalStateException(RESOURCE + ": unbekanntes sharedField " + node.asText() + " bei " + key);
            }
            node = resolved;
        }
        List<String> values = new ArrayList<>();
        node.path("values").forEach(v -> values.add(v.path("value").asText()));
        Map<String, String> onlyWith = new LinkedHashMap<>();
        node.path("onlyWith").properties().forEach(e -> onlyWith.put(e.getKey(), e.getValue().asText()));
        return new Field(key, List.copyOf(values), textOrNull(node.path("legacy")),
                node.path("ignoreCase").asBoolean(false), textOrNull(node.path("legacyFor")),
                Collections.unmodifiableMap(onlyWith));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }
}
