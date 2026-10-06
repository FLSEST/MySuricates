package com.automation.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The spelling of the {@code {{ ... }}} placeholders - one place, so that runtime resolution
 * ({@link TemplateResolver}) and static analyses (e.g. the BPMN data-flow export) see exactly
 * the same occurrences.
 */
public final class PlaceholderSyntax {

    /**
     * {@code {{x}}} or {@code {{ x }}} - exactly the two spellings the resolver has always
     * supported. Keys may contain spaces (block names like "Build Report"); an
     * asymmetric spelling yields a key with leading/trailing space
     * that matches nothing and stays literal.
     */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{( ?)([^{}]+?)\\1\\}\\}");

    private PlaceholderSyntax() {}

    /** The keys of all placeholders of a text, in order of occurrence (with repetitions). */
    public static List<String> keysIn(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(2));
        }
        return keys;
    }
}
