package com.automation.executor;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceStepIndexTest {

    private static StepResult shellStep(int index, StepStatus status, Integer exitCode) {
        Map<String, Object> md = new HashMap<>();
        md.put("blockType", "shell");
        if (exitCode != null) md.put("exitCode", exitCode);
        return StepResult.builder().id("step-" + index).stepIndex(index).status(status)
                .durationMs(312_000L).metadata(md).build();
    }

    @Test
    void recordsFactsFromStoredStep() {
        SourceStepIndex index = new SourceStepIndex();
        index.record("backend_tests", shellStep(0, StepStatus.FAILED, 1));

        SourceStep s = index.find("backend_tests").orElseThrow();
        assertThat(s.stepId()).isEqualTo("step-0");
        assertThat(s.status()).isEqualTo(StepStatus.FAILED);
        assertThat(s.exitCode()).isEqualTo(1);
        assertThat(s.blockType()).isEqualTo("shell");
        assertThat(s.durationMs()).isEqualTo(312_000L);
        assertThat(s.root()).isNull();
        assertThat(s.origin()).isSameAs(s);
    }

    @Test
    void unnamedStepUpdatesLastOnly() {
        SourceStepIndex index = new SourceStepIndex();
        index.record("a", shellStep(0, StepStatus.COMPLETED, 0));
        index.record(null, shellStep(1, StepStatus.COMPLETED, 0));

        assertThat(index.last().orElseThrow().stepIndex()).isEqualTo(1);
        assertThat(index.find("a")).isPresent();
    }

    @Test
    void duplicateNameIsAmbiguous() {
        SourceStepIndex index = new SourceStepIndex();
        index.record("x", shellStep(0, StepStatus.COMPLETED, 0));
        index.record("x", shellStep(1, StepStatus.COMPLETED, 0));

        assertThat(index.isAmbiguous("x")).isTrue();
        assertThat(index.isAmbiguous("y")).isFalse();
    }

    @Test
    void derivedStepCarriesOriginFromCompressionMetadata() {
        SourceStep original = SourceStep.of("backend_tests", shellStep(0, StepStatus.FAILED, 1));
        Map<String, Object> md = new HashMap<>();
        md.put("blockType", "compress");
        md.put("derivedOutput", true);
        md.put("compression", Map.of("source", original.toMetadata()));
        StepResult compressStep = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).metadata(md).build();

        SourceStepIndex index = new SourceStepIndex();
        index.record("backend_kompakt", compressStep);

        SourceStep derived = index.find("backend_kompakt").orElseThrow();
        assertThat(derived.root()).isNotNull();
        assertThat(derived.origin().blockName()).isEqualTo("backend_tests");
        assertThat(derived.origin().status()).isEqualTo(StepStatus.FAILED);
        assertThat(derived.origin().exitCode()).isEqualTo(1);
    }

    @Test
    void metadataRoundTripOmitsNulls() {
        SourceStep s = new SourceStep(null, null, 3, "simple", StepStatus.COMPLETED, null, null, null);

        Map<String, Object> md = s.toMetadata();

        assertThat(md).containsOnlyKeys("stepIndex", "blockType", "status");
        assertThat(SourceStep.fromMetadata(md)).isEqualTo(s);
    }
}
