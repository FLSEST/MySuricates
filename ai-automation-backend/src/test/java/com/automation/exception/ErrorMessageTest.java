package com.automation.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessageTest {

    @Test
    void formatsTheEnglishTemplateWithItsParams() {
        assertThat(ErrorMessage.INVALID_CRON.format(Map.of("expression", "* * *"))).isEqualTo("Invalid cron expression: * * *");
    }

    @Test
    void insertsValuesLiterally_evenRegexReplacementCharacters() {
        assertThat(ErrorMessage.RESTRICTED_PATH.format(Map.of("path", "/etc/$1\\x"))).isEqualTo(
                "Path points to a restricted system directory: /etc/$1\\x");
    }

    @Test
    void keepsAPlaceholderWithoutValue() {
        assertThat(ErrorMessage.UNKNOWN_SETTING.format(Map.of())).isEqualTo("Unknown setting: {key}");
        assertThat(ErrorMessage.UNKNOWN_SETTING.format(null)).isEqualTo("Unknown setting: {key}");
    }

    @Test
    void localizedExceptionsStayTheJdkTypesCallersCatch() {
        var argument = new LocalizedArgumentException(ErrorMessage.UNKNOWN_SETTING, Map.of("key", "x"));
        var state = new LocalizedStateException(ErrorMessage.TEST_INSTANCE_NOT_RUNNING);
        var notFound = new ElementNotFoundException("Chain", "unknown");

        assertThat(argument).isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown setting: x");
        assertThat(state).isInstanceOf(IllegalStateException.class)
                .hasMessage("Test instance is not running or not healthy. Start it first.");
        // The text the NoSuchElementException carried before — callers and tests compare it
        assertThat(notFound).isInstanceOf(NoSuchElementException.class).hasMessage("Chain not found: unknown");
        assertThat(notFound.params()).containsExactlyInAnyOrderEntriesOf(Map.of("resource", "Chain", "id", "unknown"));
    }

    @Test
    void errorResponseFrom_usesTheMessageKeyWhenThereIsOne_elseTheFallbackCode() {
        var localized = ErrorResponse.from(new LocalizedArgumentException(ErrorMessage.OAUTH_STATE_INVALID), ErrorCode.INVALID_ARGUMENT);
        var plain = ErrorResponse.from(new IllegalArgumentException("something else"), ErrorCode.INVALID_ARGUMENT);

        assertThat(localized.getMessageKey()).isEqualTo("OAUTH_STATE_INVALID");
        assertThat(localized.getParams()).isEmpty();
        assertThat(localized.getError()).isEqualTo("Invalid or expired OAuth state");
        assertThat(plain.getMessageKey()).isNull();
        assertThat(plain.getParams()).isNull();
        assertThat(plain.getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(plain.getError()).isEqualTo("something else");
    }
}
