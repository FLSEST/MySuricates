package com.automation.executor.blocks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForEachItemsTest {

    @Test
    void should_yieldNoItems_when_sourceIsNullOrBlank() {
        assertThat(ForEachItems.splitIntoItems(null)).isEmpty();
        assertThat(ForEachItems.splitIntoItems("")).isEmpty();
        assertThat(ForEachItems.splitIntoItems("  \n\t\n")).isEmpty();
    }

    @Test
    void should_splitOnNewlines_andDropBlankLines() {
        assertThat(ForEachItems.splitIntoItems("a\n\nb\n   \nc\n")).containsExactly("a", "b", "c");
    }

    @Test
    void should_keepLinesAsTheyAre_includingSurroundingSpaces() {
        assertThat(ForEachItems.splitIntoItems(" a \nb")).containsExactly(" a ", "b");
    }

    @Test
    void should_readStaticItems_fromAListOrText_andDropBlankOnes() {
        assertThat(ForEachItems.staticItems(java.util.List.of("a", " ", 7, ""))).containsExactly("a", "7");
        assertThat(ForEachItems.staticItems("a\n\nb")).containsExactly("a", "b");
        assertThat(ForEachItems.staticItems(null)).isEmpty();
        assertThat(ForEachItems.staticItems(java.util.Map.of("a", 1))).isEmpty();
    }

    @Test
    void should_exposeTopLevelFields_ofAJsonObjectItem() {
        var fields = ForEachItems.fields("feature", "  {\"id\": \"auth\", \"desc\": \"Login\", \"n\": 3, \"ok\": true}  ");

        assertThat(fields).containsExactly(
                java.util.Map.entry("feature.id", "auth"),
                java.util.Map.entry("feature.desc", "Login"),
                java.util.Map.entry("feature.n", "3"),
                java.util.Map.entry("feature.ok", "true"));
    }

    @Test
    void should_keepNestedValuesAsJsonText_andNullAsEmpty() {
        var fields = ForEachItems.fields("f", "{\"tags\": [\"a\", \"b\"], \"meta\": {\"x\": 1}, \"none\": null}");

        assertThat(fields)
                .containsEntry("f.tags", "[\"a\",\"b\"]")
                .containsEntry("f.meta", "{\"x\":1}")
                .containsEntry("f.none", "");
    }

    @Test
    void should_offerNoFields_forNonObjectItems() {
        assertThat(ForEachItems.fields("f", "plain text")).isEmpty();
        assertThat(ForEachItems.fields("f", "[1, 2]")).isEmpty();
        assertThat(ForEachItems.fields("f", "42")).isEmpty();
    }

    @Test
    void should_offerNoFields_forBrokenJson() {
        assertThat(ForEachItems.fields("f", "{not json")).isEmpty();
        assertThat(ForEachItems.fields("f", "{\"a\": }")).isEmpty();
    }

    @Test
    void should_offerNoFields_forAnEmptyObject() {
        assertThat(ForEachItems.fields("f", "{}")).isEmpty();
    }
}
