package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverTest {

    @Test
    void nullTemplate_staysNull_onEveryPath() {
        assertThat(TemplateResolver.resolve(null, "p", Map.of(), Map.of())).isNull();
        assertThat(TemplateResolver.resolveShellSafe(null, "p", Map.of(), Map.of(), k -> { })).isNull();
        assertThat(TemplateResolver.resolveShell(null, "p", Map.of(), Map.of(),
                new TemplateResolver.Taint(Set.of("x"), Set.of(), true), k -> { })).isNull();
    }

    @Test
    void previousOutputNull_resolvesToEmpty_andNullBlockOutputToo() {
        Map<String, String> outputs = new HashMap<>();
        outputs.put("b", null);

        assertThat(TemplateResolver.resolve("[{{ previous_output }}][{{ block.b.output }}]", null, outputs, Map.of()))
                .isEqualTo("[][]");
    }

    @Test
    void blockPlaceholderWithEmptyName_orUnknownBlock_fallsBackToVariables_thenStaysLiteral() {
        Map<String, Object> vars = new HashMap<>(Map.of("block.x.output", "from-var"));

        assertThat(TemplateResolver.resolve("{{ block..output }} {{ block.x.output }} {{ block.y.output }}",
                "", Map.of(), vars))
                .isEqualTo("{{ block..output }} from-var {{ block.y.output }}");
    }

    @Test
    void nonStringVariables_areRenderedViaStringValueOf() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("n", 42);
        vars.put("nothing", null);

        assertThat(TemplateResolver.resolve("{{ n }}/{{ nothing }}", "", Map.of(), vars)).isEqualTo("42/null");
    }

    @Test
    void replacementValuesWithRegexSpecials_areInsertedLiterally() {
        Map<String, Object> vars = new HashMap<>(Map.of("v", "$1 \\ $0"));

        assertThat(TemplateResolver.resolve("x={{ v }}", "", Map.of(), vars)).isEqualTo("x=$1 \\ $0");
    }

    @Test
    void resolvePrompt_reportsEveryResolvedKey_butNotUnknownOnes() {
        List<String> reported = new ArrayList<>();
        Map<String, Object> vars = new HashMap<>(Map.of("a", "1"));

        String out = TemplateResolver.resolvePrompt("{{ a }} {{a}} {{ b }} {{ previous_output }}", "p",
                Map.of(), vars, reported::add);

        assertThat(out).isEqualTo("1 1 {{ b }} p");
        assertThat(reported).containsExactly("a", "a", "previous_output");
    }

    @Test
    void shellEscape_quotesAndEscapesSingleQuotes_nullBecomesEmptyQuotes() {
        assertThat(TemplateResolver.shellEscape(null)).isEqualTo("''");
        assertThat(TemplateResolver.shellEscape("")).isEqualTo("''");
        assertThat(TemplateResolver.shellEscape("it's")).isEqualTo("'it'\\''s'");
    }

    @Test
    void resolveShell_withoutTaint_isRawResolution() {
        Map<String, Object> vars = new HashMap<>(Map.of("v", "a b"));

        assertThat(TemplateResolver.resolveShell("echo {{ v }}", "", Map.of(), vars,
                TemplateResolver.Taint.NONE, k -> { })).isEqualTo("echo a b");
    }

    @Test
    void resolveShell_escapesOnlyTaintedValues_andReportsOnlyTaintedVariablesThatOccurred() {
        Map<String, Object> vars = new HashMap<>(Map.of("evil", "$(rm -rf /)", "safe", "ok", "unused", "x"));
        Map<String, String> outputs = Map.of("build", "out'put", "clean", "c");
        List<String> reported = new ArrayList<>();
        var taint = new TemplateResolver.Taint(Set.of("evil", "unused"), Set.of("build"), true);

        String out = TemplateResolver.resolveShell(
                "{{ evil }} {{evil}} {{ safe }} {{ block.build.output }} {{ block.clean.output }} {{ previous_output }}",
                "prev", outputs, vars, taint, reported::add);

        assertThat(out).isEqualTo("'$(rm -rf /)' '$(rm -rf /)' ok 'out'\\''put' c 'prev'");
        assertThat(reported).containsExactly("evil");
    }

    @Test
    void resolveShell_taintedUnknownBlock_keepsItsPlaceholder() {
        var taint = new TemplateResolver.Taint(Set.of(), Set.of("ghost"), false);

        assertThat(TemplateResolver.resolveShell("x {{ block.ghost.output }}", "", Map.of(), Map.of(), taint, k -> { }))
                .isEqualTo("x {{ block.ghost.output }}");
    }

    @Test
    void resolveShell_taintedNullPreviousOutput_becomesEmptyQuotes() {
        var taint = new TemplateResolver.Taint(Set.of(), Set.of(), true);

        assertThat(TemplateResolver.resolveShell("cat {{ previous_output }}", null, Map.of(), Map.of(), taint, k -> { }))
                .isEqualTo("cat ''");
    }

    @Test
    void resolveShell_taintedValueContainingSentinelLookalikeOrPlaceholder_isNotReExpanded() {
        Map<String, Object> vars = new HashMap<>(Map.of("evil", "{{ safe }} \u0000untrusted-0\u0000", "safe", "S"));
        var taint = new TemplateResolver.Taint(Set.of("evil"), Set.of(), false);

        String out = TemplateResolver.resolveShell("{{ evil }}", "", Map.of(), vars, taint, k -> { });

        assertThat(out).isEqualTo("'{{ safe }} \u0000untrusted-0\u0000'");
    }

    @Test
    void taintIsEmpty_onlyWhenAllPartsAreEmpty() {
        assertThat(TemplateResolver.Taint.NONE.isEmpty()).isTrue();
        assertThat(new TemplateResolver.Taint(Set.of("v"), Set.of(), false).isEmpty()).isFalse();
        assertThat(new TemplateResolver.Taint(Set.of(), Set.of("o"), false).isEmpty()).isFalse();
        assertThat(new TemplateResolver.Taint(Set.of(), Set.of(), true).isEmpty()).isFalse();
    }

    @Test
    void resolves_answersLikeLookup() {
        Map<String, String> outputs = Map.of("b", "");
        Map<String, Object> vars = new HashMap<>(Map.of("v", ""));

        assertThat(TemplateResolver.resolves("previous_output", null, outputs, vars)).isTrue();
        assertThat(TemplateResolver.resolves("block.b.output", null, outputs, vars)).isTrue();
        assertThat(TemplateResolver.resolves("v", null, outputs, vars)).isTrue();
        assertThat(TemplateResolver.resolves("missing", null, outputs, vars)).isFalse();
        assertThat(TemplateResolver.resolves("block.other.output", null, outputs, vars)).isFalse();
    }
}
