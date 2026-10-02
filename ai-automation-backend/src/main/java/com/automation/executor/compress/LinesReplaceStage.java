package com.automation.executor.compress;

import java.util.Map;
import java.util.regex.Pattern;

/** lines.replace — regex replacement per line (Java replacement syntax, $1 for groups), e.g. strip timestamps. */
final class LinesReplaceStage implements CompressionStage {

    @Override
    public String op() {
        return "lines.replace";
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
        RegexGuard.compile(StageConfig.string(config, "pattern", null, where), 0, where);
        StageConfig.string(config, "replacement", "", where);
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        String where = StageConfig.where(op());
        Pattern pattern = RegexGuard.compile(StageConfig.string(config, "pattern", null, where), 0, where);
        String replacement = StageConfig.string(config, "replacement", "", where);
        String[] lines = input.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int changed = 0;
        for (int i = 0; i < lines.length; i++) {
            String replaced;
            try {
                replaced = guard.replaceAll(pattern, lines[i], replacement);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new CompressionException(where + ": invalid replacement '" + replacement + "' (" + e.getMessage() + ")");
            }
            if (!replaced.equals(lines[i])) changed++;
            if (i > 0) out.append('\n');
            out.append(replaced);
        }
        return new StageResult(out.toString(), CompressionNumbers.fmt(changed) + " lines changed");
    }
}
