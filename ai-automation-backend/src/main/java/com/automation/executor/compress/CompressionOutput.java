package com.automation.executor.compress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output of both compression blocks: a header line from stored step facts, a detail line (stages or AI),
 * a blank line, then the content. The total never exceeds max_chars; content is cut head/tail when needed.
 */
public final class CompressionOutput {

    public static final int MIN_BODY_CHARS = 200;
    /** Shown instead of empty content (spec § 5.1); not counted as content characters. */
    static final String EMPTY_BODY = "(leer)";

    public record Assembled(String text, int inChars, int outChars, boolean capped) {
    }

    private CompressionOutput() {
    }

    public static Assembled assemble(CompressionSources.Resolved src, String detailLine, String body, int maxChars) {
        int in = src.text().length();
        String header = header(src, in, body.length());
        String full = header + "\n" + detailLine + "\n\n" + (body.isEmpty() ? EMPTY_BODY : body);
        if (full.length() <= maxChars) return new Assembled(full, in, body.length(), false);

        // Reserve for the worst case: header and cap note computed with the uncut length (digits only shrink).
        String worstDetail = detailLine + " · Obergrenze (" + CompressionNumbers.chars(body.length()) + ")";
        int budget = maxChars - header.length() - 1 - worstDetail.length() - 2;
        if (budget < MIN_BODY_CHARS) {
            throw new CompressionException("'max_chars' = " + CompressionNumbers.fmt(maxChars)
                    + " reicht nicht für Kopfzeilen und mindestens " + MIN_BODY_CHARS + " Zeichen Inhalt");
        }
        String cut = TextTruncateStage.truncate(body, budget, TextTruncateStage.Keep.HEAD_TAIL);
        String finalDetail = detailLine + " · Obergrenze (" + CompressionNumbers.chars(body.length() - cut.length()) + ")";
        return new Assembled(header(src, in, cut.length()) + "\n" + finalDetail + "\n\n" + cut, in, cut.length(), true);
    }

    /**
     * Content of an earlier compression output (everything after the first blank line). The {@link #EMPTY_BODY}
     * marker counts as empty content — chaining a compression over it must not compress the marker text.
     */
    public static String bodyOf(String derivedOutput) {
        int split = derivedOutput.indexOf("\n\n");
        String body = split < 0 ? "" : derivedOutput.substring(split + 2);
        return EMPTY_BODY.equals(body) ? "" : body;
    }

    public static Map<String, Object> metadata(CompressionSources.Resolved src, Assembled out, int maxChars,
                                               List<Map<String, Object>> stages) {
        Map<String, Object> compression = new LinkedHashMap<>();
        compression.put("source", src.origin().toMetadata());
        compression.put("label", src.label());
        compression.put("inChars", out.inChars());
        compression.put("outChars", out.outChars());
        compression.put("maxChars", maxChars);
        compression.put("capped", out.capped());
        if (stages != null) compression.put("stages", stages);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("derivedOutput", true);
        metadata.put("compression", compression);
        return metadata;
    }

    static String header(CompressionSources.Resolved src, int in, int out) {
        var o = src.origin();
        StringBuilder sb = new StringBuilder("## ").append(src.label())
                .append(" — ").append(o.status() != null ? o.status().name() : "UNBEKANNT");
        if (o.exitCode() != null) sb.append(" · exit ").append(o.exitCode());
        if (o.durationMs() != null) sb.append(" · ").append(Math.round(o.durationMs() / 1000.0)).append(" s");
        sb.append(" · ").append(CompressionNumbers.fmt(in)).append(" → ").append(CompressionNumbers.fmt(out)).append(" Zeichen");
        return sb.toString();
    }
}
