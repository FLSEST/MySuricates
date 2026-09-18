package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverSinglePassTest {

    @Test
    void valueContainingPlaceholder_isNotExpandedAgain_raw() {
        Map<String, String> outputs = Map.of("log", "Test sagte {{ secret }}");
        Map<String, Object> vars = new HashMap<>(Map.of("secret", "TOPSECRET"));

        String out = TemplateResolver.resolve("A: {{ block.log.output }} B: {{ secret }}", "", outputs, vars);

        assertThat(out).isEqualTo("A: Test sagte {{ secret }} B: TOPSECRET");
    }

    @Test
    void previousOutputContainingPlaceholder_isNotExpandedAgain() {
        Map<String, Object> vars = new HashMap<>(Map.of("x", "X"));

        assertThat(TemplateResolver.resolve("{{ previous_output }}", "{{x}} und {{ x }}", Map.of(), vars))
                .isEqualTo("{{x}} und {{ x }}");
    }

    @Test
    void shellSafe_valueContainingPlaceholder_staysInsideItsQuotes() {
        Map<String, String> outputs = Map.of("log", "a {{ v }} b");
        Map<String, Object> vars = new HashMap<>(Map.of("v", "$(rm -rf x)"));

        String out = TemplateResolver.resolveShellSafe("echo {{ block.log.output }}", "", outputs, vars, key -> { });

        assertThat(out).isEqualTo("echo 'a {{ v }} b'");
    }

    @Test
    void bothSpellingsResolve_asymmetricSpacingStaysLiteral_unknownStaysLiteral() {
        Map<String, Object> vars = new HashMap<>(Map.of("a", "1"));

        assertThat(TemplateResolver.resolve("{{a}} {{ a }} {{ a}} {{ b }}", "", Map.of(), vars))
                .isEqualTo("1 1 {{ a}} {{ b }}");
    }

    @Test
    void keysWithSpaces_resolveLikeBefore() {
        Map<String, String> outputs = Map.of("Build Report", "OK");

        assertThat(TemplateResolver.resolve("R: {{ block.Build Report.output }} / {{block.Build Report.output}}", "", outputs, Map.of()))
                .isEqualTo("R: OK / OK");
    }

    @Test
    void asymmetricOrDoubleSpacing_staysLiteral_evenWithSpacesAllowedInKeys() {
        Map<String, Object> vars = new HashMap<>(Map.of("a", "1"));

        assertThat(TemplateResolver.resolve("{{ a}} {{a }} {{  a  }}", "", Map.of(), vars))
                .isEqualTo("{{ a}} {{a }} {{  a  }}");
    }

    @Test
    void unknownBlockName_keepsPlaceholder_dottedNameWorks() {
        Map<String, String> outputs = Map.of("a.b", "AB");

        assertThat(TemplateResolver.resolve("{{ block.a.b.output }} {{ block.zz.output }}", "", outputs, Map.of()))
                .isEqualTo("AB {{ block.zz.output }}");
    }

    @Test
    void nullVariableValue_rendersAsNullLikeBefore() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("n", null);

        assertThat(TemplateResolver.resolve("{{ n }}", "", Map.of(), vars)).isEqualTo("null");
    }
}
