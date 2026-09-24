package com.automation.service.integration.providers.ntfy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NtfyParamsTest {

    @Test
    void parseConnection_emptyFieldsMeanNtfyShAnonymous() {
        assertThat(NtfyParams.parseConnection("|")).isEqualTo(new NtfyParams.Connection("https://ntfy.sh", null));
        assertThat(NtfyParams.parseConnection(null)).isEqualTo(new NtfyParams.Connection("https://ntfy.sh", null));
        assertThat(NtfyParams.parseConnection(" | ").hasToken()).isFalse();
    }

    @Test
    void parseConnection_keepsServerAndTokenAndStripsTrailingSlash() {
        NtfyParams.Connection c = NtfyParams.parseConnection("https://ntfy.example.org/|tk_abc");
        assertThat(c.baseUrl()).isEqualTo("https://ntfy.example.org");
        assertThat(c.token()).isEqualTo("tk_abc");
    }

    @Test
    void parseConnection_tokenMayContainPipe() {
        assertThat(NtfyParams.parseConnection("http://127.0.0.1:8095|a|b").token()).isEqualTo("a|b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://ntfy.example.org", "file:///etc/passwd", "ntfy.example.org",
            "https://ntfy.example.org/?x=1", "https://user:pw@ntfy.example.org", "http://"})
    void normalizeServer_rejectsNonHttpOrOddUrls(String url) {
        assertThatThrownBy(() -> NtfyParams.normalizeServer(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alerts", "my-topic_2", "A"})
    void requireTopic_acceptsValidNames(String topic) {
        assertThat(NtfyParams.requireTopic(Map.of("topic", topic))).isEqualTo(topic);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "a/b", "../x", "top?ic", "äöü"})
    void requireTopic_rejectsInvalidNames(String topic) {
        assertThatThrownBy(() -> NtfyParams.requireTopic(Map.of("topic", topic)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void requireTopic_rejectsTooLongAndMissing() {
        assertThatThrownBy(() -> NtfyParams.requireTopic(Map.of("topic", "x".repeat(65))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NtfyParams.requireTopic(Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void priority_mapsNamesAndNumbers() {
        assertThat(NtfyParams.priority("min")).isEqualTo(1);
        assertThat(NtfyParams.priority("LOW")).isEqualTo(2);
        assertThat(NtfyParams.priority("default")).isEqualTo(3);
        assertThat(NtfyParams.priority("high")).isEqualTo(4);
        assertThat(NtfyParams.priority("max")).isEqualTo(5);
        assertThat(NtfyParams.priority("urgent")).isEqualTo(5);
        assertThat(NtfyParams.priority(4)).isEqualTo(4);
        assertThat(NtfyParams.priority("2")).isEqualTo(2);
        assertThat(NtfyParams.priority(null)).isNull();
        assertThat(NtfyParams.priority(" ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "2.5", "loud"})
    void priority_rejectsOutOfRange(String value) {
        assertThatThrownBy(() -> NtfyParams.priority(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority");
    }

    @Test
    void publishBody_containsOnlyGivenFields() {
        Map<String, Object> params = new HashMap<>();
        params.put("topic", "alerts");
        params.put("message", "Build failed");
        params.put("title", "CI");
        params.put("priority", "high");
        params.put("tags", "warning, skull ,");
        params.put("click", "https://example.org/run/1");
        params.put("markdown", "true");

        Map<String, Object> body = NtfyParams.publishBody(params);

        assertThat(body).containsExactly(
                Map.entry("topic", "alerts"),
                Map.entry("message", "Build failed"),
                Map.entry("title", "CI"),
                Map.entry("priority", 4),
                Map.entry("tags", List.of("warning", "skull")),
                Map.entry("click", "https://example.org/run/1"),
                Map.entry("markdown", true));
    }

    @Test
    void publishBody_minimal() {
        assertThat(NtfyParams.publishBody(Map.of("topic", "t", "message", "hi")))
                .containsOnlyKeys("topic", "message");
    }

    @Test
    void publishBody_requiresMessage() {
        assertThatThrownBy(() -> NtfyParams.publishBody(Map.of("topic", "t", "message", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    @Test
    void tags_acceptsList() {
        assertThat(NtfyParams.tags(List.of("a", " b ", ""))).containsExactly("a", "b");
    }
}
