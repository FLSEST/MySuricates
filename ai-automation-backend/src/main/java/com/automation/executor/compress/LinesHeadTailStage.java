package com.automation.executor.compress;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** lines.head / lines.tail — first or last n lines with a notice about the omitted ones. */
final class LinesHeadTailStage implements CompressionStage {

    private final boolean head;

    private LinesHeadTailStage(boolean head) {
        this.head = head;
    }

    static LinesHeadTailStage head() {
        return new LinesHeadTailStage(true);
    }

    static LinesHeadTailStage tail() {
        return new LinesHeadTailStage(false);
    }

    @Override
    public String op() {
        return head ? "lines.head" : "lines.tail";
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
        StageConfig.intValue(config, "lines", null, 1, 1_000_000, StageConfig.where(op()));
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        int keep = StageConfig.intValue(config, "lines", null, 1, 1_000_000, StageConfig.where(op()));
        List<String> lines = Arrays.asList(input.split("\n", -1));
        if (lines.size() <= keep) return new StageResult(input, null);
        int omitted = lines.size() - keep;
        String notice = "[… " + CompressionNumbers.fmt(omitted) + " Zeilen ausgelassen …]";
        String body = String.join("\n", head ? lines.subList(0, keep) : lines.subList(lines.size() - keep, lines.size()));
        return new StageResult(head ? body + "\n" + notice : notice + "\n" + body, CompressionNumbers.lines(omitted));
    }
}
