package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonToLinesStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final JsonToLinesStage stage = new JsonToLinesStage();

    @Test
    void dottedAndHyphenatedFields_booleansAndArrays() {
        StageResult r = stage.apply("[{\"a\":{\"b\":\"x\"},\"c-d\":true,\"l\":[1,2]}]",
                Map.of("template", "{a.b} {c-d} {l}"), guard);

        assertThat(r.output()).isEqualTo("x true [1,2]");
    }

    @Test
    void newlinesInValuesAndTemplate_becomeSpaces_soOneEntryIsOneLine() {
        StageResult r = stage.apply("[{\"t\":\"eins\\nzwei\"},{\"t\":\"drei\"}]", Map.of("template", "- {t}\n  ende"), guard);

        assertThat(r.output()).isEqualTo("- eins zwei   ende\n- drei   ende");
        assertThat(r.note()).isEqualTo("2 Einträge als Zeilen");
    }

    @Test
    void replacementSpecialCharsInValues_areLiteral() {
        StageResult r = stage.apply("[{\"p\":\"$1 \\\\ C:\\\\tmp\"}]", Map.of("template", "{p}"), guard);

        assertThat(r.output()).isEqualTo("$1 \\ C:\\tmp");
    }

    @Test
    void emptyArray_givesEmptyText_andNestedPath() {
        StageResult empty = stage.apply("{\"items\":[]}", Map.of("path", "items", "template", "{t}"), guard);

        assertThat(empty.output()).isEmpty();
        assertThat(empty.note()).isEqualTo("0 Einträge als Zeilen");
    }

    @Test
    void validate_templateMissingOrWithoutField() {
        assertThatThrownBy(() -> stage.validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe json.to_lines: 'template' fehlt");
        assertThatThrownBy(() -> stage.validate(Map.of("template", "{ leer }")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("mindestens einen Platzhalter");
        assertThatThrownBy(() -> stage.validate(Map.of("template", "{t}", "path", 3)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'path' muss Text sein");
    }
}
