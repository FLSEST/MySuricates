package com.automation.service.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthConfigTest {

    // --- Canonical constructor (all 7 fields) ---

    @Test
    void should_setAllFields_when_constructedWithCanonicalConstructor() {
        var scopes = List.of("read", "write");
        var optionalScopes = List.of("admin");
        var extraParams = Map.of("access_type", "offline");

        var config = new OAuthConfig(
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                scopes,
                optionalScopes,
                "CLIENT_ID",
                "CLIENT_SECRET",
                extraParams
        );

        assertThat(config.authorizationUrl()).isEqualTo("https://auth.example.com/authorize");
        assertThat(config.tokenUrl()).isEqualTo("https://auth.example.com/token");
        assertThat(config.defaultScopes()).containsExactly("read", "write");
        assertThat(config.optionalScopes()).containsExactly("admin");
        assertThat(config.clientIdEnvVar()).isEqualTo("CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("CLIENT_SECRET");
        assertThat(config.extraAuthParams()).containsEntry("access_type", "offline");
    }

    // --- Constructor without optional scopes ---

    @Test
    void should_setOptionalScopesToEmpty_when_constructedWithoutOptionalScopes() {
        var scopes = List.of("email", "profile");
        var extraParams = Map.of("prompt", "consent");

        var config = new OAuthConfig(
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                scopes,
                "CLIENT_ID",
                "CLIENT_SECRET",
                extraParams
        );

        assertThat(config.authorizationUrl()).isEqualTo("https://auth.example.com/authorize");
        assertThat(config.tokenUrl()).isEqualTo("https://auth.example.com/token");
        assertThat(config.defaultScopes()).containsExactly("email", "profile");
        assertThat(config.optionalScopes()).isEmpty();
        assertThat(config.clientIdEnvVar()).isEqualTo("CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("CLIENT_SECRET");
        assertThat(config.extraAuthParams()).containsEntry("prompt", "consent");
    }

    // --- Minimal constructor (without optional scopes and extra params) ---

    @Test
    void should_setDefaultsForOptionalFields_when_constructedWithMinimalConstructor() {
        var scopes = List.of("openid");

        var config = new OAuthConfig(
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                scopes,
                "CLIENT_ID",
                "CLIENT_SECRET"
        );

        assertThat(config.authorizationUrl()).isEqualTo("https://auth.example.com/authorize");
        assertThat(config.tokenUrl()).isEqualTo("https://auth.example.com/token");
        assertThat(config.defaultScopes()).containsExactly("openid");
        assertThat(config.optionalScopes()).isEmpty();
        assertThat(config.clientIdEnvVar()).isEqualTo("CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("CLIENT_SECRET");
        assertThat(config.extraAuthParams()).isEmpty();
    }

    // --- Edge cases ---

    @Test
    void should_acceptEmptyScopes_when_constructedWithEmptyList() {
        var config = new OAuthConfig(
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                List.of(),
                "CLIENT_ID",
                "CLIENT_SECRET"
        );

        assertThat(config.defaultScopes()).isEmpty();
        assertThat(config.optionalScopes()).isEmpty();
        assertThat(config.extraAuthParams()).isEmpty();
    }

    @Test
    void should_acceptNullUrls_when_constructedWithNulls() {
        var config = new OAuthConfig(null, null, null, null, null);

        assertThat(config.authorizationUrl()).isNull();
        assertThat(config.tokenUrl()).isNull();
        assertThat(config.defaultScopes()).isNull();
        assertThat(config.optionalScopes()).isEmpty();
        assertThat(config.clientIdEnvVar()).isNull();
        assertThat(config.clientSecretEnvVar()).isNull();
        assertThat(config.extraAuthParams()).isEmpty();
    }

    @Test
    void should_preserveMultipleExtraParams_when_constructedWithComplexMap() {
        var extraParams = Map.of(
                "access_type", "offline",
                "prompt", "consent",
                "include_granted_scopes", "true"
        );

        var config = new OAuthConfig(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of("openid", "email"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                extraParams
        );

        assertThat(config.extraAuthParams()).hasSize(3);
        assertThat(config.extraAuthParams()).containsEntry("access_type", "offline");
        assertThat(config.extraAuthParams()).containsEntry("prompt", "consent");
        assertThat(config.extraAuthParams()).containsEntry("include_granted_scopes", "true");
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new OAuthConfig("https://auth/authorize", "https://auth/token",
                List.of("read"), "CID", "CSEC");
        var b = new OAuthConfig("https://auth/authorize", "https://auth/token",
                List.of("read"), "CID", "CSEC");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentAuthorizationUrl() {
        var a = new OAuthConfig("https://auth-a/authorize", "https://auth/token",
                List.of("read"), "CID", "CSEC");
        var b = new OAuthConfig("https://auth-b/authorize", "https://auth/token",
                List.of("read"), "CID", "CSEC");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentScopes() {
        var a = new OAuthConfig("https://auth/authorize", "https://auth/token",
                List.of("read"), "CID", "CSEC");
        var b = new OAuthConfig("https://auth/authorize", "https://auth/token",
                List.of("write"), "CID", "CSEC");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentOptionalScopes() {
        var a = new OAuthConfig("https://auth/authorize", "https://auth/token",
                List.of("read"), List.of("admin"), "CID", "CSEC", Map.of());
        var b = new OAuthConfig("https://auth/authorize", "https://auth/token",
                List.of("read"), List.of("user"), "CID", "CSEC", Map.of());

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var config = new OAuthConfig(
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                List.of("openid"),
                "MY_CLIENT_ID",
                "MY_CLIENT_SECRET"
        );
        var str = config.toString();

        assertThat(str).contains("https://auth.example.com/authorize");
        assertThat(str).contains("https://auth.example.com/token");
        assertThat(str).contains("openid");
        assertThat(str).contains("MY_CLIENT_ID");
        assertThat(str).contains("MY_CLIENT_SECRET");
    }
}
