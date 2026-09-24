package com.automation.service.integration.providers.ical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Direct tests of one content line; whole calendars are {@code ICalParserTest}. */
class ICalContentLineTest {

    // --- name and value ---

    @Test
    void simpleLine_nameUpperCased_valueRaw() {
        ICalContentLine line = ICalContentLine.parse("summary:Team Meeting");

        assertThat(line.name()).isEqualTo("SUMMARY");
        assertThat(line.params()).isEmpty();
        assertThat(line.value()).isEqualTo("Team Meeting");
    }

    @Test
    void valueKeepsFurtherColonsAndEscapes() {
        ICalContentLine line = ICalContentLine.parse("DESCRIPTION:Zeit: 10:00\\, Raum 3\\nbitte pünktlich");

        assertThat(line.value()).isEqualTo("Zeit: 10:00\\, Raum 3\\nbitte pünktlich");
    }

    @Test
    void emptyValue_isKept() {
        assertThat(ICalContentLine.parse("LOCATION:").value()).isEmpty();
    }

    @Test
    void malformedLines_returnNull() {
        assertThat(ICalContentLine.parse(null)).isNull();
        assertThat(ICalContentLine.parse("")).isNull();
        assertThat(ICalContentLine.parse("NO SEPARATOR")).isNull();
        assertThat(ICalContentLine.parse(":value without name")).isNull();
        assertThat(ICalContentLine.parse(" ;X=1:value")).isNull();
        assertThat(ICalContentLine.parse("X;P=\"never closed:value")).isNull();
    }

    // --- parameters ---

    @Test
    void parameters_namesUpperCased_valuesKept_lookupIgnoresCase() {
        ICalContentLine line = ICalContentLine.parse("DTSTART;tzid=Europe/Berlin;VALUE=DATE-TIME:20261001T100000");

        assertThat(line.name()).isEqualTo("DTSTART");
        assertThat(line.params()).containsExactly(
                Map.entry("TZID", "Europe/Berlin"), Map.entry("VALUE", "DATE-TIME"));
        assertThat(line.param("tzid")).isEqualTo("Europe/Berlin");
        assertThat(line.param("Value")).isEqualTo("DATE-TIME");
        assertThat(line.param("missing")).isNull();
        assertThat(line.value()).isEqualTo("20261001T100000");
    }

    @Test
    void quotedParameter_mayContainColonAndSemicolon_quotesAreRemoved() {
        ICalContentLine line = ICalContentLine.parse(
                "ATTENDEE;CN=\"Doe; Jane\";DELEGATED-FROM=\"mailto:boss@example.com\":mailto:jane@example.com");

        assertThat(line.param("CN")).isEqualTo("Doe; Jane");
        assertThat(line.param("DELEGATED-FROM")).isEqualTo("mailto:boss@example.com");
        assertThat(line.value()).isEqualTo("mailto:jane@example.com");
    }

    @Test
    void parameterWithoutEquals_orWithoutName_isSkipped() {
        ICalContentLine line = ICalContentLine.parse("X-FLAG;BROKEN;=orphan;OK=1:v");

        assertThat(line.params()).containsExactly(Map.entry("OK", "1"));
        assertThat(line.value()).isEqualTo("v");
    }

    @Test
    void emptyParameterValue_andLoneQuote_areKeptAsIs() {
        ICalContentLine line = ICalContentLine.parse("X;A=;B=\"\";C=\"x:v");

        assertThat(line).as("the lone quote swallows the colon").isNull();
        ICalContentLine empty = ICalContentLine.parse("X;A=;B=\"\":v");
        assertThat(empty.param("A")).isEmpty();
        assertThat(empty.param("B")).isEmpty();
    }

    @Test
    void whitespaceAroundNameAndParameterName_isTrimmed() {
        ICalContentLine line = ICalContentLine.parse(" uid ; x-param = a :42");

        assertThat(line.name()).isEqualTo("UID");
        assertThat(line.params()).containsExactly(Map.entry("X-PARAM", " a "));
        assertThat(line.value()).isEqualTo("42");
    }

    // --- unfolding + parsing together ---

    @Test
    void foldedLine_parsesAfterUnfolding_evenWhenTheFoldSplitsAParameter() {
        List<String> lines = ICalParser.unfold("DTSTART;TZ\r\n ID=Europe/Ber\r\n\tlin:2026\r\n 1001T100000\r\n");

        assertThat(lines).hasSize(1);
        ICalContentLine line = ICalContentLine.parse(lines.get(0));
        assertThat(line.param("TZID")).isEqualTo("Europe/Berlin");
        assertThat(line.value()).isEqualTo("20261001T100000");
    }

    // --- TEXT escaping ---

    @Test
    void unescapeText_handlesTheRfcEscapes() {
        assertThat(ICalContentLine.unescapeText("a\\nb\\Nc\\,d\\;e\\\\f")).isEqualTo("a\nb\nc,d;e\\f");
    }

    @Test
    void unescapeText_leniency_colonAndQuote_unknownEscapeKept_trailingBackslashKept() {
        assertThat(ICalContentLine.unescapeText("x\\:y\\\"z")).isEqualTo("x:y\"z");
        assertThat(ICalContentLine.unescapeText("tab\\there")).isEqualTo("tab\\there");
        assertThat(ICalContentLine.unescapeText("end\\")).isEqualTo("end\\");
    }

    @Test
    void unescapeText_withoutBackslash_returnsTheSameValue() {
        String plain = "no escapes here";

        assertThat(ICalContentLine.unescapeText(plain)).isSameAs(plain);
        assertThat(ICalContentLine.unescapeText(null)).isNull();
    }

    @Test
    void escapeText_escapesSpecialsAndEveryLineBreakStyle() {
        assertThat(ICalContentLine.escapeText("a\\b;c,d")).isEqualTo("a\\\\b\\;c\\,d");
        assertThat(ICalContentLine.escapeText("1\r\n2\r3\n4")).isEqualTo("1\\n2\\n3\\n4");
        assertThat(ICalContentLine.escapeText(null)).isEmpty();
    }

    @Test
    void escapeThenUnescape_roundTrips() {
        String text = "Agenda: 1) Budget; 2) Plan, final\\draft\nZeile 2";

        assertThat(ICalContentLine.unescapeText(ICalContentLine.escapeText(text))).isEqualTo(text);
    }
}
