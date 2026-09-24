package com.automation.service.integration.providers.httpapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpApiTargetTest {

    @Test
    void parsesBaseUrlAndAuthHeader() {
        HttpApiTarget target = HttpApiTarget.parse(" https://api.example.com/v1/ ", "Authorization: Bearer abc123");

        assertThat(target.base().toString()).isEqualTo("https://api.example.com/v1/");
        assertThat(target.basePath()).isEqualTo("/v1");
        assertThat(target.label()).isEqualTo("api.example.com/v1");
        assertThat(target.authName()).isEqualTo("Authorization");
        assertThat(target.authValue()).isEqualTo("Bearer abc123");
        assertThat(target.toString()).doesNotContain("abc123");
    }

    @Test
    void emptyAuthHeaderMeansNoAuth_andRootBaseHasEmptyPath() {
        HttpApiTarget target = HttpApiTarget.parse("http://localhost:8080", "");

        assertThat(target.hasAuth()).isFalse();
        assertThat(target.basePath()).isEmpty();
        assertThat(target.label()).isEqualTo("localhost:8080");
    }

    @Test
    void schemeIsNormalizedToLowerCase() {
        assertThat(HttpApiTarget.parse("HTTPS://api.example.com", null).base().getScheme()).isEqualTo("https");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ftp://example.com", "file:///etc/passwd", "javascript:alert(1)",
            "https://user:pw@example.com", "https://example.com/v1?key=x", "https://example.com/#frag",
            "https:///nohost", "example.com/api", "https://exa mple.com", "https://example.com/a|b",
            "https://example.com/v1/../admin", "https://example.com/\nx"})
    void rejectsBadBaseUrls(String baseUrl) {
        assertThatThrownBy(() -> HttpApiTarget.parse(baseUrl, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer abc", ": value", "Bad Name: x", "Authorization:", "X-Key: a\r\nX-Evil: 1",
            "X-Key: a\nb", "Host: evil.example", "Content-Length: 5", "Transfer-Encoding: chunked",
            "Proxy-Authorization: x"})
    void rejectsBadAuthHeaders(String header) {
        assertThatThrownBy(() -> HttpApiTarget.parse("https://api.example.com", header))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void combinedCredential_splitsAtTheFirstBar_soTheHeaderValueMayContainOne() {
        HttpApiTarget target = HttpApiTarget.fromCredential("https://api.example.com|X-Api-Key: a|b");

        assertThat(target.authName()).isEqualTo("X-Api-Key");
        assertThat(target.authValue()).isEqualTo("a|b");
    }

    @Test
    void missingCredential_saysWhatToDo() {
        assertThatThrownBy(() -> HttpApiTarget.fromCredential(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No http-api connection configured");
    }

    @Test
    void redact_replacesTheAuthValueEverywhere() {
        HttpApiTarget target = HttpApiTarget.parse("https://api.example.com", "X-Api-Key: s3cr3t-value");

        assertThat(target.redact("{\"headers\":{\"X-Api-Key\":\"s3cr3t-value\"}} s3cr3t-value"))
                .isEqualTo("{\"headers\":{\"X-Api-Key\":\"***\"}} ***");
        assertThat(HttpApiTarget.parse("https://api.example.com", "").redact("x")).isEqualTo("x");
    }
}
