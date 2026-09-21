package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class CompressionExceptionTest {

    @Test
    void isUncheckedAndKeepsMessage() {
        CompressionException e = new CompressionException("Quelle fehlt");

        assertThat(e).isInstanceOf(RuntimeException.class).hasMessage("Quelle fehlt").hasNoCause();
    }

    /** Stage errors surface as CompressionException with a single position prefix instead of the stage's own. */
    @Test
    void stageRunError_isRethrownWithPositionPrefix() {
        var steps = StageChain.parse(List.of(Map.of("op", "json.limit", "count", 1)));

        CompressionException e = catchThrowableOfType(CompressionException.class,
                () -> StageChain.run(steps, "kein json", RegexGuard.startingNow(Duration.ofSeconds(5))));

        assertThat(e).hasMessage("Stufe 1 (json.limit): Eingabe ist kein gültiges JSON");
    }
}
