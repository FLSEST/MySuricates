package com.automation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeOAuthTokenTest {

    @Test
    void aLongTokenWithoutSpacesIsPlausible() {
        assertThat(ClaudeOAuthToken.isPlausible("sk-ant-oat01-" + "a".repeat(80))).isTrue();
        assertThat(ClaudeOAuthToken.isPlausible("x".repeat(ClaudeOAuthToken.MIN_LENGTH))).isTrue();
    }

    /** What copied instructions leave behind in ai.env — none of it may count as a login. */
    @ParameterizedTest
    @ValueSource(strings = {"<token>", "…", "...", "DEIN_TOKEN", "sk-ant-oat-secret-value",
            "<sk-ant-oat01-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa>",
            "paste the token from claude setup-token here please"})
    void placeholdersAreNot(String value) {
        assertThat(ClaudeOAuthToken.isPlausible(value)).isFalse();
    }

    @Test
    void emptyNullAndOneShortOfTheMinimumAreNot() {
        assertThat(ClaudeOAuthToken.isPlausible(null)).isFalse();
        assertThat(ClaudeOAuthToken.isPlausible("")).isFalse();
        assertThat(ClaudeOAuthToken.isPlausible("x".repeat(ClaudeOAuthToken.MIN_LENGTH - 1))).isFalse();
    }

    @Test
    void aTrailingNewlineOrCarriageReturnIsWhitespace() {
        String token = "sk-ant-oat01-" + "a".repeat(80);
        assertThat(ClaudeOAuthToken.isPlausible(token + "\r")).isFalse();
        assertThat(ClaudeOAuthToken.isPlausible(token + "\n")).isFalse();
    }
}
