package com.automation.service.runoutput;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Gates bleiben freiwillig — erwartet wird ein Urteil nur, wo die Definition danach fragt (63ecc124). */
class GateExpectationTest {

    private static BlockDefinition block(Map<String, Object> config) {
        BlockDefinition block = new BlockDefinition();
        block.setType("simple");
        block.setConfig(config);
        return block;
    }

    @Test
    void promptMitMarkerAuftragBestelltEinUrteil() {
        assertThat(GateExpectation.isExpected(List.of(
                block(Map.of("prompt", "Am Ende: gate_status: GREEN oder RED"))))).isTrue();
    }

    @Test
    void schreibweiseMitBindestrichUndGrossbuchstabenZaehltAuch() {
        assertThat(GateExpectation.isExpected(List.of(
                block(Map.of("prompt", "Schliesse mit **Gate-Status:** ..."))))).isTrue();
    }

    @Test
    void auftragInEinemVerschachteltenBlockZaehlt() {
        Map<String, Object> inner = Map.of("prompt", "gate_status: GREEN");
        Map<String, Object> foreach = Map.of("items", List.of("a", "b"),
                "blocks", List.of(Map.of("type", "simple", "config", inner)));
        assertThat(GateExpectation.isExpected(List.of(block(foreach)))).isTrue();
    }

    @Test
    void gewoehnlicherBlockBestelltNichts() {
        assertThat(GateExpectation.isExpected(List.of(
                block(Map.of("command", "mvn test"))))).isFalse();
    }

    @Test
    void leereUndNullEingabenSindKeineBestellung() {
        assertThat(GateExpectation.isExpected(List.of())).isFalse();
        assertThat(GateExpectation.isExpected(null)).isFalse();
        assertThat(GateExpectation.isExpected(List.of(block(Map.of())))).isFalse();
    }
}
