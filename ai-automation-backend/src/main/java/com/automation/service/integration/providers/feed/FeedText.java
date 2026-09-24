package com.automation.service.integration.providers.feed;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns feed HTML into short plain text: drops tags, script/style/comment blocks, decodes the common
 * entities and collapses whitespace. A linear scanner instead of regexes, so hostile markup (many
 * unclosed {@code <script}) cannot make it backtrack.
 */
final class FeedText {

    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("hellip", "…"), Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("bdquo", "„"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"),
            Map.entry("auml", "ä"), Map.entry("ouml", "ö"), Map.entry("uuml", "ü"), Map.entry("Auml", "Ä"),
            Map.entry("Ouml", "Ö"), Map.entry("Uuml", "Ü"), Map.entry("szlig", "ß"), Map.entry("euro", "€"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("middot", "·"));

    private static final Set<String> INLINE_TAGS = Set.of(
            "a", "abbr", "b", "cite", "code", "em", "font", "i", "kbd", "mark", "q", "s", "small",
            "span", "strong", "sub", "sup", "time", "u");

    private FeedText() {}

    /** Plain text of {@code html}, cut to {@code maxLength} characters (with "…"); {@code null} stays null. */
    static String plain(String html, int maxLength) {
        if (html == null) return null;
        String text = collapse(decodeEntities(stripTags(html)));
        return truncate(text, maxLength);
    }

    static String stripTags(String html) {
        StringBuilder out = new StringBuilder(html.length());
        String lower = html.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            if (lower.startsWith("<!--", i)) {
                i = skipPast(lower, "-->", i + 4);
            } else if (isBlockStart(lower, i, "script") || isBlockStart(lower, i, "style")) {
                String name = lower.startsWith("<script", i) ? "script" : "style";
                i = skipPast(lower, "</" + name, i + 1);
                i = skipPast(lower, ">", i);
            } else {
                int end = html.indexOf('>', i + 1);
                if (end < 0) { // a lone '<' is text, not a tag
                    out.append(c);
                    i++;
                    continue;
                }
                if (!isInline(lower, i + 1, end)) out.append(' ');
                i = end + 1;
            }
        }
        return out.toString();
    }

    /** Inline tags (b, em, a, …) join text without a gap; block tags (p, br, li, …) separate words. */
    private static boolean isInline(String lower, int from, int end) {
        int start = from < end && lower.charAt(from) == '/' ? from + 1 : from;
        int stop = start;
        while (stop < end && Character.isLetterOrDigit(lower.charAt(stop))) stop++;
        return INLINE_TAGS.contains(lower.substring(start, stop));
    }

    private static boolean isBlockStart(String lower, int i, String name) {
        int after = i + 1 + name.length();
        if (!lower.startsWith("<" + name, i)) return false;
        if (after >= lower.length()) return true;
        char next = lower.charAt(after);
        return next == '>' || next == '/' || Character.isWhitespace(next);
    }

    private static int skipPast(String lower, String marker, int from) {
        int idx = lower.indexOf(marker, from);
        return idx < 0 ? lower.length() : idx + marker.length();
    }

    static String decodeEntities(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int semi = c == '&' ? text.indexOf(';', i + 1) : -1;
            if (semi < 0 || semi - i > 10) {
                out.append(c);
                i++;
                continue;
            }
            String decoded = decodeEntity(text.substring(i + 1, semi));
            if (decoded == null) {
                out.append(c);
                i++;
            } else {
                out.append(decoded);
                i = semi + 1;
            }
        }
        return out.toString();
    }

    private static String decodeEntity(String name) {
        if (name.startsWith("#")) {
            try {
                boolean hex = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
                int cp = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
                return Character.isValidCodePoint(cp) && cp != 0 ? new String(Character.toChars(cp)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ENTITIES.get(name);
    }

    static String collapse(String text) {
        return text.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    static String truncate(String text, int maxLength) {
        if (maxLength <= 0 || text.length() <= maxLength) return text;
        int cut = text.lastIndexOf(' ', maxLength - 1);
        if (cut < maxLength / 2) cut = maxLength - 1;
        return text.substring(0, cut).stripTrailing() + "…";
    }
}
