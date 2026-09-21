package com.automation.service.export.bpmn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.WorkflowFixtures.ordered;
import static org.assertj.core.api.Assertions.assertThat;

class DataFlowConfigStringsTest {

    @Test
    void nullUndSkalareLiefernNichts() {
        assertThat(DataFlowConfigStrings.of(null)).isEqualTo(new DataFlowConfigStrings(List.of(), List.of()));
        assertThat(DataFlowConfigStrings.of(42).direct()).isEmpty();
    }

    @Test
    void nurStringsMitGeschweifterKlammerZaehlen() {
        DataFlowConfigStrings s = DataFlowConfigStrings.of(ordered(
                "command", "echo {{ previous_output }}",
                "plain", "kein Platzhalter",
                "timeout_seconds", 30));

        assertThat(s.direct()).containsExactly("echo {{ previous_output }}");
        assertThat(s.nested()).isEmpty();
    }

    @Test
    void trenntObersteEbeneVonVerschachteltenWerten() {
        DataFlowConfigStrings s = DataFlowConfigStrings.of(ordered(
                "prompt", "{{ a }}",
                "params", ordered("body", "{{ b }}"),
                "cases", List.of(ordered("blocks", List.of(ordered("config", ordered("command", "{{ c }}")))))));

        assertThat(s.direct()).containsExactly("{{ a }}");
        assertThat(s.nested()).containsExactly("{{ b }}", "{{ c }}");
    }

    @Test
    void listeAlsWurzelGiltAuchAlsObersteEbene() {
        DataFlowConfigStrings s = DataFlowConfigStrings.of(List.of("{{ x }}", List.of("{{ y }}")));

        assertThat(s.direct()).containsExactly("{{ x }}");
        assertThat(s.nested()).containsExactly("{{ y }}");
    }

    @Test
    void tiefeVerschachtelungWirdBeiMaxTiefeAbgeschnitten() {
        Object value = "{ tief }";
        for (int i = 0; i < 25; i++) {
            List<Object> wrapper = new ArrayList<>();
            wrapper.add(value);
            value = wrapper;
        }
        assertThat(DataFlowConfigStrings.of(value).nested()).isEmpty();

        Object shallow = "{ flach }";
        for (int i = 0; i < 20; i++) {
            shallow = List.of(shallow);
        }
        assertThat(DataFlowConfigStrings.of(shallow).nested()).containsExactly("{ flach }");
    }

    @Test
    void zyklischeStrukturTerminiertDurchTiefenlimit() {
        Map<String, Object> loop = new java.util.HashMap<>();
        loop.put("self", loop);
        loop.put("v", "{{ z }}");

        DataFlowConfigStrings s = DataFlowConfigStrings.of(loop);

        assertThat(s.direct()).containsExactly("{{ z }}");
        assertThat(s.nested()).isNotEmpty().allMatch("{{ z }}"::equals);
    }
}
