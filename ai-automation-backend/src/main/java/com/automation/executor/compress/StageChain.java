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
            throw new CompressionException("'stages' muss eine nicht-leere Liste von Stufen sein");
        }
        List<Step> steps = new ArrayList<>();
        StageKind current = StageKind.ANY;
        for (int i = 0; i < list.size(); i++) {
            int position = i + 1;
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw new CompressionException("Stufe " + position + ": muss ein Objekt mit 'op' sein");
            }
            Map<String, Object> config = (Map<String, Object>) map;
            String op = config.get("op") instanceof String s ? s : null;
            CompressionStage stage = CompressionStages.find(op).orElseThrow(() -> new CompressionException(
                    "Stufe " + position + ": unbekannte Operation '" + op + "' (erlaubt: "
                            + String.join(", ", CompressionStages.ops()) + ")"));
            if (stage.inputKind() == StageKind.JSON && current == StageKind.TEXT) {
                throw new CompressionException("Stufe " + position + " (" + op + "): erwartet JSON, aber eine vorherige Stufe liefert Text");
            }
            stage.validate(config);
            steps.add(new Step(stage, config));
            current = stage.outputKind();
        }
        return steps;
    }

    public static Outcome run(List<Step> steps, String input, RegexGuard guard) {
        String text = input;
        List<Map<String, Object>> metadata = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Step step : steps) {
            guard.checkDeadline();
            int inChars = text.length();
            StageResult result = step.stage().apply(text, step.config(), guard);
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
}
