package com.automation.executor.compress;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regex matching with a deadline: Java regex has no timeout, so matching runs over a CharSequence that
 * aborts once the block's time budget is spent. Lines are matched on their first 10,000 chars only.
 */
public final class RegexGuard {

    static final int MAX_MATCH_CHARS = 10_000;

    private final long deadlineNanos;

    private RegexGuard(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static RegexGuard startingNow(Duration budget) {
        return new RegexGuard(System.nanoTime() + budget.toNanos());
    }

    public static Pattern compile(String regex, int flags, String where) {
        try {
            return Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            throw new CompressionException(where + ": ungültiger regulärer Ausdruck '" + regex + "' (" + e.getDescription() + ")");
        }
    }

    public boolean find(Pattern pattern, String line) {
        String head = head(line);
        try {
            return pattern.matcher(new DeadlineCharSequence(head, deadlineNanos)).find();
        } catch (StackOverflowError e) {
            throw tooDeep(line);
        }
    }

    public boolean matches(Pattern pattern, String line) {
        String head = head(line);
        try {
            return pattern.matcher(new DeadlineCharSequence(head, deadlineNanos)).matches();
        } catch (StackOverflowError e) {
            throw tooDeep(line);
        }
    }

    public boolean findAny(List<Pattern> patterns, String line) {
        for (Pattern p : patterns) {
            if (find(p, line)) return true;
        }
        return false;
    }

    public String replaceAll(Pattern pattern, String line, String replacement) {
        String head = head(line);
        String replaced;
        try {
            replaced = pattern.matcher(new DeadlineCharSequence(head, deadlineNanos)).replaceAll(replacement);
        } catch (StackOverflowError e) {
            throw tooDeep(line);
        }
        return line.length() > head.length() ? replaced + line.substring(head.length()) : replaced;
    }

    public void checkDeadline() {
        if (System.nanoTime() > deadlineNanos) throw timeout();
    }

    /** Index at or before {@code index} that does not split a surrogate pair. */
    public static int safeCut(String s, int index) {
        if (index > 0 && index < s.length() && Character.isHighSurrogate(s.charAt(index - 1))) return index - 1;
        return index;
    }

    private static String head(String line) {
        return line.length() > MAX_MATCH_CHARS ? line.substring(0, safeCut(line, MAX_MATCH_CHARS)) : line;
    }

    private static CompressionException tooDeep(String line) {
        return new CompressionException("Muster zu tief verschachtelt für Zeile mit " + CompressionNumbers.fmt(line.length()) + " Zeichen");
    }

    static CompressionException timeout() {
        return new CompressionException("Zeitlimit überschritten — regulärer Ausdruck zu aufwendig oder Eingabe zu groß");
    }

    /**
     * Checks the deadline every 1,024 {@code charAt} calls — not by index, so backtracking on short lines or
     * near a line end is caught too. Sub-sequences share the parent's call counter.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final String text;
        private final long deadline;
        private final int[] calls;

        DeadlineCharSequence(String text, long deadline) {
            this(text, deadline, new int[1]);
        }

        private DeadlineCharSequence(String text, long deadline, int[] calls) {
            this.text = text;
            this.deadline = deadline;
            this.calls = calls;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if ((++calls[0] & 0x3FF) == 0 && System.nanoTime() > deadline) throw timeout();
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(text.substring(start, end), deadline, calls);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
