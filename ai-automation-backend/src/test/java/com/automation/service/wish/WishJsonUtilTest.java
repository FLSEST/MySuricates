package com.automation.service.wish;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WishJsonUtilTest {

    @Nested
    class ExtractJsonTests {

        @Test
        void nullInput_returnsEmptyObject() {
            assertThat(WishJsonUtil.extractJson(null)).isEqualTo("{}");
        }

        @Test
        void validJsonObject_returnsAsIs() {
            String json = "{\"key\": \"value\"}";
            assertThat(WishJsonUtil.extractJson(json)).isEqualTo(json);
        }

        @Test
        void jsonInMarkdownFences_stripsAndExtracts() {
            String input = "```json\n{\"a\": 1}\n```";
            assertThat(WishJsonUtil.extractJson(input)).isEqualTo("{\"a\": 1}");
        }

        @Test
        void jsonInMarkdownWithSurroundingText_stripsCorrectly() {
            String input = "Here is the result:\n```\n{\"b\": 2}\n```\nDone.";
            assertThat(WishJsonUtil.extractJson(input)).isEqualTo("{\"b\": 2}");
        }

        @Test
        void nestedObjects_extractsOuterBoundary() {
            String input = "text {\"a\":{\"b\":1}} more text";
            assertThat(WishJsonUtil.extractJson(input)).isEqualTo("{\"a\":{\"b\":1}}");
        }

        @Test
        void noJsonInText_throwsIllegalState() {
            assertThatThrownBy(() -> WishJsonUtil.extractJson("no json here"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not return valid JSON");
        }

        @Test
        void emptyString_throwsIllegalState() {
            assertThatThrownBy(() -> WishJsonUtil.extractJson(""))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class ParseJsonSafelyTests {

        @Test
        void validJson_returnsMap() {
            Map<String, Object> result = WishJsonUtil.parseJsonSafely("{\"key\": \"val\"}", "test");
            assertThat(result).containsEntry("key", "val");
        }

        @Test
        void jsonInMarkdownFences_parsesSuccessfully() {
            Map<String, Object> result = WishJsonUtil.parseJsonSafely("```json\n{\"key\": 42}\n```", "test");
            assertThat(result).containsEntry("key", 42);
        }

        @Test
        void nestedJson_parsesAllLevels() {
            String json = "{\"outer\": {\"inner\": true}, \"list\": [1, 2]}";
            Map<String, Object> result = WishJsonUtil.parseJsonSafely(json, "test");
            assertThat(result).containsKey("outer");
            assertThat(result).containsKey("list");
        }

        @Test
        void nullInput_parsesToEmptyMap() {
            Map<String, Object> result = WishJsonUtil.parseJsonSafely(null, "test");
            assertThat(result).isEmpty();
        }

        @Test
        void totallyBrokenJson_throwsIllegalState() {
            // "not json at all" has no { or }, so extractJson throws first
            assertThatThrownBy(() -> WishJsonUtil.parseJsonSafely("not json at all", "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not return valid JSON");
        }

        @Test
        void truncatedJson_withBraces_repairedSuccessfully() {
            // Truncated JSON that has both { and } but is malformed internally
            // extractJson will find the outer braces, then parse fails, repair kicks in
            String truncated = "{\"key\": \"value\", \"nested\": {\"a\": 1}";
            // This has { at 0 and } at position of the inner }, extractJson extracts it
            // The extracted part is: {"key": "value", "nested": {"a": 1}
            // which is valid JSON for the inner part, so this should parse
            Map<String, Object> result = WishJsonUtil.parseJsonSafely(truncated, "test");
            assertThat(result).containsKey("key");
        }
    }
}
