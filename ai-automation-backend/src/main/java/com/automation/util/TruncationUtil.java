package com.automation.util;

/**
 * Deterministic truncation helpers for surfacing long step outputs to the AI assistant.
 * Errors usually sit at the END of an output, so these helpers preserve the tail —
 * unlike naive head-first substring truncation.
 */
public final class TruncationUtil {

    private TruncationUtil() {}

    /**
     * Keeps the first {@code head} and last {@code tail} characters with a skip
     * marker in between. Strings within budget are returned unchanged (no marker).
     */
    public static String smart(String s, int head, int tail) {
        if (s == null) return "";
        int len = s.length();
        if (len <= head + tail) return s;
        int skipped = len - head - tail;
        return s.substring(0, head)
                + "\n[... skipped " + skipped + " of " + len + " characters ...]\n"
                + s.substring(len - tail);
    }

    /**
     * Returns a slice of up to {@code max} characters starting at {@code offset},
     * framed by position markers so the AI can page through long outputs.
     */
    public static String window(String s, int offset, int max) {
        if (s == null) return "";
        int len = s.length();
        int from = Math.max(0, offset);
        if (from >= len && len > 0) {
            return "[offset " + offset + " is beyond output length " + len + "]";
        }
        int to = Math.min(from + max, len);
        StringBuilder sb = new StringBuilder();
        sb.append("[showing characters ").append(from).append("-").append(to)
                .append(" of ").append(len).append("]\n")
                .append(s, from, to);
        if (to < len) {
            sb.append("\n[... ").append(len - to)
                    .append(" more characters. Continue with ?offset=").append(to).append(" ...]");
        }
        return sb.toString();
    }

    /**
     * Single-line tail for compact list entries: keeps the last {@code max}
     * characters prefixed with an ellipsis — no multi-line skip marker.
     */
    public static String tailInline(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "…" + s.substring(s.length() - max);
    }

    /**
     * Keeps the last {@code max} characters with a leading skip marker.
     * Strings within budget are returned unchanged.
     */
    public static String tail(String s, int max) {
        if (s == null) return "";
        int len = s.length();
        if (len <= max) return s;
        return "[... skipped " + (len - max) + " of " + len + " characters ...]\n"
                + s.substring(len - max);
    }
}
