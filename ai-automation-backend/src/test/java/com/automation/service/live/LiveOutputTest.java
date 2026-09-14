package com.automation.service.live;

import com.automation.util.LineListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveOutputTest {

    private record Emitted(LiveLineKind kind, String text) {}

    private static LiveOutputSink recording(List<Emitted> target) {
        return (kind, text) -> target.add(new Emitted(kind, text));
    }

    @Test
    void unboundThreadGetsNoop() {
        assertThat(LiveOutput.current()).isSameAs(LiveOutputSink.NOOP);
        assertThat(LiveOutput.current().isActive()).isFalse();
        assertThat(LiveOutput.current().rawLines()).isSameAs(LineListener.NONE);
    }

    @Test
    void bindMakesTheSinkCurrentAndRemovesItAfterwards() {
        List<Emitted> lines = new ArrayList<>();
        LiveOutputSink sink = recording(lines);

        String result = LiveOutput.bind(sink, () -> {
            assertThat(LiveOutput.current()).isSameAs(sink);
            LiveOutput.current().emit(LiveLineKind.TOOL, "Bash: ls");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(lines).containsExactly(new Emitted(LiveLineKind.TOOL, "Bash: ls"));
        assertThat(LiveOutput.current()).isSameAs(LiveOutputSink.NOOP);
    }

    @Test
    void nestedBindRestoresTheOuterSink_evenWhenTheBodyThrows() {
        LiveOutputSink outer = recording(new ArrayList<>());
        LiveOutputSink inner = recording(new ArrayList<>());

        LiveOutput.bind(outer, () -> {
            assertThatThrownBy(() -> LiveOutput.bind(inner, () -> {
                assertThat(LiveOutput.current()).isSameAs(inner);
                throw new IllegalStateException("block failed");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(LiveOutput.current()).isSameAs(outer);
            return null;
        });

        assertThat(LiveOutput.current()).isSameAs(LiveOutputSink.NOOP);
    }

    @Test
    void bindingIsNotInheritedByChildThreads() throws InterruptedException {
        AtomicReference<LiveOutputSink> seenByVirtual = new AtomicReference<>();
        AtomicReference<LiveOutputSink> seenByPlatform = new AtomicReference<>();

        LiveOutput.bind(recording(new ArrayList<>()), () -> {
            try {
                Thread.ofVirtual().start(() -> seenByVirtual.set(LiveOutput.current())).join();
                Thread.ofPlatform().start(() -> seenByPlatform.set(LiveOutput.current())).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertThat(seenByVirtual.get()).isSameAs(LiveOutputSink.NOOP);
        assertThat(seenByPlatform.get()).isSameAs(LiveOutputSink.NOOP);
    }

    @Test
    void rawLinesForwardAsLineKind_withDroppedBytesHint() {
        List<Emitted> lines = new ArrayList<>();
        LineListener listener = recording(lines).rawLines();

        listener.onLine("hello", 0);
        listener.onLine("very long", 1234);

        assertThat(lines).containsExactly(
                new Emitted(LiveLineKind.LINE, "hello"),
                new Emitted(LiveLineKind.LINE, "very long … (+1234 bytes)"));
    }

    @Test
    void wireNameIsLowerCase() {
        assertThat(LiveLineKind.TOOL_RESULT.wire()).isEqualTo("tool_result");
    }
}
