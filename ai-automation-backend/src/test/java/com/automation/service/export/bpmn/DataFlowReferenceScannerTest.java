package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.WorkflowFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placeholder scan: which block output flows into which block. Deterministic, without a run —
 * the four forms {@code block.x.output}, {@code previous_output}, {@code step_N_output} and
 * the {@code source} specification of the compression blocks.
 */
class DataFlowReferenceScannerTest {

    private List<DataFlowReferenceScanner.Reference> scan(BlockDefinition... blocks) {
        return DataFlowReferenceScanner.scan(List.of(blocks));
    }

    @Test
    void benannteBlockAusgabeWirdErkannt() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("simple", "report", Map.of("command", "Fasse zusammen: {{ block.tests.output }}"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
    }

    @Test
    void schreibweiseOhneLeerzeichenWirdErkannt() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("simple", "report", Map.of("command", "{{block.tests.output}}"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
    }

    @Test
    void previousOutputZeigtAufDenUnmittelbarVorherigenBlock() {
        assertThat(scan(
                block("shell", "a", Map.of("command", "x")),
                block("shell", "b", Map.of("command", "y")),
                block("simple", "c", Map.of("command", "{{ previous_output }}"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(1, 2));
    }

    @Test
    void previousOutputImErstenBlockErgibtKeineReferenz() {
        assertThat(scan(block("simple", "a", Map.of("command", "{{ previous_output }}")))).isEmpty();
    }

    @Test
    void stepIndexIstNullBasiertWieDerStepIndexCounter() {
        // ExecutionContext sets step_<StepResult.stepIndex>_output, and StepIndexCounter counts from 0.
        assertThat(scan(
                block("shell", "a", Map.of("command", "x")),
                block("shell", "b", Map.of("command", "y")),
                block("simple", "c", Map.of("command", "{{ step_0_output }}"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 2));
    }

    @Test
    void stepIndexAufSichSelbstOderInDieZukunftWirdIgnoriert() {
        assertThat(scan(
                block("simple", "a", Map.of("command", "{{ step_0_output }} {{ step_5_output }}")),
                block("shell", "b", Map.of("command", "y"))))
                .isEmpty();
    }

    /**
     * Wrapper blocks draw a further step index per item/iteration at runtime
     * ({@code StepIndexCounter}), so the mapping step index → block list index
     * behind it is no longer correct. Better no edge than a wrong one.
     */
    @Test
    void stepIndexHinterEinemWrapperBlockWirdVerworfen() {
        for (String wrapper : List.of("foreach", "while", "retry")) {
            // The wrapper comes BEFORE the referenced block: at runtime step_1 is the first
            // iteration of the wrapper, not the block at list position 1.
            assertThat(scan(
                    block(wrapper, "schleife", Map.of("command", "x", "variable_name", "i")),
                    block("shell", "a", Map.of("command", "ls")),
                    block("simple", "c", Map.of("command", "{{ step_1_output }}"))))
                    .as("Wrapper %s vor dem referenzierten Block", wrapper)
                    .isEmpty();
        }
    }

    /** A wrapper BETWEEN source and consumer does not shift the source's index. */
    @Test
    void wrapperZwischenQuelleUndVerbraucherLaesstDieKanteBestehen() {
        assertThat(scan(
                block("shell", "a", Map.of("command", "ls")),
                block("foreach", "schleife", Map.of("variable_name", "i", "command", "x")),
                block("simple", "c", Map.of("command", "{{ step_0_output }}"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 2));
    }

    @Test
    void stepIndexAufDenWrapperBlockSelbstWirdVerworfen() {
        assertThat(scan(
                block("foreach", "schleife", Map.of("variable_name", "i", "command", "x")),
                block("simple", "c", Map.of("command", "{{ step_0_output }}"))))
                .isEmpty();
    }

    /** A wrapper BEHIND the reference shifts nothing — the edge stays. */
    @Test
    void wrapperNachDerReferenzLaesstDieKanteBestehen() {
        assertThat(scan(
                block("shell", "a", Map.of("command", "ls")),
                block("simple", "b", Map.of("command", "{{ step_0_output }}")),
                block("foreach", "schleife", Map.of("variable_name", "i", "command", "x"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
    }

    /**
     * Position-based placeholders mean the level they stand on. In a
     * nested block (switch case) that is not the top-level block list — there only
     * the flat namespace {@code block.<name>.output} is evaluated.
     */
    @Test
    void previousOutputInVerschachteltemBlockWirdVerworfen() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                Map.of("type", "simple", "config", Map.of("command", "erst {{ x }}")),
                                Map.of("type", "simple", "config", Map.of("command", "dann {{ previous_output }}")))))))))
                .isEmpty();
    }

    @Test
    void stepIndexInVerschachteltemBlockWirdVerworfen() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                Map.of("type", "simple", "config", Map.of("command", "{{ step_0_output }}")))))))))
                .isEmpty();
    }

    @Test
    void previousOutputAlsDirekterConfigWertBleibtEineTopLevelReferenz() {
        // then_command/else_command are own config values of the conditional block, not a
        // nested block list — previous_output there means the block before it. This is exactly what
        // this test records: the SOURCE stays the top-level block 0.
        //
        // Since TODO 7557b928 the TARGET is the branch task (_then), no longer the conditional block
        // itself: BPMN allows no data association on the gateway, the edge was dropped there.
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("conditional", "pruefung", Map.of(
                        "condition", "x", "then_command", "Analysiere {{ previous_output }}", "else_command", "y"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(
                        DataFlowReferenceScanner.Endpoint.topLevel(0),
                        new DataFlowReferenceScanner.Endpoint(1, "_then")));
    }

    @Test
    void komprimierungsQuelleWirdErkannt() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("compress", "kompakt", Map.of("source", "tests", "max_chars", 5000)),
                block("compress-ai", "kurz", Map.of("source", "kompakt", "instruction", "Fehler nennen"))))
                .containsExactly(
                        new DataFlowReferenceScanner.Reference(0, 1),
                        new DataFlowReferenceScanner.Reference(1, 2));
    }

    @Test
    void komprimierungsQuellePreviousMeintDenVorherigenBlock() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("compress", "kompakt", Map.of("source", "previous"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
    }

    /**
     * 7557b928: a placeholder in a switch case points to the branch block, not to the
     * switch itself — that is a gateway and carries no data association in BPMN.
     */
    @Test
    void platzhalterInVerschachteltenBloeckenZeigenAufDenZweigBlock() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                Map.of("type", "simple", "config", Map.of(
                                        "command", "Analysiere {{ block.tests.output }}")))))))))
                .containsExactly(new DataFlowReferenceScanner.Reference(
                        DataFlowReferenceScanner.Endpoint.topLevel(0),
                        new DataFlowReferenceScanner.Endpoint(1, "_c0_0")));
    }

    @Test
    void platzhalterImDefaultZweigZeigtAufDenDefaultBlock() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "RED", "blocks", List.of())),
                        "default", List.of(Map.of("type", "simple", "config", Map.of(
                                "command", "Analysiere {{ block.tests.output }}")))))))
                .containsExactly(new DataFlowReferenceScanner.Reference(
                        DataFlowReferenceScanner.Endpoint.topLevel(0),
                        new DataFlowReferenceScanner.Endpoint(1, "_default_0")));
    }

    /** A named case block is addressable at runtime as {@code block.<name>.output} (12a3b244). */
    @Test
    void benannterCaseBlockIstQuelleFuerSpaetereBloecke() {
        assertThat(scan(
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                Map.of("type", "shell", "name", "alarm", "config", Map.of("command", "echo alarm"))))))),
                block("simple", "report", Map.of("command", "Bericht: {{ block.alarm.output }}"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(
                        new DataFlowReferenceScanner.Endpoint(0, "_c0_0"),
                        DataFlowReferenceScanner.Endpoint.topLevel(1)));
    }

    /** Even nested deeper the path stays unambiguous (switch in switch). */
    @Test
    void zweifachVerschachtelterCaseBlockBehaeltSeinenPfad() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "aussen", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "A", "blocks", List.of(
                                Map.of("type", "switch", "config", Map.of(
                                        "expression", "{{ inner }}",
                                        "cases", List.of(Map.of("value", "1", "blocks", List.of(
                                                Map.of("type", "simple", "config", Map.of(
                                                        "command", "{{ block.tests.output }}"))))))))))))))
                .containsExactly(new DataFlowReferenceScanner.Reference(
                        DataFlowReferenceScanner.Endpoint.topLevel(0),
                        new DataFlowReferenceScanner.Endpoint(1, "_c0_0_c0_0")));
    }

    /** Within the same switch block no edge arises: at runtime only one branch runs. */
    @Test
    void referenzZwischenZweiCaseBloeckenDesselbenSwitchWirdVerworfen() {
        assertThat(scan(
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(
                                Map.of("value", "A", "blocks", List.of(
                                        Map.of("type", "shell", "name", "erst", "config", Map.of("command", "ls")))),
                                Map.of("value", "B", "blocks", List.of(
                                        Map.of("type", "simple", "config", Map.of(
                                                "command", "{{ block.erst.output }}")))))))))
                .isEmpty();
    }

    @Test
    void mehrdeutigerNameZwischenZweigUndTopLevelWirdIgnoriert() {
        assertThat(scan(
                block("shell", "doppelt", Map.of("command", "ls")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "A", "blocks", List.of(
                                Map.of("type", "shell", "name", "doppelt", "config", Map.of("command", "pwd"))))))),
                block("simple", "report", Map.of("command", "{{ block.doppelt.output }}"))))
                .isEmpty();
    }

    /** The level semantics remain: {@code source: "previous"} in the branch means the inner level. */
    @Test
    void komprimierungsQuellePreviousInEinemZweigWirdVerworfen() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "A", "blocks", List.of(
                                Map.of("type", "compress", "config", Map.of("source", "previous")))))))))
                .isEmpty();
    }

    /** … but a block name as {@code source} in the branch points to the branch block. */
    @Test
    void komprimierungsQuelleMitBlocknamenInEinemZweigWirdErkannt() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "A", "blocks", List.of(
                                Map.of("type", "compress", "config", Map.of("source", "tests")))))))))
                .containsExactly(new DataFlowReferenceScanner.Reference(
                        DataFlowReferenceScanner.Endpoint.topLevel(0),
                        new DataFlowReferenceScanner.Endpoint(1, "_c0_0")));
    }

    @Test
    void mehrfachnennungWirdNurEinmalGezaehlt() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("simple", "report", Map.of(
                        "command", "{{ block.tests.output }} und nochmal {{ block.tests.output }}",
                        "note", "{{ previous_output }}"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
    }

    @Test
    void ohnePlatzhalterGibtEsKeineReferenz() {
        assertThat(scan(
                block("shell", "a", Map.of("command", "ls")),
                block("shell", "b", Map.of("command", "pwd"))))
                .isEmpty();
    }

    @Test
    void unbekannterBlockNameWirdIgnoriert() {
        assertThat(scan(
                block("shell", "a", Map.of("command", "ls")),
                block("simple", "b", Map.of("command", "{{ block.gibtesnicht.output }}"))))
                .isEmpty();
    }

    @Test
    void mehrdeutigerBlockNameWirdIgnoriert() {
        assertThat(scan(
                block("shell", "doppelt", Map.of("command", "a")),
                block("shell", "doppelt", Map.of("command", "b")),
                block("simple", "c", Map.of("command", "{{ block.doppelt.output }}"))))
                .isEmpty();
    }

    @Test
    void rueckwaertsReferenzAufSpaeterenBlockWirdIgnoriert() {
        assertThat(scan(
                block("simple", "a", Map.of("command", "{{ block.spaeter.output }}")),
                block("shell", "spaeter", Map.of("command", "ls"))))
                .isEmpty();
    }

    @Test
    void referenzenSindNachZielUndQuelleSortiert() {
        List<DataFlowReferenceScanner.Reference> refs = scan(
                block("shell", "a", Map.of("command", "ls")),
                block("shell", "b", Map.of("command", "pwd")),
                block("simple", "c", Map.of("command", "{{ block.b.output }} {{ block.a.output }}")));
        assertThat(refs).containsExactly(
                new DataFlowReferenceScanner.Reference(0, 2),
                new DataFlowReferenceScanner.Reference(1, 2));
    }

    @Test
    void leereBlockListeIstZulaessig() {
        assertThat(DataFlowReferenceScanner.scan(List.of())).isEmpty();
        assertThat(DataFlowReferenceScanner.scan(null)).isEmpty();
    }
}
