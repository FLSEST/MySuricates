package com.automation.executor.compress;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * lines.filter — keeps lines matching an include pattern (plus before/after context) and drops exclude
 * matches. With include, gaps are marked "[… N lines …]"; exclude-only removes silently.
 */
final class LinesFilterStage implements CompressionStage {

    @Override
    public String op() {
        return "lines.filter";
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
        String where = StageConfig.where(op());
        List<String> include = StageConfig.strings(config, "include", where);
        List<String> exclude = StageConfig.strings(config, "exclude", where);
        if (include.isEmpty() && exclude.isEmpty()) {
            throw new CompressionException(where + ": 'include' or 'exclude' needs at least one pattern");
        }
        compileAll(include, where);
        compileAll(exclude, where);
        StageConfig.intValue(config, "before", 0, 0, 1000, where);
        StageConfig.intValue(config, "after", 0, 0, 1000, where);
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        String where = StageConfig.where(op());
        List<Pattern> include = compileAll(StageConfig.strings(config, "include", where), where);
        List<Pattern> exclude = compileAll(StageConfig.strings(config, "exclude", where), where);
        int before = StageConfig.intValue(config, "before", 0, 0, 1000, where);
        int after = StageConfig.intValue(config, "after", 0, 0, 1000, where);

        String[] lines = input.split("\n", -1);
        int n = lines.length;
        boolean[] excluded = new boolean[n];
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            excluded[i] = guard.findAny(exclude, lines[i]);
            keep[i] = !excluded[i] && (include.isEmpty() || guard.findAny(include, lines[i]));
        }
        if (!include.isEmpty() && (before > 0 || after > 0)) {
            boolean[] hit = keep.clone();
            for (int i = 0; i < n; i++) {
                if (!hit[i]) continue;
                for (int j = Math.max(0, i - before); j <= Math.min(n - 1, i + after); j++) {
                    if (!excluded[j]) keep[j] = true;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        int kept = 0;
        int gap = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) {
                gap++;
                continue;
            }
            if (gap > 0 && !include.isEmpty()) appendLine(out, "[… " + CompressionNumbers.fmt(gap) + " lines …]");
            gap = 0;
            appendLine(out, lines[i]);
            kept++;
        }
        if (gap > 0 && !include.isEmpty() && kept > 0) appendLine(out, "[… " + CompressionNumbers.fmt(gap) + " lines …]");
        return new StageResult(out.toString(), kept == 0 ? "no matches" : CompressionNumbers.lines(n - kept));
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(line);
    }

    private static List<Pattern> compileAll(List<String> regexes, String where) {
        return regexes.stream().map(r -> RegexGuard.compile(r, 0, where)).toList();
    }
}
