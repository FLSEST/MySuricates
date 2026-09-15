package com.automation.executor.compress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All deterministic stages by op name, in documentation order. */
public final class CompressionStages {

    private static final Map<String, CompressionStage> STAGES = new LinkedHashMap<>();

    static {
        register(new TextNormalizeStage());
        register(new TextTruncateStage());
        register(new LinesFilterStage());
        register(new LinesReplaceStage());
        register(new LinesDedupeStage());
        register(LinesHeadTailStage.head());
        register(LinesHeadTailStage.tail());
    }

    private CompressionStages() {
    }

    private static void register(CompressionStage stage) {
        STAGES.put(stage.op(), stage);
    }

    public static Optional<CompressionStage> find(String op) {
        return Optional.ofNullable(op == null ? null : STAGES.get(op));
    }

    public static List<String> ops() {
        return List.copyOf(STAGES.keySet());
    }
}
