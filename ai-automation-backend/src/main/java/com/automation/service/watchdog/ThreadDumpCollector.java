package com.automation.service.watchdog;

import com.sun.management.HotSpotDiagnosticMXBean;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtered thread dump that <em>includes virtual threads</em>.
 *
 * <p>{@link Thread#getAllStackTraces()} only knows platform threads. Workflow runs execute on
 * {@code virtual-*} threads, so a dump based on it showed the thread <em>waiting</em> for the
 * single Hikari connection but never the one <em>holding</em> it (Audit-TODO 6aa3cc39).
 * {@link HotSpotDiagnosticMXBean#dumpThreads} (JDK 21, no extra dependency) writes every thread
 * — virtual ones included — to a file in {@code TEXT_PLAIN} format; this class parses that file,
 * keeps only threads with frames in our code / Hibernate / SQLite / Hikari, puts likely
 * connection holders first and caps the output. Falls back to {@code getAllStackTraces()} when the
 * HotSpot bean is unavailable or fails.
 */
@Slf4j
public class ThreadDumpCollector {

    static final int DEFAULT_MAX_THREADS = 20;
    static final int DEFAULT_MAX_FRAMES = 30;

    /** {@code #<id> "<name>"} optionally followed by {@code virtual} — one header per thread. */
    private static final Pattern HEADER = Pattern.compile("^#(\\d+) \"(.*)\"( virtual)?\\s*$");

    private static final List<String> INTERESTING = List.of(
            "com.automation.", "org.hibernate.", "org.sqlite.", "com.zaxxer.hikari.");

    /** Frames that indicate a thread is on the connection (as opposed to queued for it). */
    private static final List<String> HOLDER_HINTS = List.of(
            "org.sqlite.", "com.zaxxer.hikari.pool.Proxy", "com.zaxxer.hikari.pool.HikariProxy");

    private static final String WAITER_HINT = "com.zaxxer.hikari.pool.HikariPool.getConnection";

    public record ThreadStack(long id, String name, boolean virtual, List<String> frames) {

        boolean isInteresting() {
            return frames.stream().anyMatch(f -> INTERESTING.stream().anyMatch(f::contains));
        }

        boolean isLikelyHolder() {
            return frames.stream().anyMatch(f -> HOLDER_HINTS.stream().anyMatch(f::contains));
        }

        boolean isWaiter() {
            return frames.stream().anyMatch(f -> f.contains(WAITER_HINT));
        }
    }

    public String collect() {
        return collect(DEFAULT_MAX_THREADS, DEFAULT_MAX_FRAMES);
    }

    public String collect(int maxThreads, int maxFrames) {
        List<ThreadStack> all;
        String source;
        try {
            all = dumpAllThreads();
            source = "HotSpotDiagnosticMXBean (virtual threads included)";
        } catch (Throwable e) { // UnsupportedOperationException, NoClassDefFoundError, IOException …
            log.debug("HotSpot thread dump failed, falling back to platform threads only: {}", e.toString());
            all = platformThreads();
            source = "Thread.getAllStackTraces (platform threads only)";
        }
        List<ThreadStack> shown = filterAndLimit(all, maxThreads);
        return format(shown, all, maxFrames, source);
    }

    /** All threads of this JVM via the HotSpot diagnostic bean; the temp file never outlives the call. */
    static List<ThreadStack> dumpAllThreads() throws IOException {
        Path file = Path.of(System.getProperty("java.io.tmpdir"),
                "mysuricates-threads-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + ".txt");
        try {
            // dumpThreads refuses to overwrite, so the path must not exist yet
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(file.toAbsolutePath().toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            return parse(Files.readAllLines(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Parses the {@code TEXT_PLAIN} format: a header line per thread, frames indented below it. */
    static List<ThreadStack> parse(List<String> lines) {
        List<ThreadStack> result = new ArrayList<>();
        long id = -1;
        String name = null;
        boolean virtual = false;
        List<String> frames = null;
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                if (frames != null) {
                    result.add(new ThreadStack(id, name, virtual, List.copyOf(frames)));
                }
                id = Long.parseLong(header.group(1));
                name = header.group(2);
                virtual = header.group(3) != null;
                frames = new ArrayList<>();
            } else if (frames != null && !line.isBlank()) {
                frames.add(line.strip());
            }
        }
        if (frames != null) {
            result.add(new ThreadStack(id, name, virtual, List.copyOf(frames)));
        }
        return result;
    }

    static List<ThreadStack> platformThreads() {
        List<ThreadStack> result = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            List<String> frames = Arrays.stream(e.getValue()).map(StackTraceElement::toString).toList();
            result.add(new ThreadStack(t.threadId(), t.getName(), t.isVirtual(), frames));
        }
        return result;
    }

    /** Interesting threads only, likely holders first, waiters last, at most {@code maxThreads}. */
    static List<ThreadStack> filterAndLimit(List<ThreadStack> all, int maxThreads) {
        return all.stream()
                .filter(ThreadStack::isInteresting)
                .sorted(Comparator.comparingInt(ThreadDumpCollector::rank).thenComparingLong(ThreadStack::id))
                .limit(Math.max(0, maxThreads))
                .toList();
    }

    private static int rank(ThreadStack t) {
        if (t.isLikelyHolder()) return 0;
        if (t.isWaiter()) return 2;
        return 1;
    }

    static String format(List<ThreadStack> shown, List<ThreadStack> all, int maxFrames, String source) {
        long interesting = all.stream().filter(ThreadStack::isInteresting).count();
        long virtualCount = all.stream().filter(ThreadStack::virtual).count();
        StringBuilder sb = new StringBuilder();
        sb.append("  source=").append(source)
                .append(", threads=").append(all.size())
                .append(" (virtual=").append(virtualCount)
                .append("), matched=").append(interesting)
                .append(", shown=").append(shown.size());
        for (ThreadStack t : shown) {
            sb.append("\n  Thread \"").append(t.name()).append("\" id=").append(t.id());
            if (t.virtual()) sb.append(" virtual");
            if (t.isLikelyHolder()) sb.append(" [likely holder]");
            else if (t.isWaiter()) sb.append(" [waiting for connection]");
            int limit = Math.min(t.frames().size(), Math.max(0, maxFrames));
            for (int i = 0; i < limit; i++) {
                sb.append("\n    at ").append(t.frames().get(i));
            }
            if (t.frames().size() > limit) {
                sb.append("\n    ... ").append(t.frames().size() - limit).append(" more");
            }
        }
        return sb.toString();
    }
}
