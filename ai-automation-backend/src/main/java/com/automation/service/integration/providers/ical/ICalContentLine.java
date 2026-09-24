package com.automation.service.integration.providers.ical;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One unfolded iCalendar content line: {@code NAME;PARAM=value;PARAM="quoted:value":VALUE}
 * (RFC 5545 §3.1). Parameter names are upper-cased, quoted parameter values unquoted; the value is
 * kept raw (text unescaping is the caller's business, it depends on the property type).
 */
public record ICalContentLine(String name, Map<String, String> params, String value) {

    public String param(String key) {
        return params.get(key.toUpperCase(Locale.ROOT));
    }

    /** Parses one line; {@code null} for a line without a name/value separator (malformed). */
    public static ICalContentLine parse(String line) {
        if (line == null || line.isEmpty()) return null;
        boolean quoted = false;
        int colon = -1;
        List<Integer> semicolons = new ArrayList<>();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && c == ';') semicolons.add(i);
            else if (!quoted && c == ':') {
                colon = i;
                break;
            }
        }
        if (colon <= 0) return null;
        int nameEnd = semicolons.isEmpty() ? colon : semicolons.get(0);
        String name = line.substring(0, nameEnd).trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) return null;

        Map<String, String> params = new LinkedHashMap<>();
        for (int s = 0; s < semicolons.size(); s++) {
            int from = semicolons.get(s) + 1;
            int to = s + 1 < semicolons.size() ? semicolons.get(s + 1) : colon;
            String param = line.substring(from, to);
            int eq = param.indexOf('=');
            if (eq <= 0) continue;
            String value = param.substring(eq + 1);
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            params.put(param.substring(0, eq).trim().toUpperCase(Locale.ROOT), value);
        }
        return new ICalContentLine(name, params, line.substring(colon + 1));
    }

    /** Unescapes a TEXT value (RFC 5545 §3.3.11): {@code \n \N \, \; \\}. */
    public static String unescapeText(String value) {
        if (value == null || value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n', 'N' -> out.append('\n');
                    case ',', ';', '\\', ':', '"' -> out.append(next);
                    default -> out.append('\\').append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Escapes a TEXT value for writing; CR/LF become {@code \n}. */
    public static String escapeText(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\r", "\\n")
                .replace("\n", "\\n");
    }
}
