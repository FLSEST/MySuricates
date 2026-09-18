package com.automation.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Schreibweise der {@code {{ ... }}}-Platzhalter — eine Stelle, damit Laufzeit-Auflösung
 * ({@link TemplateResolver}) und statische Analysen (z. B. der BPMN-Datenfluss-Export) exakt
 * dieselben Vorkommen sehen.
 */
public final class PlaceholderSyntax {

    /**
     * {@code {{x}}} oder {@code {{ x }}} — genau die zwei Schreibweisen, die der Resolver seit jeher
     * unterstützt. Schlüssel dürfen Leerzeichen enthalten (Blocknamen wie "Build Report"); eine
     * asymmetrische Schreibweise ergibt einen Schlüssel mit führendem/abschließendem Leerzeichen,
     * der auf nichts passt und literal stehen bleibt.
     */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{( ?)([^{}]+?)\\1\\}\\}");

    private PlaceholderSyntax() {}

    /** Die Schlüssel aller Platzhalter eines Textes, in Vorkommensreihenfolge (mit Wiederholungen). */
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
