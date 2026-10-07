package com.automation.exception;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodedExceptionTest {

    /** Minimal concrete subclass; CodedException itself is abstract. */
    private static final class Probe extends CodedException {
        Probe(ErrorCode code, String message) {
            super(code, message);
        }

        Probe(ErrorCode code, String message, Map<String, String> params) {
            super(code, message, params);
        }

        Probe(ErrorMessage errorMessage, Map<String, String> params) {
            super(errorMessage, params);
        }
    }

    @Test
    void should_keepFreeTextMessage_withoutParams_when_onlyCodeAndMessageGiven() {
        var ex = new Probe(ErrorCode.STATE_CONFLICT, "something is busy");

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCode()).isEqualTo(ErrorCode.STATE_CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("something is busy");
        assertThat(ex.getParams()).isNull();
        assertThat(ex.getErrorMessage()).isNull();
    }

    @Test
    void should_keepNullParams_when_paramsExplicitlyNull() {
        var ex = new Probe(ErrorCode.INVALID_ARGUMENT, "bad", null);

        assertThat(ex.getParams()).isNull();
        assertThat(ex.getErrorMessage()).isNull();
    }

    @Test
    void should_copyParamsDefensively_when_codeMessageAndParamsGiven() {
        Map<String, String> params = new HashMap<>(Map.of("name", "x"));
        var ex = new Probe(ErrorCode.INVALID_ARGUMENT, "Missing x", params);

        params.put("name", "changed");
        params.put("other", "y");

        assertThat(ex.getParams()).containsExactlyEntriesOf(Map.of("name", "x"));
        assertThatThrownBy(() -> ex.getParams().put("z", "1")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ex.getMessage()).isEqualTo("Missing x");
    }

    @Test
    void should_deriveCodeAndFormattedMessage_when_builtFromErrorMessage() {
        var ex = new Probe(ErrorMessage.INVALID_CRON, Map.of("expression", "* * *"));

        assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.INVALID_CRON);
        assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ex.getMessage()).isEqualTo("Invalid cron expression: * * *");
        assertThat(ex.getParams()).containsExactlyEntriesOf(Map.of("expression", "* * *"));
    }

    @Test
    void should_takeCodeOfTheErrorMessage_notAFixedOne() {
        var ex = new Probe(ErrorMessage.CHAIN_RUN_ACTIVE, Map.of());

        assertThat(ex.getCode()).isEqualTo(ErrorCode.STATE_CONFLICT);
        assertThat(ex.getParams()).isEmpty();
    }
}
