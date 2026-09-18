package com.automation.service.pool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Entfernt Instanz-Bindungen aus einer Pool-JSON, bevor sie geschrieben wird.
 *
 * <p>Der Export hat die Personendaten und Instanz-UUIDs in die getrackten Pool-Dateien gebracht,
 * weil er den Workflow eins zu eins übernommen hat. Diese Klasse ist die Gegenprobe zum
 * Hygiene-Gate ({@code scripts/ci/pool-neutrality.sh}): was das Gate im Bestand beanstandet,
 * entsteht hier gar nicht erst.
 *
 * <p>Zwei Regeln, bewusst eng gefasst:
 * <ol>
 *   <li><b>Instanz-Referenzschlüssel</b> ({@code projectId}, {@code workflowId}, {@code runId} …)
 *       fallen weg — es sei denn, ihr Wert ist leer oder eine Template-Variable
 *       ({@code {{ project_id }}}). Ein Platzhalter ist die portable Parametrisierung und bleibt.</li>
 *   <li><b>UUID-Werte</b> fallen weg, egal unter welchem Schlüssel. Steht die UUID in einem
 *       längeren Text (Prompt), wird nur die UUID durch {@link #REDACTED_UUID} ersetzt, damit der
 *       Text nicht zerfällt.</li>
 * </ol>
 *
 * <p>Nicht wiederverwendbar: eine Instanz sammelt die Fundstellen eines Laufs in
 * {@link #removedPaths()}.
 */
public class PoolExportSanitizer {

    /** Platzhalter für eine UUID, die mitten in einem Text stand. */
    public static final String REDACTED_UUID = "REDACTED-UUID";

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Template-Variable wie {@code {{ project_id }}} — eine Parametrisierung, kein Instanzwert. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^}]*}}");

    /**
     * Die Instanz-Referenzschlüssel in camelCase — die eine Quelle für Export und Hygiene-Gate.
     * {@code PoolSanitizerKeysTest} erzeugt daraus {@code scripts/lib/pool-sanitizer.generated.sh},
     * das {@code scripts/ci/pool-neutrality.sh} liest (TODO 96640e6f). Verglichen wird normalisiert
     * (ohne {@code _}/{@code -}, klein), {@code project_id} zählt also mit.
     */
    private static final List<String> INSTANCE_REFERENCE_KEYS = List.of(
            "projectId", "workflowId", "runId", "scheduleId", "chainId",
            "executionId", "instanceId", "todoId", "sourceRunId", "lastFixRunId");

    private static final Set<String> NORMALIZED_INSTANCE_REFERENCE_KEYS =
            INSTANCE_REFERENCE_KEYS.stream().map(PoolExportSanitizer::normalize).collect(Collectors.toSet());

    /** Instanz-Referenzschlüssel in camelCase, in Deklarationsreihenfolge. */
    public static List<String> instanceReferenceKeys() {
        return INSTANCE_REFERENCE_KEYS;
    }

    private final List<String> removedPaths = new ArrayList<>();

    /** Pfade der entfernten oder ersetzten Stellen, z. B. {@code blocks[0].config.projectId}. */
    public List<String> removedPaths() {
        return List.copyOf(removedPaths);
    }

    /** Liefert eine bereinigte Kopie des Knotens. */
    public Object scrub(Object node) {
        return scrub("", node);
    }

    private Object scrub(String path, Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (isInstanceReference(key, entry.getValue())) {
                    removedPaths.add(childPath);
                    continue;
                }
                Object value = scrub(childPath, entry.getValue());
                if (value == DROP) {
                    removedPaths.add(childPath);
                    continue;
                }
                out.put(key, value);
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                String childPath = path + "[" + i + "]";
                Object value = scrub(childPath, list.get(i));
                // Ein Element, das nur eine UUID ist (z. B. in skillIds), fällt ganz weg —
                // ein null an seiner Stelle wäre für den Leser ein kaputter Eintrag.
                if (value == DROP) {
                    removedPaths.add(childPath);
                    continue;
                }
                out.add(value);
            }
            return out;
        }
        if (node instanceof String text) {
            if (UUID.matcher(text.trim()).matches()) {
                return DROP;
            }
            if (UUID.matcher(text).find()) {
                removedPaths.add(path);
                return UUID.matcher(text).replaceAll(REDACTED_UUID);
            }
        }
        return node;
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static boolean isInstanceReference(String key, Object value) {
        if (!NORMALIZED_INSTANCE_REFERENCE_KEYS.contains(normalize(key))) {
            return false;
        }
        if (!(value instanceof String s)) {
            return value != null;
        }
        return !s.isBlank() && !PLACEHOLDER.matcher(s).find();
    }

    /** Marker für „dieser Wert fällt weg“ — {@code null} wäre von einem echten null nicht zu trennen. */
    private static final Object DROP = new Object();
}
