package com.automation.service.project.bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep copy of a JSON-like tree (maps, lists, scalars) with every string value passed through a
 * rewriter — map keys stay untouched. The copy is always fresh, so a caller may change it without
 * touching the entity it came from.
 */
final class BundleStrings {

    /** @param field where the value sits, e.g. {@code blocks[0].config.command} */
    @FunctionalInterface
    interface Rewriter {
        String rewrite(String value, String field);
    }

    static final Rewriter IDENTITY = (value, field) -> value;

    private BundleStrings() {
    }

    @SuppressWarnings("unchecked")
    static <T> T copy(T node, String field, Rewriter rewriter) {
        return (T) transform(node, field, rewriter);
    }

    private static Object transform(Object node, String field, Rewriter rewriter) {
        if (node instanceof String s) {
            return rewriter.rewrite(s, field);
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key),
                    transform(value, field.isEmpty() ? String.valueOf(key) : field + "." + key, rewriter)));
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                copy.add(transform(list.get(i), field + "[" + i + "]", rewriter));
            }
            return copy;
        }
        return node;
    }
}
