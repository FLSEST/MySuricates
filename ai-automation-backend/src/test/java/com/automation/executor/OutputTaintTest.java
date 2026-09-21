package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OutputTaintTest {

    private final OutputTaint taint = new OutputTaint();

    @Test
    void cleanTaint_snapshotIsNone_andExportIsEmpty() {
        assertThat(taint.isEmpty()).isTrue();
        assertThat(taint.snapshot(Set.of())).isSameAs(TemplateResolver.Taint.NONE);
        assertThat(taint.export(Set.of())).isEmpty();
    }

    @Test
    void mark_setsPreviousOutputAndNamedBlock() {
        taint.mark("build");

        var snapshot = taint.snapshot(Set.of());
        assertThat(snapshot.previousOutput()).isTrue();
        assertThat(snapshot.outputs()).containsExactly("build");
    }

    @Test
    void mark_withBlankOrNullName_onlyTaintsPreviousOutput() {
        taint.mark(null);
        taint.mark("  ");

        assertThat(taint.isEmpty()).isFalse();
        assertThat(taint.snapshot(Set.of()).outputs()).isEmpty();
    }

    @Test
    void markBlockOutput_leavesPreviousOutputClean() {
        taint.markBlockOutput("x");

        assertThat(taint.snapshot(Set.of()).previousOutput()).isFalse();
        assertThat(taint.isEmpty()).isFalse();
    }

    @Test
    void untrustedVariablesAlone_produceSnapshotAndExport() {
        var snapshot = taint.snapshot(Set.of("payload"));

        assertThat(snapshot.variables()).containsExactly("payload");
        assertThat(taint.export(Set.of("payload"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("payload"),
                ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE, List.of()));
    }

    @Test
    void export_sortsNamesAndAddsPreviousOutputPseudoName() {
        taint.markBlockOutput("zeta");
        taint.markBlockOutput("alpha");
        taint.markPreviousOutput();

        Map<String, Object> exported = taint.export(Set.of("b", "a"));

        assertThat(exported.get(ExecutionContext.UNTRUSTED_KEYS_VARIABLE)).isEqualTo(List.of("a", "b"));
        assertThat(exported.get(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE))
                .isEqualTo(List.of("alpha", ExecutionContext.PREVIOUS_OUTPUT_NAME, "zeta"));
    }
}
