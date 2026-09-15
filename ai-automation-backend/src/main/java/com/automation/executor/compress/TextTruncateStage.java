package com.automation.executor.compress;

import java.util.Map;

/** text.truncate — cuts to max_chars (notice included), preferring line boundaries; head_tail keeps 25 % / 75 %. */
public final class TextTruncateStage implements CompressionStage {

    public enum Keep { HEAD, TAIL, HEAD_TAIL }

    @Override
    public String op() {
        return "text.truncate";
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
        StageConfig.intValue(config, "max_chars", null, 100, 1_000_000, StageConfig.where(op()));
        keep(config);
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        int max = StageConfig.intValue(config, "max_chars", null, 100, 1_000_000, StageConfig.where(op()));
        String out = truncate(input, max, keep(config));
        return new StageResult(out, out.length() < input.length() ? CompressionNumbers.chars(input.length() - out.length()) : null);
    }

    private static Keep keep(Map<String, Object> config) {
        return switch (StageConfig.string(config, "keep", "head_tail", "Stufe text.truncate")) {
            case "head" -> Keep.HEAD;
            case "tail" -> Keep.TAIL;
            case "head_tail" -> Keep.HEAD_TAIL;
            default -> throw new CompressionException("Stufe text.truncate: 'keep' muss head, tail oder head_tail sein");
        };
    }

    /** Result length is at most {@code budget} (for budgets above the notice length), notice included. */
    public static String truncate(String text, int budget, Keep keep) {
        if (text.length() <= budget) return text;
        int available = Math.max(0, budget - notice(text.length()).length());
        String head = "";
        String tail = "";
        switch (keep) {
            case HEAD -> head = headPart(text, available);
            case TAIL -> tail = tailPart(text, available);
            case HEAD_TAIL -> {
                int headBudget = available / 4;
                head = headPart(text, headBudget);
                tail = tailPart(text, available - headBudget);
            }
        }
        return head + notice(text.length() - head.length() - tail.length()) + tail;
    }

    static String notice(long omitted) {
        return "\n[… " + CompressionNumbers.fmt(omitted) + " Zeichen ausgelassen …]\n";
    }

    private static String headPart(String text, int max) {
        if (max <= 0) return "";
        int cut = RegexGuard.safeCut(text, Math.min(max, text.length()));
        int newline = text.lastIndexOf('\n', cut - 1);
        if (newline >= cut / 2) cut = newline + 1;
        return text.substring(0, cut);
    }

    private static String tailPart(String text, int max) {
        if (max <= 0) return "";
        int start = text.length() - Math.min(max, text.length());
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        int newline = text.indexOf('\n', start);
        if (newline >= 0 && newline < start + max / 2) start = newline + 1;
        return text.substring(start);
    }
}
