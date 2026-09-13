package com.automation.service.watchdog;

import com.automation.service.watchdog.ThreadDumpCollector.ThreadStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watchdog dump must show the thread that HOLDS the single Hikari connection. Workflow
 * runs execute on virtual threads, which {@code Thread.getAllStackTraces()} omits — so the
 * collector has to go through the HotSpot diagnostic bean (Audit-TODO 6aa3cc39).
 */
class ThreadDumpCollectorTest {

    private static final String PROBE_THREAD = "virtual-probe-holder";

    private final AtomicBoolean release = new AtomicBoolean(false);
    private Thread probe;

    @AfterEach
    void releaseProbe() throws InterruptedException {
        release.set(true);
        if (probe != null) {
            LockSupport.unpark(probe);
            probe.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    /** A recognisable frame (package com.automation) in which the virtual thread parks. */
    private void parkInRecognisableFrame(CountDownLatch parked) {
        parked.countDown();
        while (!release.get()) {
            LockSupport.park(this);
        }
    }

    @Test
    void collect_includesParkedVirtualThread() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        probe = Thread.ofVirtual().name(PROBE_THREAD).start(() -> parkInRecognisableFrame(parked));
        assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
        awaitState(probe, Thread.State.WAITING);

        // Control: the pre-fix mechanism cannot see the virtual thread at all
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> PROBE_THREAD.equals(t.getName()));

        String dump = new ThreadDumpCollector().collect();

        assertThat(dump)
                .contains("Thread \"" + PROBE_THREAD + "\"")
                .contains("virtual")
                .contains("ThreadDumpCollectorTest.parkInRecognisableFrame");
        assertThat(dump).contains("source=HotSpotDiagnosticMXBean");
    }

    @Test
    void dumpAllThreads_leavesNoTempFileBehind() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        long before = countDumpFiles(tmp);

        List<ThreadStack> threads = ThreadDumpCollector.dumpAllThreads();

