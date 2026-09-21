package com.automation.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ProcessTrackingTest {

    @Test
    void unboundThread_getsNoneTracker_whichIgnoresProcesses() {
        assertThat(ProcessTracking.current()).isSameAs(ProcessTracking.Tracker.NONE);
        Process process = mock(Process.class);
        ProcessTracking.Tracker.NONE.started(process);
        ProcessTracking.Tracker.NONE.finished(process);
    }

    @Test
    void bind_makesTrackerCurrent_returnsBodyResult_andUnbindsAfterwards() {
        ProcessTracking.Tracker tracker = mock(ProcessTracking.Tracker.class);

        String result = ProcessTracking.bind(tracker, () -> {
            assertThat(ProcessTracking.current()).isSameAs(tracker);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(ProcessTracking.current()).isSameAs(ProcessTracking.Tracker.NONE);
    }

    @Test
    void nestedBind_restoresOuterTracker_evenWhenBodyThrows() {
        ProcessTracking.Tracker outer = mock(ProcessTracking.Tracker.class);
        ProcessTracking.Tracker inner = mock(ProcessTracking.Tracker.class);

        ProcessTracking.bind(outer, () -> {
            assertThatThrownBy(() -> ProcessTracking.bind(inner, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(ProcessTracking.current()).isSameAs(outer);
            return null;
        });

        assertThat(ProcessTracking.current()).isSameAs(ProcessTracking.Tracker.NONE);
    }

    @Test
    void binding_isNotInheritedByChildThreads() throws Exception {
        AtomicReference<ProcessTracking.Tracker> seenInChild = new AtomicReference<>();
        ProcessTracking.Tracker tracker = mock(ProcessTracking.Tracker.class);

        ProcessTracking.bind(tracker, () -> {
            Thread child = new Thread(() -> seenInChild.set(ProcessTracking.current()));
            child.start();
            try {
                child.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        assertThat(seenInChild.get()).isSameAs(ProcessTracking.Tracker.NONE);
    }
}
