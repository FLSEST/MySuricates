package com.automation.service.export.bpmn;

import java.util.Locale;

final class BpmnNames {

    private static final int MAX_NAME = 60;

    private BpmnNames() {
    }

    /** Task-Name: block.name falls gesetzt, sonst Typ-Label + erste Command-Zeile (max. 60 Zeichen). */
    static String taskName(String blockName, String typeLabel, String command) {
        if (blockName != null && !blockName.isBlank()) {
            return blockName.strip();
        }
        String firstLine = command == null ? "" : command.strip().lines().findFirst().orElse("").strip();
        if (firstLine.isEmpty()) {
            return typeLabel;
        }
        if (firstLine.length() > MAX_NAME) {
            firstLine = firstLine.substring(0, MAX_NAME - 3) + "...";
        }
        return typeLabel + ": " + firstLine;
    }

    /** Stabile, sprechende XML-ID: base + Slug aus dem Namen ([a-z0-9_], max. 30 Slug-Zeichen). */
    static String elementId(String base, String name) {
        if (name == null || name.isBlank()) {
            return base;
        }
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.length() > 30) {
            slug = slug.substring(0, 30).replaceAll("_+$", "");
        }
        return slug.isEmpty() ? base : base + "_" + slug;
    }
}
