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
     * ac639b39: the shared number path (IntegrationParams) treats blank like missing, as every other
     * provider does. Until then Jira kept the pre-split behaviour — a bare NumberFormatException.
     */
    @Test
    void paramInt_blankString_meansTheDefault() {
        assertThat(JiraParams.paramInt(Map.of("n", " "), "n", 7)).isEqualTo(7);
    }

    /** ac639b39: a JSON number is read like its text — 1.5 is not cut to 1, nothing wraps around. */
    @Test
    void paramInt_rejectsDecimalsAndValuesBeyondInt() {
        for (Object bad : new Object[] {1.5, 5.0, 3_000_000_000L, "abc"}) {
            assertThatThrownBy(() -> JiraParams.paramInt(Map.of("maxResults", bad), "maxResults", 0))
                    .as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Parameter 'maxResults' must be a number");
        }
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
