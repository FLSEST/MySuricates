package com.automation.service.integration.providers.trello;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrelloParamsTest {

    @Test
    void paramString_returnsValueOrDefault() {
        assertThat(TrelloParams.paramString(Map.of("filter", "closed"), "filter", "open")).isEqualTo("closed");
        assertThat(TrelloParams.paramString(Map.of(), "filter", "open")).isEqualTo("open");
    }

    @Test
    void paramInt_acceptsNumbersAndNumericStrings_elseDefault() {
        assertThat(TrelloParams.paramInt(Map.of("cards_limit", 10), "cards_limit", 5)).isEqualTo(10);
        assertThat(TrelloParams.paramInt(Map.of("cards_limit", "7"), "cards_limit", 5)).isEqualTo(7);
        assertThat(TrelloParams.paramInt(Map.of(), "cards_limit", 5)).isEqualTo(5);
    }

    @Test
    void requireParam_missing_throwsWithKey() {
        assertThatThrownBy(() -> TrelloParams.requireParam(Map.of(), "cardId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Required parameter missing: cardId");
    }

    @Test
    void urlEncode_encodesSpaceAndAmpersand() {
        assertThat(TrelloParams.urlEncode("a b&c")).isEqualTo("a+b%26c");
    }

    /** ac639b39: a JSON number is read like its text; blank means the default, as elsewhere. */
    @Test
    void paramInt_rejectsDecimalsAndValuesBeyondInt_blankIsTheDefault() {
        assertThat(TrelloParams.paramInt(Map.of("cards_limit", " "), "cards_limit", 5)).isEqualTo(5);
        for (Object bad : new Object[] {1.5, 5.0, 3_000_000_000L}) {
            assertThatThrownBy(() -> TrelloParams.paramInt(Map.of("cards_limit", bad), "cards_limit", 5))
                    .as(String.valueOf(bad))
                    .hasMessage("Parameter 'cards_limit' must be a number");
        }
    }
}
