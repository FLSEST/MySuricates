package com.automation.service.integration.providers.slack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackParamsTest {

    @Test
    void extractToken_takesPartAfterFirstPipe() {
        assertThat(SlackParams.extractToken("bot|xoxb-1")).isEqualTo("xoxb-1");
        assertThat(SlackParams.extractToken("xoxb-1")).isEqualTo("xoxb-1");
        assertThat(SlackParams.extractToken(null)).isNull();
    }

    @Test
    void requireAndOptionalString_trimAndTreatBlankAsMissing() {
        assertThat(SlackParams.requireParam(Map.of("k", "  v "), "k")).isEqualTo("v");
        assertThatThrownBy(() -> SlackParams.requireParam(Map.of("k", " "), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k");
        assertThatThrownBy(() -> SlackParams.requireParam(Map.of(), "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SlackParams.optionalString(Map.of("k", " v "), "k")).isEqualTo("v");
        assertThat(SlackParams.optionalString(Map.of("k", ""), "k")).isNull();
        assertThat(SlackParams.optionalString(Map.of(), "k")).isNull();
    }

    @Test
    void boundedInt_defaultsAndEnforcesRange() {
        assertThat(SlackParams.boundedInt(Map.of(), "limit", 1, 1000, 100)).isEqualTo(100);
        assertThat(SlackParams.boundedInt(Map.of("limit", "25"), "limit", 1, 1000, 100)).isEqualTo(25);
        assertThat(SlackParams.boundedInt(Map.of("limit", 1000), "limit", 1, 1000, 100)).isEqualTo(1000);
        assertThatThrownBy(() -> SlackParams.boundedInt(Map.of("limit", 0), "limit", 1, 1000, 100))
                .hasMessage("Parameter 'limit' must be between 1 and 1000");
        assertThatThrownBy(() -> SlackParams.boundedInt(Map.of("limit", 1001), "limit", 1, 1000, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlackParams.boundedInt(Map.of("limit", "abc"), "limit", 1, 1000, 100))
                .hasMessageContaining("'limit'");
    }

    @Test
    void timestamp_acceptsSlackFormatOnly() {
        assertThat(SlackParams.timestamp(Map.of("oldest", "1503435956.000247"), "oldest")).isEqualTo("1503435956.000247");
        assertThat(SlackParams.timestamp(Map.of("oldest", "1503435956"), "oldest")).isEqualTo("1503435956");
        assertThat(SlackParams.timestamp(Map.of(), "oldest")).isNull();
        for (String bad : new String[]{"yesterday", "1.2.3", "-5", "1503435956."}) {
            assertThatThrownBy(() -> SlackParams.timestamp(Map.of("thread_ts", bad), "thread_ts"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thread_ts");
        }
    }

    @Test
    void channelTypes_defaultsNormalizesAndRejectsUnknown() {
        assertThat(SlackParams.channelTypes(Map.of())).isEqualTo("public_channel");
        assertThat(SlackParams.channelTypes(Map.of("types", " public_channel , im "))).isEqualTo("public_channel,im");
        assertThat(SlackParams.channelTypes(Map.of("types", ",,"))).isEqualTo("public_channel");
        assertThatThrownBy(() -> SlackParams.channelTypes(Map.of("types", "public_channel,dm")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("types");
    }

    @Test
    void parseScopeHeader_convertsCommasToSpaces() {
        assertThat(SlackParams.parseScopeHeader("chat:write,channels:read")).isEqualTo("chat:write channels:read");
        assertThat(SlackParams.parseScopeHeader(" a , b ")).isEqualTo("a b");
        assertThat(SlackParams.parseScopeHeader(null)).isNull();
        assertThat(SlackParams.parseScopeHeader("  ")).isNull();
    }
}