        assertThat(threads).anyMatch(t -> "main".equals(t.name()) || t.name().contains("ForkJoinPool") || !t.frames().isEmpty());
        assertThat(countDumpFiles(tmp)).isEqualTo(before);
    }

    @Test
    void parse_readsHotSpotTextFormatIncludingVirtualMarker() {
        List<String> lines = List.of(
                "12345",
                "2026-09-13T17:29:29.563059650Z",
                "21.0.12+8-1-24.04-Ubuntu",
                "",
                "#1 \"main\"",
                "      java.base/java.lang.Thread.getStackTrace(Thread.java:2451)",
                "      com.automation.Foo.bar(Foo.java:10)",
                "",
                "#11 \"Signal Dispatcher\"",
                "",
                "#25 \"virtual-7\" virtual",
                "      java.base/java.lang.Object.wait0(Native Method)",
                "      org.sqlite.core.NativeDB.step(Native Method)",
                "      com.automation.service.workflow.WorkflowExecutor.run(WorkflowExecutor.java:99)",
                "");

        List<ThreadStack> parsed = ThreadDumpCollector.parse(lines);

        assertThat(parsed).hasSize(3);
        assertThat(parsed.get(0).id()).isEqualTo(1);
        assertThat(parsed.get(0).name()).isEqualTo("main");
        assertThat(parsed.get(0).virtual()).isFalse();
        assertThat(parsed.get(0).frames()).containsExactly(
                "java.base/java.lang.Thread.getStackTrace(Thread.java:2451)",
                "com.automation.Foo.bar(Foo.java:10)");
        assertThat(parsed.get(1).name()).isEqualTo("Signal Dispatcher");
        assertThat(parsed.get(1).frames()).isEmpty();
        assertThat(parsed.get(2).id()).isEqualTo(25);
        assertThat(parsed.get(2).virtual()).isTrue();
        assertThat(parsed.get(2).isLikelyHolder()).isTrue();
    }

    @Test
    void filterAndLimit_dropsUninterestingThreads_putsHoldersFirst_capsCount() {
        List<ThreadStack> all = new ArrayList<>();
        // 25 waiters queued on the pool (rank last)
        IntStream.range(0, 25).forEach(i -> all.add(new ThreadStack(100 + i, "waiter-" + i, true, List.of(
                "java.base/jdk.internal.misc.Unsafe.park(Native Method)",
                "com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:180)",
                "com.automation.service.workflow.WorkflowExecutor.step(WorkflowExecutor.java:1)"))));
        // 3 boring JDK-only threads
        IntStream.range(0, 3).forEach(i -> all.add(new ThreadStack(10 + i, "boring-" + i, false, List.of(
                "java.base/java.lang.Object.wait0(Native Method)",
                "java.base/java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:1)"))));
        // 1 plain com.automation thread without pool frames (middle rank)
        all.add(new ThreadStack(50, "scheduler-1", false, List.of(
                "com.automation.service.watchdog.ConnectionPoolWatchdogService.checkPoolHealth(X.java:1)")));
        // the holder — listed LAST with the highest id, must still come first
        all.add(new ThreadStack(999, "virtual-42", true, List.of(
                "org.sqlite.core.NativeDB.step(Native Method)",
                "com.zaxxer.hikari.pool.ProxyStatement.execute(ProxyStatement.java:1)",
                "com.automation.service.workflow.WorkflowExecutor.persist(WorkflowExecutor.java:1)")));
        Collections.shuffle(all);

        List<ThreadStack> shown = ThreadDumpCollector.filterAndLimit(all, 20);

        assertThat(shown).hasSize(20);
        assertThat(shown.get(0).name()).isEqualTo("virtual-42");
        assertThat(shown.get(1).name()).isEqualTo("scheduler-1");
        assertThat(shown).noneMatch(t -> t.name().startsWith("boring-"));
        assertThat(shown.subList(2, 20)).allMatch(ThreadStack::isWaiter);
        assertThat(shown.subList(2, 20)).extracting(ThreadStack::id).isSorted();
    }

    @Test
    void format_capsFramesAndReportsTotals() {
        List<String> frames = IntStream.range(0, 50)
                .mapToObj(i -> "com.automation.Deep.frame" + i + "(Deep.java:" + i + ")")
                .toList();
        ThreadStack deep = new ThreadStack(7, "virtual-7", true, frames);
        ThreadStack boring = new ThreadStack(8, "Finalizer", false, List.of("java.base/java.lang.Object.wait0(Native Method)"));

        String out = ThreadDumpCollector.format(List.of(deep), List.of(deep, boring), 30, "test");

        assertThat(out).startsWith("  source=test, threads=2 (virtual=1), matched=1, shown=1");
        assertThat(out).contains("Thread \"virtual-7\" id=7 virtual");
        assertThat(out).contains("frame29(").doesNotContain("frame30(");
        assertThat(out).contains("... 20 more");
        assertThat(out).doesNotContain("Finalizer");
    }

    @Test
    void format_marksHoldersAndWaiters() {
        ThreadStack holder = new ThreadStack(1, "virtual-1", true, List.of("org.sqlite.core.NativeDB.step(Native Method)"));
        ThreadStack waiter = new ThreadStack(2, "virtual-2", true, List.of("com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:1)"));

        String out = ThreadDumpCollector.format(List.of(holder, waiter), List.of(holder, waiter), 30, "test");

        assertThat(out).contains("\"virtual-1\" id=1 virtual [likely holder]");
        assertThat(out).contains("\"virtual-2\" id=2 virtual [waiting for connection]");
    }

    private static void awaitState(Thread t, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(t.getState()).isEqualTo(expected);
    }

    private static long countDumpFiles(File dir) {
        String[] names = dir.list((d, n) -> n.startsWith("mysuricates-threads-"));
        return names == null ? 0 : names.length;
    }
}
