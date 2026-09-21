package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Contract of {@link CompressionStage}, checked against every registered implementation. */
class CompressionStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));

    @Test
    void everyStage_isFoundUnderItsOwnOp_withDeclaredKinds() {
        for (String op : CompressionStages.ops()) {
            CompressionStage stage = CompressionStages.find(op).orElseThrow();
            assertThat(stage.op()).isEqualTo(op);
            assertThat(stage.inputKind()).as(op).isNotNull();
            assertThat(stage.outputKind()).as(op).isNotNull().isNotEqualTo(StageKind.ANY);
        }
    }

    @Test
    void onlyJsonStagesExpectJson_andOnlyToLinesLeavesJson() {
        for (String op : CompressionStages.ops()) {
            CompressionStage stage = CompressionStages.find(op).orElseThrow();
            boolean json = op.startsWith("json.");
            assertThat(stage.inputKind()).as(op).isEqualTo(json ? StageKind.JSON : StageKind.ANY);
            StageKind expectedOut = json && !op.equals("json.to_lines") ? StageKind.JSON : StageKind.TEXT;
            assertThat(stage.outputKind()).as(op).isEqualTo(expectedOut);
        }
    }

    @Test
    void validate_failsOnlyWithCompressionException_evenForNullOrEmptyConfig() {
        for (String op : CompressionStages.ops()) {
            CompressionStage stage = CompressionStages.find(op).orElseThrow();
            for (Map<String, Object> config : new Map[]{null, Map.of()}) {
                Throwable t = catchThrowable(() -> stage.validate(config));
                if (t != null) {
                    assertThat(t).as(op).isInstanceOf(CompressionException.class);
                    assertThat(t.getMessage()).as(op).isNotBlank();
                }
            }
        }
    }

    @Test
    void stagesWithoutRequiredOptions_runOnEmptyInput() {
        for (String op : new String[]{"text.normalize", "lines.dedupe"}) {
            CompressionStage stage = CompressionStages.find(op).orElseThrow();
            stage.validate(Map.of());
            StageResult r = stage.apply("", Map.of(), guard);
            assertThat(r.output()).as(op).isEmpty();
        }
    }

    @Test
    void stagesAreStateless_repeatedApplyGivesSameResult() {
        CompressionStage stage = CompressionStages.find("lines.dedupe").orElseThrow();
        Map<String, Object> config = Map.of("count", true);

        StageResult first = stage.apply("a\na\nb", config, guard);
        StageResult second = stage.apply("a\na\nb", config, guard);

        assertThat(second).isEqualTo(first);
    }
}
