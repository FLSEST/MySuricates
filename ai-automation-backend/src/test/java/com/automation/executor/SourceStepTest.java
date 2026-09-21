package com.automation.executor;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceStepTest {

    private static StepResult step(String id, int index, StepStatus status, Long duration, Map<String, Object> metadata) {
        StepResult step = new StepResult();
        step.setId(id);
        step.setStepIndex(index);
        step.setStatus(status);
        step.setDurationMs(duration);
        step.setMetadata(metadata);
        return step;
    }

    @Test
    void of_readsStoredFacts_andIsItsOwnOrigin() {
        SourceStep source = SourceStep.of("build", step("s1", 4, StepStatus.FAILED, 1200L,
                Map.of("exitCode", 2, "blockType", "shell")));

        assertThat(source).isEqualTo(new SourceStep("build", "s1", 4, "shell", StepStatus.FAILED, 2, 1200L, null));
        assertThat(source.origin()).isSameAs(source);
    }

    @Test
    void of_toleratesNullMetadataAndWrongTypes() {
        SourceStep fromNull = SourceStep.of("b", step("s", 0, StepStatus.COMPLETED, null, null));
        SourceStep wrongTypes = SourceStep.of("b", step("s", 0, StepStatus.COMPLETED, null,
                Map.of("exitCode", "2", "blockType", 7)));

        assertThat(fromNull.exitCode()).isNull();
        assertThat(fromNull.blockType()).isNull();
        assertThat(wrongTypes.exitCode()).isNull();
        assertThat(wrongTypes.blockType()).isNull();
    }

    @Test
    void of_derivedCompressionOutput_pointsToOriginalSource() {
        Map<String, Object> original = new SourceStep("tests", "s0", 1, "shell", StepStatus.FAILED, 1, 500L, null)
                .toMetadata();
        SourceStep compressed = SourceStep.of("short", step("s2", 2, StepStatus.COMPLETED, 10L, Map.of(
                "derivedOutput", true, "compression", Map.of("source", original))));

        assertThat(compressed.root()).isNotNull();
        assertThat(compressed.origin().blockName()).isEqualTo("tests");
        assertThat(compressed.origin().status()).isEqualTo(StepStatus.FAILED);
        assertThat(compressed.origin().exitCode()).isEqualTo(1);
    }

    @Test
    void of_compressionMetadataWithoutDerivedFlag_hasNoRoot() {
        Map<String, Object> md = new HashMap<>();
        md.put("compression", Map.of("source", Map.of("block", "x")));

        assertThat(SourceStep.of("c", step("s", 0, StepStatus.COMPLETED, null, md)).root()).isNull();
    }

    @Test
    void toMetadata_omitsNullFields_andRoundTripsThroughFromMetadata() {
        SourceStep sparse = new SourceStep(null, null, 3, null, null, null, null, null);
        assertThat(sparse.toMetadata()).containsExactly(Map.entry("stepIndex", 3));

        SourceStep full = new SourceStep("b", "id", 5, "simple", StepStatus.COMPLETED, 0, 99L, null);
        assertThat(full.toMetadata()).containsEntry("status", "COMPLETED");
        assertThat(SourceStep.fromMetadata(full.toMetadata())).isEqualTo(full);
    }

    @Test
    void fromMetadata_defaultsIndexToZero_andRejectsUnknownStatus() {
        assertThat(SourceStep.fromMetadata(Map.of()).stepIndex()).isZero();
        assertThatThrownBy(() -> SourceStep.fromMetadata(Map.of("status", "BOGUS")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
