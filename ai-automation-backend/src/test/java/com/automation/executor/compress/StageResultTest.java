package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StageResultTest {

    @Test
    void valueSemantics_andNullNoteAllowed() {
        assertThat(new StageResult("a", null)).isEqualTo(new StageResult("a", null));
        assertThat(new StageResult("a", "n")).isNotEqualTo(new StageResult("a", null));
        assertThat(new StageResult("a", null).note()).isNull();
    }

    /** A null note means "nothing to report": the chain shows the bare op and stores no note key. */
    @Test
    void nullNote_inChain_yieldsBareOpAndNoNoteMetadata() {
        var steps = StageChain.parse(List.of(
                Map.of("op", "text.truncate", "max_chars", 100),
                Map.of("op", "lines.dedupe")));

        StageChain.Outcome o = StageChain.run(steps, "kurz", RegexGuard.startingNow(Duration.ofSeconds(5)));

        assertThat(o.notes()).containsExactly("text.truncate", "lines.dedupe (−0 Zeilen)");
        assertThat(o.stageMetadata().getFirst()).doesNotContainKey("note");
        assertThat(o.stageMetadata().get(1)).containsEntry("note", "−0 Zeilen");
    }
}
