package com.automation.util;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Zweisprachige Schritte in Vorlagen- und Pool-Dateien (TODO a129a661).
 *
 * <p>Name und Beschreibung einer Vorlage sind schon lange {@code {"de": …, "en": …}}. Eine Datei mit
 * dem Marker {@code "i18n": ["de", "en"]} darf dieselbe Form auch in {@code blocks} und
 * {@code instructions} verwenden: Jedes Objekt, dessen Schlüssel genau die deklarierten Sprachen
 * sind und dessen Werte Strings sind, ist ein lokalisierter Text. Beim Anlegen eines Workflows wird
 * eine Sprache gewählt, danach ist der Workflow einsprachig. Rückfall: gewünschte Sprache, dann
 * {@code en}, dann {@code de}.
 *
 * <p>Ohne Marker wird nichts angefasst. Sonst könnte eine echte Daten-Map mit den Schlüsseln
 * {@code de}/{@code en} (etwa in einem exportierten Workflow) still zu einem String werden.
 */
public final class LocalizedJson {

    /** Top-level key that opts a file into localized block content. */
    public static final String MARKER = "i18n";
    public static final String DEFAULT_LOCALE = "en";
    public static final List<String> SUPPORTED = List.of("en", "de");

    private static final List<String> LOCALIZED_FIELDS = List.of("blocks", "instructions");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private LocalizedJson() {
    }

    /** {@code de-DE} → {@code de}; empty or unsupported → {@code en}. */
    public static String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String lang = locale.strip().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return SUPPORTED.contains(lang) ? lang : DEFAULT_LOCALE;
    }

    /** The languages a file declares with its marker; empty when it has none. */
    public static List<String> declaredLocales(JsonNode root) {
        List<String> locales = new ArrayList<>();
        JsonNode marker = root == null ? null : root.get(MARKER);
        if (marker != null && marker.isArray()) {
            marker.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    locales.add(n.asText());
                }
            });
        }
        return locales;
    }

    public static boolean isLocalized(JsonNode root) {
        return !declaredLocales(root).isEmpty();
    }

    /** An object whose keys are exactly {@code locales} and whose values are all strings. */
    public static boolean isLocalizedText(JsonNode node, Collection<String> locales) {
        if (node == null || !node.isObject() || node.size() != locales.size() || locales.isEmpty()) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                return false;
            }
            keys.add(field.getKey());
        }
        return keys.equals(new HashSet<>(locales));
    }

    /** Deep copy of {@code node} with every localized text replaced by the chosen language. Never mutates the input. */
    public static JsonNode resolve(JsonNode node, Collection<String> locales, String locale) {
        if (node == null) {
            return null;
        }
        if (isLocalizedText(node, locales)) {
            return TextNode.valueOf(pick(node, locale));
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(f -> copy.set(f.getKey(), resolve(f.getValue(), locales, locale)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(resolve(element, locales, locale)));
            return copy;
        }
        return node.deepCopy();
    }

    /**
     * Copy of a whole template or pool file in one language: only {@code blocks} and
     * {@code instructions} are resolved. {@code name}/{@code description} keep their maps, because
     * the readers expect them there. A file without marker comes back as an unchanged copy.
     */
    public static JsonNode resolveDefinition(JsonNode root, String locale) {
        List<String> locales = declaredLocales(root);
        if (locales.isEmpty() || !root.isObject()) {
            return root == null ? null : root.deepCopy();
        }
        ObjectNode copy = root.deepCopy();
        for (String field : LOCALIZED_FIELDS) {
            if (root.has(field)) {
                copy.set(field, resolve(root.get(field), locales, locale));
            }
        }
        return copy;
    }

    /**
     * The unresolved {@code blocks} and {@code instructions} of a localized file, with its marker,
     * as JSON text. It is stored next to the English blocks and resolved when read. {@code null} for
     * a file without marker.
     */
    public static String localizedSource(JsonNode root) {
        if (!isLocalized(root)) {
            return null;
        }
        ObjectNode source = JsonNodeFactory.instance.objectNode();
        source.set(MARKER, root.get(MARKER).deepCopy());
        for (String field : LOCALIZED_FIELDS) {
            if (root.has(field)) {
                source.set(field, root.get(field).deepCopy());
            }
        }
        return source.toString();
    }

    /** Blocks in {@code locale} from a stored source; {@code fallback} when there is no usable source. */
    public static List<BlockDefinition> blocksFor(String source, String locale, List<BlockDefinition> fallback) {
        JsonNode resolved = resolvedSource(source, locale);
        if (resolved == null || !resolved.has("blocks")) {
            return fallback;
        }
        try {
            return MAPPER.convertValue(resolved.get("blocks"), new TypeReference<>() {});
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Instructions in {@code locale} from a stored source; {@code fallback} when the source has none. */
    public static String instructionsFor(String source, String locale, String fallback) {
        JsonNode resolved = resolvedSource(source, locale);
        if (resolved == null || !resolved.has("instructions") || resolved.get("instructions").isNull()) {
            return fallback;
        }
        return resolved.get("instructions").asText();
    }

    /** One language of a localized map such as a template name; {@code null} for a null or empty map. */
    public static String pick(Map<String, String> localized, String locale) {
        if (localized == null || localized.isEmpty()) {
            return null;
        }
        String lang = normalize(locale);
        for (String candidate : List.of(lang, DEFAULT_LOCALE, "de")) {
            String value = localized.get(candidate);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return localized.values().iterator().next();
    }

    private static String pick(JsonNode localized, String locale) {
        Map<String, String> values = new LinkedHashMap<>();
        localized.fields().forEachRemaining(f -> values.put(f.getKey(), f.getValue().asText()));
        return pick(values, locale);
    }

    private static JsonNode resolvedSource(String source, String locale) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return resolveDefinition(MAPPER.readTree(source), normalize(locale));
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
