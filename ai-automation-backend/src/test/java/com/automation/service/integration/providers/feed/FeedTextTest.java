package com.automation.service.integration.providers.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FeedTextTest {

    @Test
    void plain_nullStaysNull_andTagsScriptsStylesCommentsAreDropped() {
        assertThat(FeedText.plain(null, 100)).isNull();
        assertThat(FeedText.plain("<p>Hallo <b>fette</b> Welt</p><script>alert(1)</script><style>p{}</style>"
                + "<!-- weg -->Ende", 100)).isEqualTo("Hallo fette Welt Ende");
    }

    @Test
    void stripTags_blockTagsSeparateWords_inlineTagsJoin() {
        assertThat(FeedText.collapse(FeedText.stripTags("eins<br>zwei<li>drei"))).isEqualTo("eins zwei drei");
        assertThat(FeedText.stripTags("Fu<b>ss</b>ball")).isEqualTo("Fussball");
        assertThat(FeedText.stripTags("<A HREF=\"x\">Link</A>text")).isEqualTo("Linktext");
    }

    @Test
    void stripTags_loneAngleBracketIsText_unclosedScriptSwallowsRest() {
        assertThat(FeedText.stripTags("3 < 5")).isEqualTo("3 < 5");
        assertThat(FeedText.stripTags("vorher<script>nie zu")).isEqualTo("vorher");
        assertThat(FeedText.stripTags("a<!-- offen")).isEqualTo("a");
        // "<scripted>" is an ordinary (block) tag, not a script block
        assertThat(FeedText.collapse(FeedText.stripTags("x<scripted>y</scripted>"))).isEqualTo("x y");
    }

    @Test
    void stripTags_manyUnclosedScriptsStayFast() {
        String hostile = "<script".repeat(20_000);
        long start = System.nanoTime();
        FeedText.stripTags(hostile);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2000);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Tom &amp; Jerry|Tom & Jerry",
            "&lt;b&gt;|<b>",
            "M&auml;rz &ndash; Juli|März – Juli",
            "&#65;&#x42;&#X43;|ABC",
            "&unknown; bleibt|&unknown; bleibt",
            "&#xZZ; &#; &#0;|&#xZZ; &#; &#0;",
            "&#1114112;|&#1114112;",
            "ohne Semikolon &amp|ohne Semikolon &amp",
            "&thisisverylongname;|&thisisverylongname;"
    })
    void decodeEntities(String in, String expected) {
        assertThat(FeedText.decodeEntities(in)).isEqualTo(expected);
    }

    @Test
    void collapse_mergesWhitespaceIncludingNbsp() {
        assertThat(FeedText.collapse("  a \n\t b  c  ")).isEqualTo("a b c");
    }

    @Test
    void truncate_cutsAtWordBoundaryWithEllipsis() {
        assertThat(FeedText.truncate("kurz", 10)).isEqualTo("kurz");
        assertThat(FeedText.truncate("kurz", 0)).isEqualTo("kurz");
        assertThat(FeedText.truncate("eins zwei drei vier", 12)).isEqualTo("eins zwei…");
    }

    @Test
    void truncate_hardCutWhenNoBoundaryInSecondHalf() {
        String result = FeedText.truncate("Donaudampfschifffahrt", 10);
        assertThat(result).isEqualTo("Donaudamp…").hasSize(10);
        // an early space (in the first half) is not used
        assertThat(FeedText.truncate("ab cdefghijklmnop", 10)).isEqualTo("ab cdefgh…");
    }

    @Test
    void plain_appliesTruncationAfterDecoding() {
        assertThat(FeedText.plain("<p>Tom &amp; Jerry gehen heute Abend ins Kino</p>", 20)).isEqualTo("Tom & Jerry gehen…");
    }
}
