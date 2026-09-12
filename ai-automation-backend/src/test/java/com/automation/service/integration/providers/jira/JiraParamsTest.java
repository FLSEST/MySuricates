package com.automation.service.integration.providers.jira;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JiraParamsTest {

    // --- paramInt (migrated from JiraProviderTest, same assertions) ---

    @Test
    void paramInt_parsesNumber() {
        assertThat(JiraParams.paramInt(Map.of("n", 42), "n", 0)).isEqualTo(42);
    }

    @Test
    void paramInt_parsesStringNumber() {
        assertThat(JiraParams.paramInt(Map.of("n", "42"), "n", 0)).isEqualTo(42);
    }

    @Test
    void paramInt_returnsDefaultWhenMissing() {
        assertThat(JiraParams.paramInt(Map.of(), "n", 99)).isEqualTo(99);
    }

    /**
     * Documents the intentional divergence from {@code GitHubParams.paramInt}: a blank string is
     * NOT special-cased here, so it falls through to {@link Integer#parseInt} and throws — this is
     * the pre-split {@code JiraProvider} behavior, kept as-is by the brief.
     */
    @Test
    void paramInt_blankString_throwsNumberFormatException() {
        assertThatThrownBy(() -> JiraParams.paramInt(Map.of("n", " "), "n", 0))
                .isInstanceOf(NumberFormatException.class);
    }

    // --- paramString (migrated from JiraProviderTest, same assertions) ---

    @Test
    void paramString_returnsDefaultWhenMissing() {
        assertThat(JiraParams.paramString(Map.of(), "k", "default")).isEqualTo("default");
    }

    @Test
    void paramString_returnsValueAsString() {
        assertThat(JiraParams.paramString(Map.of("k", 42), "k", "default")).isEqualTo("42");
    }

    // --- buildAdfText (migrated from JiraProviderTest, same assertions) ---

    @Test
    @SuppressWarnings("unchecked")
    void buildAdfText_wrapsTextInAdfDocument() {
        Map<String, Object> adf = JiraParams.buildAdfText("Hello World");

        assertThat(adf).containsEntry("type", "doc").containsEntry("version", 1);
        List<Map<String, Object>> content = (List<Map<String, Object>>) adf.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("type", "paragraph");
        List<Map<String, Object>> paragraphContent =
                (List<Map<String, Object>>) content.get(0).get("content");
        assertThat(paragraphContent).hasSize(1);
        assertThat(paragraphContent.get(0))
                .containsEntry("type", "text")
                .containsEntry("text", "Hello World");
    }
}
