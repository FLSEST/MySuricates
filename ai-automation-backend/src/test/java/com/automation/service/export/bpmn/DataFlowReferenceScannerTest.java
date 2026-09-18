package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.WorkflowFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platzhalter-Scan: welche Block-Ausgabe fließt in welchen Block. Deterministisch, ohne Run —
 * die vier Formen {@code block.x.output}, {@code previous_output}, {@code step_N_output} und
 * die {@code source}-Angabe der Komprimierungs-Blöcke.
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
        // ExecutionContext setzt step_<StepResult.stepIndex>_output, und StepIndexCounter zählt ab 0.
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
     * Wrapper-Blöcke ziehen zur Laufzeit je Item/Iteration einen weiteren Step-Index
     * ({@code StepIndexCounter}), also stimmt die Zuordnung Step-Index → Blocklisten-Index
     * dahinter nicht mehr. Lieber keine Kante als eine falsche.
     */
    @Test
    void stepIndexHinterEinemWrapperBlockWirdVerworfen() {
        for (String wrapper : List.of("foreach", "while", "retry")) {
            // Der Wrapper steht VOR dem referenzierten Block: zur Laufzeit ist step_1 die erste
            // Iteration des Wrappers, nicht der Block an Listenposition 1.
            assertThat(scan(
                    block(wrapper, "schleife", Map.of("command", "x", "variable_name", "i")),
                    block("shell", "a", Map.of("command", "ls")),
                    block("simple", "c", Map.of("command", "{{ step_1_output }}"))))
                    .as("Wrapper %s vor dem referenzierten Block", wrapper)
                    .isEmpty();
        }
    }

    /** Ein Wrapper ZWISCHEN Quelle und Verbraucher verschiebt den Index der Quelle nicht. */
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

    /** Ein Wrapper HINTER der Referenz verschiebt nichts — die Kante bleibt. */
    @Test
    void wrapperNachDerReferenzLaesstDieKanteBestehen() {
        assertThat(scan(
                block("shell", "a", Map.of("command", "ls")),
                block("simple", "b", Map.of("command", "{{ step_0_output }}")),
                block("foreach", "schleife", Map.of("variable_name", "i", "command", "x"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
    }

    /**
     * Positionsbezogene Platzhalter meinen die Ebene, auf der sie stehen. In einem
     * verschachtelten Block (Switch-Case) ist das nicht die Top-Level-Blockliste — dort wird
     * nur der flache Namensraum {@code block.<name>.output} ausgewertet.
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
        // then_command/else_command sind eigene Config-Werte des conditional-Blocks, keine
        // verschachtelte Blockliste — previous_output meint dort den Block davor.
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("conditional", "pruefung", Map.of(
                        "condition", "x", "then_command", "Analysiere {{ previous_output }}", "else_command", "y"))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
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

    @Test
    void platzhalterInVerschachteltenBloeckenZaehlenFuerDenElternBlock() {
        assertThat(scan(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("switch", "verteiler", Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                Map.of("type", "simple", "config", Map.of(
                                        "command", "Analysiere {{ block.tests.output }}")))))))))
                .containsExactly(new DataFlowReferenceScanner.Reference(0, 1));
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
