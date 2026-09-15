package com.automation.executor.compress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** lines.dedupe — drops repeated lines (global or consecutive); with count as "37× line" at the first occurrence. */
final class LinesDedupeStage implements CompressionStage {

    @Override
    public String op() {
        return "lines.dedupe";
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
        // count / consecutive_only are optional booleans
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        boolean count = StageConfig.flag(config, "count");
        boolean consecutive = StageConfig.flag(config, "consecutive_only");
        String[] lines = input.split("\n", -1);
        List<String> out = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        if (consecutive) {
            for (String line : lines) {
                if (!out.isEmpty() && !line.isBlank() && out.getLast().equals(line)) {
                    counts.set(counts.size() - 1, counts.getLast() + 1);
                } else {
                    out.add(line);
                    counts.add(1);
                }
            }
        } else {
            Map<String, Integer> firstIndex = new HashMap<>();
            for (String line : lines) {
                Integer at = line.isBlank() ? null : firstIndex.get(line);
                if (at != null) {
                    counts.set(at, counts.get(at) + 1);
                    continue;
                }
                if (!line.isBlank()) firstIndex.put(line, out.size());
                out.add(line);
                counts.add(1);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('\n');
            if (count && counts.get(i) > 1) sb.append(counts.get(i)).append("× ");
            sb.append(out.get(i));
        }
        return new StageResult(sb.toString(), CompressionNumbers.lines(lines.length - out.size()));
    }
}
