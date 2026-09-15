package com.automation.executor.compress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** text.normalize — ANSI sequences, CR and trailing whitespace removed; runs of blank lines collapsed to one. */
final class TextNormalizeStage implements CompressionStage {

    private static final Pattern ANSI = Pattern.compile(
            "\\[[0-?]*[ -/]*[@-~]|\\][^]*(?:|\\\\)|[@-Z\\\\-_]");

    @Override
    public String op() {
        return "text.normalize";
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
        // no options
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        String text = ANSI.matcher(input).replaceAll("").replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        boolean previousBlank = true; // also drops leading blank lines
        for (String line : text.split("\n", -1)) {
            String stripped = line.stripTrailing();
            boolean blank = stripped.isEmpty();
            if (blank && previousBlank) continue;
            out.add(stripped);
            previousBlank = blank;
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) out.removeLast();
        String result = String.join("\n", out);
        return new StageResult(result, CompressionNumbers.chars(input.length() - result.length()));
    }
}
