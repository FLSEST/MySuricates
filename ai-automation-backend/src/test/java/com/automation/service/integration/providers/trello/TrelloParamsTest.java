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
}
