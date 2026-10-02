package com.automation.executor.compress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses and validates a block's stage list completely before any stage runs, then runs it in order. */
public final class StageChain {

    public record Step(CompressionStage stage, Map<String, Object> config) {
    }

    public record Outcome(String text, List<Map<String, Object>> stageMetadata, List<String> notes) {
    }

    private StageChain() {
    }

    @SuppressWarnings("unchecked")
    public static List<Step> parse(Object rawStages) {
        if (!(rawStages instanceof List<?> list) || list.isEmpty()) {
            throw new CompressionException("'stages' must be a non-empty list of stages");
        }
        List<Step> steps = new ArrayList<>();
        StageKind current = StageKind.ANY;
        for (int i = 0; i < list.size(); i++) {
            int position = i + 1;
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw new CompressionException("Stage " + position + ": must be an object with 'op'");
            }
            Map<String, Object> config = (Map<String, Object>) map;
            String op = config.get("op") instanceof String s ? s : null;
            CompressionStage stage = CompressionStages.find(op).orElseThrow(() -> new CompressionException(
                    "Stage " + position + ": unknown operation '" + op + "' (allowed: "
                            + String.join(", ", CompressionStages.ops()) + ")"));
            if (stage.inputKind() == StageKind.JSON && current == StageKind.TEXT) {
                throw new CompressionException("Stage " + position + " (" + op + "): expects JSON, but an earlier stage yields text");
            }
            try {
                stage.validate(config);
            } catch (CompressionException e) {
                throw namePositionAndOp(position, op, e);
            }
            steps.add(new Step(stage, config));
            current = stage.outputKind();
        }
        return steps;
    }

    public static Outcome run(List<Step> steps, String input, RegexGuard guard) {
        String text = input;
        List<Map<String, Object>> metadata = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            guard.checkDeadline();
            int inChars = text.length();
            StageResult result = apply(step, i + 1, text, guard);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", step.stage().op());
            m.put("inChars", inChars);
            m.put("outChars", result.output().length());
            if (result.note() != null) m.put("note", result.note());
            metadata.add(m);
            notes.add(result.note() == null ? step.stage().op() : step.stage().op() + " (" + result.note() + ")");
            text = result.output();
        }
        return new Outcome(text, metadata, notes);
    }

    /**
     * Runs one stage; its errors name the stage position and op exactly once ("Stage 2 (lines.filter): …").
     * A message the stage already prefixed with its own "Stage &lt;op&gt;: " loses that prefix — the position
     * form contains the op already.
     */
    private static StageResult apply(Step step, int position, String text, RegexGuard guard) {
        try {
            return step.stage().apply(text, step.config(), guard);
        } catch (CompressionException e) {
            throw namePositionAndOp(position, step.stage().op(), e);
        }
    }

    /**
     * Rewrites a stage's error to name its position and op exactly once ("Stage 2 (lines.filter):
     * …"), for both configuration errors ({@link #parse}) and run-time errors ({@link #apply}). A
     * message the stage already prefixed with its own "Stage &lt;op&gt;: " ({@link
     * StageConfig#where}) loses that prefix — the position form contains the op already. Already
     * positioned (e.g. re-thrown by a caller) it passes through unchanged.
     */
    private static CompressionException namePositionAndOp(int position, String op, CompressionException e) {
        String prefix = "Stage " + position;
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith(prefix)) return e;
        String ownPrefix = StageConfig.where(op) + ": ";
        if (message.startsWith(ownPrefix)) message = message.substring(ownPrefix.length());
        return new CompressionException(prefix + " (" + op + "): " + message);
    }
}
