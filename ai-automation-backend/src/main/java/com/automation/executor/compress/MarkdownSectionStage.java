package com.automation.executor.compress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * markdown.section — keeps sections whose heading matches (case-insensitive), up to the next heading of the
 * same or a higher level. Headings inside ``` / ~~~ fences do not count. No match is a note, not an error.
 */
final class MarkdownSectionStage implements CompressionStage {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    @Override
    public String op() {
        return "markdown.section";
    }

    @Override
    public StageKind inputKind() {
        return StageKind.ANY;
    }

    @Override
    public StageKind outputKind() {
        return StageKind.TEXT;
    }

    @Override
    public void validate(Map<String, Object> config) {
        headings(config);
        level(config);
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        List<Pattern> headings = headings(config);
        Integer level = level(config);
        List<String> out = new ArrayList<>();
        int keepLevel = 0;
        int total = 0;
        int matched = 0;
        boolean fence = false;
        for (String line : input.split("\n", -1)) {
            if (!fence && line.startsWith("#") && line.length() <= 1000 && guard.matches(HEADING, line)) {
                int lvl = headingLevel(line);
                if (keepLevel > 0 && lvl <= keepLevel) keepLevel = 0;
                if (level == null || lvl == level) {
                    total++;
                    if (keepLevel == 0 && guard.findAny(headings, headingTitle(line, lvl))) {
                        keepLevel = lvl;
                        matched++;
                        if (!out.isEmpty()) out.add("");
                    }
                }
            }
            if (keepLevel > 0) out.add(line);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) fence = !fence;
        }
        String note = matched == 0 ? "kein passender Abschnitt"
                : CompressionNumbers.fmt(matched) + " von " + CompressionNumbers.fmt(total) + " Abschnitten";
        return new StageResult(String.join("\n", out), note);
    }

    /** Number of leading '#' — only called after HEADING matched, so 1..6. */
    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        return level;
    }

    /**
     * HEADING's title group without a second, unguarded regex run: text after the '#' run and its whitespace,
     * minus trailing whitespace, closing '#'s and the whitespace before them — the same result as the lazy group.
     */
    private static String headingTitle(String line, int level) {
        int start = level;
        while (start < line.length() && isRegexSpace(line.charAt(start))) start++;
        int end = line.length();
        while (end > start && isRegexSpace(line.charAt(end - 1))) end--;
        while (end > start && line.charAt(end - 1) == '#') end--;
        while (end > start && isRegexSpace(line.charAt(end - 1))) end--;
        return line.substring(start, end);
    }

    /** Java regex {@code \s} without UNICODE_CHARACTER_CLASS: {@code [ \t\n\x0B\f\r]}. */
    private static boolean isRegexSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    private List<Pattern> headings(Map<String, Object> config) {
        String where = StageConfig.where(op());
        List<String> raw = StageConfig.strings(config, "headings", where);
        if (raw.isEmpty()) throw new CompressionException(where + ": 'headings' braucht mindestens einen Eintrag");
        return raw.stream().map(r -> RegexGuard.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, where)).toList();
    }

    private Integer level(Map<String, Object> config) {
        return config.containsKey("level") ? StageConfig.intValue(config, "level", null, 1, 6, StageConfig.where(op())) : null;
    }
}
