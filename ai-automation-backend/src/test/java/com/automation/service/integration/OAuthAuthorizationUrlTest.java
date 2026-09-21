package com.automation.service.integration;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthAuthorizationUrlTest {

    private static final OAuthConfig CONFIG = new OAuthConfig(
            "https://auth.example.com/authorize", "https://auth.example.com/token",
            List.of("read", "profile"), List.of("write", "admin"),
            "X_ID", "X_SECRET", Map.of());

    private static Map<String, String> query(String url) {
        String raw = URI.create(url).getRawQuery();
        return Arrays.stream(raw.split("&"))
                .map(p -> p.split("=", 2))
                .collect(Collectors.toMap(
                        p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                        p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8),
                        (a, b) -> b, LinkedHashMap::new));
    }

    @Test
    void build_encodesEveryParameter_andKeepsFixedOrder() {
        String url = OAuthAuthorizationUrl.build(CONFIG, "id with space&x=1",
                "http://localhost:9090/cb?a=b", null, "st/at+e");

        assertThat(url).startsWith("https://auth.example.com/authorize?client_id=id+with+space%26x%3D1"
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A9090%2Fcb%3Fa%3Db&response_type=code&scope=read+profile"
                + "&state=st%2Fat%2Be");
        assertThat(query(url))
                .containsEntry("client_id", "id with space&x=1")
                .containsEntry("redirect_uri", "http://localhost:9090/cb?a=b")
                .containsEntry("state", "st/at+e")
                .containsOnlyKeys("client_id", "redirect_uri", "response_type", "scope", "state");
    }

    @Test
    void build_addsOnlyAdditionalScopesThatAreDeclaredOptional() {
        String url = OAuthAuthorizationUrl.build(CONFIG, "cid", "https://app/cb",
                List.of("admin", "delete_everything", "write"), "s");

        assertThat(query(url).get("scope")).isEqualTo("read profile admin write");
    }

    @Test
    void build_appendsEncodedExtraAuthParams() {
        OAuthConfig config = new OAuthConfig("https://auth.example.com/authorize", "t",
                List.of("s1"), "X_ID", "X_SECRET", Map.of("prompt", "consent select_account"));

        String url = OAuthAuthorizationUrl.build(config, "cid", "https://app/cb", List.of(), "s");

        assertThat(url).endsWith("&prompt=consent+select_account");
        assertThat(query(url)).containsEntry("prompt", "consent select_account");
    }

    @Test
    void build_doesNotMutateTheConfiguredDefaultScopes() {
        List<String> defaults = new java.util.ArrayList<>(List.of("read"));
        OAuthConfig config = new OAuthConfig("https://a/authorize", "t", defaults, List.of("write"),
                "X_ID", "X_SECRET", Map.of());

        OAuthAuthorizationUrl.build(config, "cid", "https://app/cb", List.of("write"), "s");

        assertThat(defaults).containsExactly("read");
    }
}
