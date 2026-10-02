package com.automation.executor.compress;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regex matching with a deadline: Java regex has no timeout, so matching runs over a CharSequence that
 * aborts once the block's time budget is spent. Lines are matched on their first 10,000 chars only.
 */
public final class RegexGuard {

    static final int MAX_MATCH_CHARS = 10_000;
    /** Cap of the compile cache; reaching it clears the cache instead of growing without bound. */
    static final int MAX_CACHED_PATTERNS = 500;

    /** Compiled patterns by flags + regex: a stage compiles the same regex in validate() and in apply(). */
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private final long deadlineNanos;
    /** Shared by every CharSequence this guard hands to a matcher, so short lines add up to a check too. */
    private final int[] calls = new int[1];

    private RegexGuard(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static RegexGuard startingNow(Duration budget) {
        return new RegexGuard(System.nanoTime() + budget.toNanos());
    }

    /** Compiles — or returns the already compiled {@link Pattern}; patterns are immutable and thread-safe. */
    public static Pattern compile(String regex, int flags, String where) {
        String key = flags + "|" + regex;   // flags are digits, so the first | always delimits them
        Pattern cached = COMPILED.get(key);
        if (cached != null) return cached;
        try {
            Pattern compiled = Pattern.compile(regex, flags);
            if (COMPILED.size() >= MAX_CACHED_PATTERNS) COMPILED.clear();
            COMPILED.put(key, compiled);
            return compiled;
        } catch (PatternSyntaxException e) {
            throw new CompressionException(where + ": invalid regular expression '" + regex + "' (" + e.getDescription() + ")");
        }
    }

    public boolean find(Pattern pattern, String line) {
        String head = head(line);
        try {
            return pattern.matcher(sequence(head)).find();
        } catch (StackOverflowError e) {
            throw tooDeep(line);
        }
    }

    public boolean matches(Pattern pattern, String line) {
        String head = head(line);
        try {
            return pattern.matcher(sequence(head)).matches();
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
            replaced = pattern.matcher(sequence(head)).replaceAll(replacement);
        } catch (StackOverflowError e) {
            throw tooDeep(line);
        }
        return line.length() > head.length() ? replaced + line.substring(head.length()) : replaced;
    }

    /** Counts the call itself too, so even lines that never reach {@code charAt} (empty ones) run into a check. */
    private DeadlineCharSequence sequence(String text) {
        if ((++calls[0] & 0x3FF) == 0) checkDeadline();
        return new DeadlineCharSequence(text, deadlineNanos, calls);
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
        return new CompressionException("Pattern nested too deeply for a line of " + CompressionNumbers.fmt(line.length()) + " chars");
    }

    static CompressionException timeout() {
        return new CompressionException("Time limit exceeded — regular expression too costly or input too large");
    }

    /**
     * Checks the deadline every 1,024 {@code charAt} calls — not by index, so backtracking on short lines or
     * near a line end is caught too. Sub-sequences and every later line of the same guard share the call
     * counter, so an input of very many short lines also reaches a check inside the stage, not only between
     * stages (each line alone would never get to 1,024 calls).
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final String text;
        private final long deadline;
        private final int[] calls;

        DeadlineCharSequence(String text, long deadline, int[] calls) {
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
