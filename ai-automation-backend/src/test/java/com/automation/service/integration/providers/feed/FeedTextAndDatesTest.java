package com.automation.service.integration.providers.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FeedTextAndDatesTest {

    // --- FeedText ---

    @Test
    void plain_stripsTagsScriptsStylesAndComments() {
        String html = "<p>Hallo <b>Welt</b></p><script>alert('x')</script><style>p{}</style><!-- c -->"
                + "<SCRIPT type=\"x\">evil()</SCRIPT>Ende";
        assertThat(FeedText.plain(html, 0)).isEqualTo("Hallo Welt Ende");
    }

    @Test
    void plain_inlineTagsJoinWithoutGapBlockTagsSeparate() {
        assertThat(FeedText.plain("(codename <em>trixie</em>). Zeile<br/>zwei<p>drei</p><a href=\"x\">Link</a>!", 0))
                .isEqualTo("(codename trixie). Zeile zwei drei Link!");
    }

    @Test
    void plain_decodesEntities() {
        assertThat(FeedText.plain("Tom &amp; Jerry &lt;3 &#228; &#x1F600; &auml;&nbsp;x &unknown; &", 0))
                .isEqualTo("Tom & Jerry <3 ä 😀 ä x &unknown; &");
    }

    @Test
    void plain_keepsLoneLessThanAsText() {
        assertThat(FeedText.plain("a < b", 0)).isEqualTo("a < b");
    }

    @Test
    void plain_decodedMarkupStaysText() {
        // escaped markup in the source is content, not a tag
        assertThat(FeedText.plain("&lt;b&gt;fett&lt;/b&gt;", 0)).isEqualTo("<b>fett</b>");
    }

    @Test
    void plain_truncatesAtWordBoundary() {
        assertThat(FeedText.plain("eins zwei drei vier fünf", 12)).isEqualTo("eins zwei…");
        assertThat(FeedText.plain("kurz", 12)).isEqualTo("kurz");
        assertThat(FeedText.plain(null, 12)).isNull();
    }

    @Test
    void plain_unclosedScriptSwallowsRestButStaysLinear() {
        String hostile = "<script>".repeat(50_000);
        long start = System.nanoTime();
        assertThat(FeedText.plain("vorher " + hostile, 0)).isEqualTo("vorher");
        assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
    }

    // --- FeedDates ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Wed, 24 Sep 2026 10:15:30 GMT        | 2026-09-24T10:15:30Z",
            "Wed, 24 Sep 2026 10:15:30 +0200      | 2026-09-24T08:15:30Z",
            "24 Sep 2026 10:15 EST                | 2026-09-24T15:15:00Z",
            "Wed, 4 Sep 26 10:15:30 -0000         | 2026-09-04T10:15:30Z",
            "Thu,  24 September 2026 10:15:30 UT  | 2026-09-24T10:15:30Z",
            "Wed, 24 Sep 2026 10:15:30 +02:00     | 2026-09-24T08:15:30Z",
            "Wed, 24 Sep 2026 10:15:30            | 2026-09-24T10:15:30Z",
            "2026-09-24T10:15:30Z                 | 2026-09-24T10:15:30Z",
            "2026-09-24T10:15:30.123+02:00        | 2026-09-24T08:15:30.123Z",
            "2026-09-24T10:15:30+0200             | 2026-09-24T08:15:30Z",
            "2026-09-24 10:15:30                  | 2026-09-24T10:15:30Z",
            "2026-09-24                           | 2026-09-24T00:00:00Z"
    })
    void parse_rfc822AndIso(String input, String expected) {
        assertThat(FeedDates.parse(input)).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "gestern", "32 Sep 2026 10:00:00 GMT", "24 Foo 2026 10:00 GMT", "2026-13-01"})
    void parse_returnsNullForGarbage(String input) {
        assertThat(FeedDates.parse(input)).isNull();
    }

    @Test
    void parse_rejectsOverlongInput() {
        assertThat(FeedDates.parse("2026-09-24" + " ".repeat(200))).isNull();
        assertThat(FeedDates.parse(null)).isNull();
    }
}
