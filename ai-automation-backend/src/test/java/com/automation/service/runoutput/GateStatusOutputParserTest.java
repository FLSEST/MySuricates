package com.automation.service.runoutput;

import com.automation.entity.GateStatus;
import com.automation.service.runoutput.GateStatusOutputParser.ParsedGateStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GateStatusOutputParserTest {

    @Test
    void parse_simpleRed() {
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse("gate_status: RED");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
        assertThat(result.get().category()).isNull();
        assertThat(result.get().description()).isNull();
    }

    @Test
    void parse_simpleGreen() {
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse("gate_status: GREEN");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.GREEN);
    }

    @Test
    void parse_simpleYellow() {
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse("gate_status: YELLOW");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.YELLOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gate_status: red", "gate_status: Red", "gate_status: rEd"})
    void parse_caseInsensitive(String input) {
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(input);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
    }

    @Test
    void parse_allFieldsTogether() {
        String output = """
                Some analysis output here.

                gate_status: RED
                gate_category: lint-errors
                gate_description: Found 5 critical lint errors in src/main
                """;

        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
        assertThat(result.get().category()).isEqualTo("lint-errors");
        assertThat(result.get().description()).isEqualTo("Found 5 critical lint errors in src/main");
    }

    @Test
    void parse_markdownBold() {
        String output = "**gate_status: RED**";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
    }

    @Test
    void parse_backticks() {
        String output = "gate_status: `RED`";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
    }

    @Test
    void parse_noSpace() {
        String output = "gate_status:RED";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
    }

    @Test
    void parse_embeddedInLongerText() {
        String output = """
                ## Platform Health Report

                Checked 15 files, found 3 issues.

                ### Summary
                - Issue 1: Missing import
                - Issue 2: Unused variable
                - Issue 3: Deprecated API call

                gate_status: YELLOW
                gate_category: code-quality
                gate_description: 3 non-critical issues found

                ### Recommendation
                Fix the issues in the next sprint.
                """;

        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.YELLOW);
        assertThat(result.get().category()).isEqualTo("code-quality");
        assertThat(result.get().description()).isEqualTo("3 non-critical issues found");
    }

    @Test
    void parse_noMarker_returnsEmpty() {
        String output = "Everything looks good, no issues found.";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isEmpty();
    }

    @Test
    void parse_null_returnsEmpty() {
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(null);
        assertThat(result).isEmpty();
    }

    @Test
    void parse_blank_returnsEmpty() {
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse("   ");
        assertThat(result).isEmpty();
    }

    @Test
    void parse_invalidValue_returnsEmpty() {
        String output = "gate_status: BLUE";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isEmpty();
    }

    @Test
    void parse_markerMidLine_noMatch() {
        String output = "The gate_status: RED was found in the report";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isEmpty();
    }

    @Test
    void parse_statusOnly_noCategoryOrDescription() {
        String output = """
                Analysis complete.
                gate_status: GREEN
                All checks passed.
                """;

        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.GREEN);
        assertThat(result.get().category()).isNull();
        assertThat(result.get().description()).isNull();
    }

    @Test
    void parse_markdownHeading() {
        String output = "### Gate-Status: YELLOW";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.YELLOW);
    }

    @Test
    void parse_hyphenVariant() {
        String output = "**Gate-Status: YELLOW** — 4 von 6 Kategorien veraltet";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.YELLOW);
    }

    @Test
    void parse_trailingTextAfterStatus() {
        String output = "**gate_status: RED** — 58.85% Erfolgsrate, deutlich unter 70%.";
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
    }

    @Test
    void parse_boldCategoryAndDescription() {
        String output = """
                **gate_status: RED**
                **gate_category: security**
                **gate_description: SQL injection vulnerability detected**
                """;

        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.RED);
        assertThat(result.get().category()).isEqualTo("security");
        assertThat(result.get().description()).isEqualTo("SQL injection vulnerability detected");
    }

    /** foreach setzt "[<item>]: " vor jede Iterationsausgabe (TODO 8671d03b). */
    @Test
    void parse_markersBehindForeachItemPrefix() {
        String output = """
                [frontend]: gate_status: YELLOW
                [frontend]: gate_description: 3 Updates offen
                """;
        Optional<ParsedGateStatus> result = GateStatusOutputParser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(GateStatus.YELLOW);
        assertThat(result.get().description()).isEqualTo("3 Updates offen");
    }
}
