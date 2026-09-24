package com.automation.service.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerTokenCredentialTest {

    @Test
    void parse_splitsUrlAndTokenAndDropsTrailingSlashes() {
        ServerTokenCredential c = ServerTokenCredential.parse(" https://git.example.org// | tok123 ", null);

        assertThat(c.baseUrl()).isEqualTo("https://git.example.org");
        assertThat(c.token()).isEqualTo("tok123");
        assertThat(c.host()).isEqualTo("git.example.org");
    }

    @Test
    void parse_keepsPortAndSubpath() {
        assertThat(ServerTokenCredential.parse("HTTP://127.0.0.1:3300/gitea/|t", null).baseUrl())
                .isEqualTo("http://127.0.0.1:3300/gitea");
    }

    @Test
    void parse_emptyUrlFallsBackToDefault() {
        assertThat(ServerTokenCredential.parse("|t", "https://gitlab.com").baseUrl()).isEqualTo("https://gitlab.com");
        assertThat(ServerTokenCredential.parse("t", "https://gitlab.com").token()).isEqualTo("t");
    }

    @Test
    void parse_emptyUrlWithoutDefault_fails() {
        assertThatThrownBy(() -> ServerTokenCredential.parse("|t", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Server URL missing");
    }

    @Test
    void parse_missingToken_failsWithoutEchoingInput() {
        assertThatThrownBy(() -> ServerTokenCredential.parse("https://x.org|  ", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Access token missing");
        assertThatThrownBy(() -> ServerTokenCredential.parse(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://x.org", "file:///etc/passwd", "git.example.org", "javascript:alert(1)", "https://", "https://x.org/?a=b", "https://x.org/#f", "http://a b"})
    void normalize_rejectsNonHttpOrMalformedUrls(String url) {
        assertThatThrownBy(() -> ServerTokenCredential.normalizeServerUrl(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalize_rejectsUserInfoWithoutEchoingIt() {
        assertThatThrownBy(() -> ServerTokenCredential.normalizeServerUrl("https://bob:s3cret@git.example.org"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Server URL must not contain user credentials")
                .hasMessageNotContaining("s3cret");
    }

    @Test
    void errorsAndToString_neverContainTheToken() {
        assertThatThrownBy(() -> ServerTokenCredential.parse("ftp://x.org|supersecret", null))
                .hasMessageNotContaining("supersecret");
        assertThat(ServerTokenCredential.parse("https://x.org|supersecret", null).toString())
                .doesNotContain("supersecret").contains("https://x.org");
    }
}
