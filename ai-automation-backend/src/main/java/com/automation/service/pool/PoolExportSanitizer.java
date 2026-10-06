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
 * Removes instance bindings from a pool JSON before it is written.
 *
 * <p>The export brought personal data and instance UUIDs into the tracked pool files,
 * because it took over the workflow one to one. This class is the counter-check to the
 * hygiene gate ({@code scripts/ci/pool-neutrality.sh}): what the gate objects to in the existing files
 * is not produced here in the first place.
 *
 * <p>Two rules, deliberately narrow:
 * <ol>
 *   <li><b>Instance reference keys</b> ({@code projectId}, {@code workflowId}, {@code runId} …)
 *       are dropped - unless their value is empty or a template variable
 *       ({@code {{ project_id }}}). A placeholder is the portable parametrisation and stays.</li>
 *   <li><b>UUID values</b> are dropped, regardless of the key. If the UUID sits in a
 *       longer text (prompt), only the UUID is replaced by {@link #REDACTED_UUID}, so that the
 *       text does not fall apart.</li>
 * </ol>
 *
 * <p>Not reusable: an instance collects the findings of one run in
 * {@link #removedPaths()}.
 */
public class PoolExportSanitizer {

    /** Placeholder for a UUID that stood in the middle of a text. */
    public static final String REDACTED_UUID = "REDACTED-UUID";

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Template variable like {@code {{ project_id }}} - a parametrisation, not an instance value. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^}]*}}");

    /**
     * The instance reference keys in camelCase - the single source for export and hygiene gate.
     * {@code PoolSanitizerKeysTest} generates {@code scripts/lib/pool-sanitizer.generated.sh} from it,
     * which {@code scripts/ci/pool-neutrality.sh} reads (TODO 96640e6f). Comparison is normalised
     * (without {@code _}/{@code -}, lower case), so {@code project_id} counts too.
     */
    private static final List<String> INSTANCE_REFERENCE_KEYS = List.of(
            "projectId", "workflowId", "runId", "scheduleId", "chainId",
            "executionId", "instanceId", "todoId", "sourceRunId", "lastFixRunId");

    private static final Set<String> NORMALIZED_INSTANCE_REFERENCE_KEYS =
            INSTANCE_REFERENCE_KEYS.stream().map(PoolExportSanitizer::normalize).collect(Collectors.toSet());

    /** Instance reference keys in camelCase, in declaration order. */
    public static List<String> instanceReferenceKeys() {
        return INSTANCE_REFERENCE_KEYS;
    }

    private final List<String> removedPaths = new ArrayList<>();

    /** Paths of the removed or replaced places, e.g. {@code blocks[0].config.projectId}. */
    public List<String> removedPaths() {
        return List.copyOf(removedPaths);
    }

    /** Returns a sanitised copy of the node. */
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
                // An element that is only a UUID (e.g. in skillIds) is dropped entirely -
                // a null in its place would look like a broken entry to the reader.
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

    /** Marker for "this value is dropped" - {@code null} could not be told apart from a real null. */
    private static final Object DROP = new Object();
}
