package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonStagesTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private static final String FEED = """
            {"meta":{"count":3,"source":"rss"},
             "items":[{"title":"B","url":"u2","date":"2026-09-02","body":"lang"},
                      {"title":"A","url":"u1","date":"2026-09-10","body":"lang"},
                      {"url":"u3","body":"ohne Titel und Datum"}]}
            """;

    @Test
    void select_keepsOnlyPaths_andReportsMissing() {
        StageResult r = new JsonSelectStage().apply(FEED, Map.of("paths", List.of("items[].title", "meta.count", "nope.x")), guard);

        assertThat(r.output()).isEqualTo("{\"items\":[{\"title\":\"B\"},{\"title\":\"A\"},{}],\"meta\":{\"count\":3}}");
        assertThat(r.note()).isEqualTo("Pfad nicht gefunden: nope.x");
    }

    @Test
    void select_rootArray() {
        StageResult r = new JsonSelectStage().apply("[{\"a\":1,\"b\":2}]", Map.of("paths", List.of("[].a")), guard);

        assertThat(r.output()).isEqualTo("[{\"a\":1}]");
    }

    @Test
    void select_nonJsonInput_fails() {
        assertThatThrownBy(() -> new JsonSelectStage().apply("kein json", Map.of("paths", List.of("a")), guard))
                .isInstanceOf(CompressionException.class).hasMessageContaining("kein gültiges JSON");
    }

    @Test
    void sort_desc_missingLast_thenLimit_thenToLines() {
        String sorted = new JsonSortStage().apply(FEED, Map.of("path", "items", "by", "date", "order", "desc"), guard).output();
        String limited = new JsonLimitStage().apply(sorted, Map.of("path", "items", "count", 2), guard).output();
        StageResult lines = new JsonToLinesStage().apply(limited, Map.of("path", "items", "template", "{title} — {url}"), guard);

        assertThat(lines.output()).isEqualTo("A — u1\nB — u2");
        assertThat(lines.note()).isEqualTo("2 Einträge als Zeilen");
    }

    @Test
    void sort_mixedValues_isTotal_numbersFirst_missingLast() {
        String mixed = "[{\"v\":\"b\"},{\"v\":10},{\"v\":\"a\"},{\"v\":2},{}]";

        String asc = new JsonSortStage().apply(mixed, Map.of("by", "v"), guard).output();
        String desc = new JsonSortStage().apply(mixed, Map.of("by", "v", "order", "desc"), guard).output();

        assertThat(asc).isEqualTo("[{\"v\":2},{\"v\":10},{\"v\":\"a\"},{\"v\":\"b\"},{}]");
        assertThat(desc).isEqualTo("[{\"v\":\"b\"},{\"v\":\"a\"},{\"v\":10},{\"v\":2},{}]");
        // numeric text between numbers made the old comparator cyclic (10 < "15" < 2 < 10)
        assertThat(new JsonSortStage().apply("[{\"v\":2},{\"v\":\"15\"},{\"v\":10}]", Map.of("by", "v"), guard).output())
                .isEqualTo("[{\"v\":2},{\"v\":10},{\"v\":\"15\"}]");
    }

    @Test
    void select_outputLongerThanInput_noteShowsGrowth() {
        StageResult r = new JsonSelectStage().apply("{\"a\":1e2}", Map.of("paths", List.of("a")), guard);

        assertThat(r.output()).isEqualTo("{\"a\":100.0}");
        assertThat(r.note()).isEqualTo("+2 Zeichen").doesNotContain("−-");
    }

    @Test
    void toLines_missingFieldIsEmpty_numbersAndObjectsRendered() {
        StageResult r = new JsonToLinesStage().apply("[{\"n\":5,\"o\":{\"k\":1}},{}]", Map.of("template", "{n}|{o}|{fehlt}"), guard);

        assertThat(r.output()).isEqualTo("5|{\"k\":1}|\n||");
    }

    @Test
    void limit_notAnArray_fails() {
        assertThatThrownBy(() -> new JsonLimitStage().apply(FEED, Map.of("path", "meta", "count", 1), guard))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'meta' ist kein Array");
    }

    /** Shared parse+arrayAt helper: every array stage reports the same two problems the same way. */
    @Test
    void arrayStages_nonJsonAndNonArrayPath_failAlike() {
        Map<String, Object> limit = Map.of("path", "meta", "count", 1);
        Map<String, Object> sort = Map.of("path", "meta", "by", "date");
        Map<String, Object> toLines = Map.of("path", "meta", "template", "{title}");

        assertThatThrownBy(() -> new JsonLimitStage().apply("kein json", limit, guard))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe json.limit: Eingabe ist kein gültiges JSON");
        assertThatThrownBy(() -> new JsonSortStage().apply("kein json", sort, guard))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe json.sort: Eingabe ist kein gültiges JSON");
        assertThatThrownBy(() -> new JsonToLinesStage().apply("kein json", toLines, guard))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe json.to_lines: Eingabe ist kein gültiges JSON");

        assertThatThrownBy(() -> new JsonLimitStage().apply(FEED, limit, guard))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe json.limit: 'meta' ist kein Array");
        assertThatThrownBy(() -> new JsonSortStage().apply(FEED, sort, guard))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe json.sort: 'meta' ist kein Array");
        assertThatThrownBy(() -> new JsonToLinesStage().apply(FEED, toLines, guard))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe json.to_lines: 'meta' ist kein Array");
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> new JsonToLinesStage().validate(Map.of("template", "ohne Platzhalter")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Platzhalter");
        assertThatThrownBy(() -> new JsonSortStage().validate(Map.of("by", "date", "order", "zufall")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("order");
        assertThatThrownBy(() -> new JsonSelectStage().validate(Map.of("paths", List.of("a..b"))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("ungültiger Pfad");
    }
}
