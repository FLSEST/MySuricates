package com.automation.service.integration.providers;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedError;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TODO ac639b39: one number path for the integration params — a number is read like its text. */
class IntegrationParamsTest {

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put(key, value);
        return params;
    }

    @Test
    void wholeNumbers_asJsonNumberOrAsText_areTheSameValue() {
        assertThat(IntegrationParams.intParam(params("limit", 7), "limit", 1)).isEqualTo(7);
        assertThat(IntegrationParams.intParam(params("limit", 7L), "limit", 1)).isEqualTo(7);
        assertThat(IntegrationParams.intParam(params("limit", BigInteger.valueOf(7)), "limit", 1)).isEqualTo(7);
        assertThat(IntegrationParams.intParam(params("limit", " 7 "), "limit", 1)).isEqualTo(7);
        assertThat(IntegrationParams.intParam(params("limit", -3), "limit", 1)).isEqualTo(-3);
    }

    @Test
    void missingOrBlank_meansTheDefault() {
        assertThat(IntegrationParams.intParam(Map.of(), "limit", 5)).isEqualTo(5);
        assertThat(IntegrationParams.intParam(params("limit", null), "limit", 5)).isEqualTo(5);
        assertThat(IntegrationParams.intParam(params("limit", "  "), "limit", 5)).isEqualTo(5);
        assertThat(IntegrationParams.optionalInt(null, "limit")).isNull();
        assertThat(IntegrationParams.optionalLong(" ", "offset")).isNull();
    }

    /** Before: 1.5 became 1 and 5.0 became 5 through intValue(), while the string "1.5" was rejected. */
    @Test
    void decimals_failLikeTheirText_insteadOfBeingCutOff() {
        for (Object bad : new Object[] {1.5, 5.0, 1.5f, "1.5", "5.0", new BigDecimal("5.0")}) {
            assertThatThrownBy(() -> IntegrationParams.intParam(params("limit", bad), "limit", 1))
                    .as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Parameter 'limit' must be a number");
        }
    }

    /** Before: 3000000000 became -1294967296 and 2^32 + 5 became 5 through intValue(). */
    @Test
    void valuesBeyondTheTargetType_fail_insteadOfWrappingAround() {
        for (Object bad : new Object[] {3_000_000_000L, BigInteger.ONE.shiftLeft(32).add(BigInteger.valueOf(5)), "2147483648"}) {
            assertThatThrownBy(() -> IntegrationParams.optionalInt(bad, "limit"))
                    .as(String.valueOf(bad))
                    .hasMessage("Parameter 'limit' must be a number");
        }
        assertThat(IntegrationParams.optionalLong(3_000_000_000L, "offset")).isEqualTo(3_000_000_000L);
        assertThatThrownBy(() -> IntegrationParams.optionalLong(new BigInteger("9223372036854775808"), "offset"))
                .hasMessage("Parameter 'offset' must be a number");
    }

    @Test
    void anythingButDigits_fails() {
        for (Object bad : new Object[] {"zwei", true, List.of(1), Map.of("n", 1)}) {
            assertThatThrownBy(() -> IntegrationParams.optionalInt(bad, "n"))
                    .as(String.valueOf(bad))
                    .hasMessage("Parameter 'n' must be a number");
        }
    }

    @Test
    void parseInt_leavesTheMessageToTheCaller() {
        assertThat(IntegrationParams.parseInt(" 4 ")).isEqualTo(4);
        assertThat(IntegrationParams.parseInt("")).isNull();
        assertThatThrownBy(() -> IntegrationParams.parseInt(4.5)).isInstanceOf(NumberFormatException.class);
    }

    /** 2b73ff48: the action test in the integration panel shows this as a toast — in the UI's language. */
    @Test
    void missing_keepsTheTextAndCarriesAMessageKey() {
        IllegalArgumentException missing = IntegrationParams.missing("chat_id");

        assertThat(missing).hasMessage("Required parameter missing: chat_id")
                .isInstanceOfSatisfying(LocalizedError.class, error -> {
                    assertThat(error.errorMessage()).isEqualTo(ErrorMessage.INTEGRATION_PARAMETER_MISSING);
                    assertThat(error.params()).isEqualTo(Map.of("name", "chat_id"));
                });
    }

    @Test
    void paramString_defaultOnlyWhenMissing_blankAndNumbersKept() {
        assertThat(IntegrationParams.paramString(Map.of(), "state", "open")).isEqualTo("open");
        assertThat(IntegrationParams.paramString(params("state", null), "state", "open")).isEqualTo("open");
        assertThat(IntegrationParams.paramString(params("state", "  "), "state", "open")).isEqualTo("  ");
        assertThat(IntegrationParams.paramString(params("state", 7), "state", "open")).isEqualTo("7");
    }

    @Test
    void requireParam_failsOnlyWhenMissing_andKeepsTheValueAsIs() {
        assertThat(IntegrationParams.requireParam(params("q", " a b "), "q")).isEqualTo(" a b ");
        assertThat(IntegrationParams.requireParam(params("q", ""), "q")).isEmpty();
        assertThat(IntegrationParams.requireParam(params("q", 42), "q")).isEqualTo("42");
        assertThatThrownBy(() -> IntegrationParams.requireParam(params("q", null), "q"))
                .isInstanceOf(LocalizedError.class)
                .hasMessage("Required parameter missing: q");
    }

    @Test
    void requireNonBlank_failsWhenMissingOrBlank_andDoesNotTrim() {
        assertThat(IntegrationParams.requireNonBlank(params("q", " a "), "q")).isEqualTo(" a ");
        for (Object bad : new Object[] {null, "", "   "}) {
            assertThatThrownBy(() -> IntegrationParams.requireNonBlank(params("q", bad), "q"))
                    .as(String.valueOf(bad))
                    .isInstanceOf(LocalizedError.class)
                    .hasMessage("Required parameter missing: q");
        }
    }

    @Test
    void urlEncode_utf8FormEncoding() {
        assertThat(IntegrationParams.urlEncode("a b&c=ä/ü")).isEqualTo("a+b%26c%3D%C3%A4%2F%C3%BC");
    }
}
