package com.automation.service.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantToolPayloadsTest {

    @Test
    void shorten_nullIsQuestionMark() {
        assertThat(AssistantToolPayloads.shorten(null)).isEqualTo("?");
    }

    @Test
    void shorten_upToEightCharsUnchanged() {
        assertThat(AssistantToolPayloads.shorten("12345678")).isEqualTo("12345678");
        assertThat(AssistantToolPayloads.shorten(42)).isEqualTo("42");
    }

    @Test
    void shorten_longerThanEight_cutWithEllipsis() {
        assertThat(AssistantToolPayloads.shorten("123456789")).isEqualTo("12345678…");
    }

    @Test
    void sanitizeJson_nullOrEmpty_passThrough() {
        assertThat(AssistantToolPayloads.sanitizeJson(null)).isNull();
        assertThat(AssistantToolPayloads.sanitizeJson("")).isEmpty();
    }

    @Test
    void sanitizeJson_replacesAllTenFullwidthCharacters() {
        String fullwidth = "｛＂a＂：［1，2］；“b”｝";

        assertThat(AssistantToolPayloads.sanitizeJson(fullwidth)).isEqualTo("{\"a\":[1,2];\"b\"}");
    }

    @Test
    void sanitizeJson_asciiUntouched() {
        String json = "{\"title\":\"x\",\"tags\":[\"a\"]}";
        assertThat(AssistantToolPayloads.sanitizeJson(json)).isEqualTo(json);
    }
}
