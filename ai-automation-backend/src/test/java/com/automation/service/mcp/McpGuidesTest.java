package com.automation.service.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpGuidesTest {

    private static final String MARKDOWN = """
            # Guide

            ## 1. Basics

            Intro.

            ### Block Choice: Frugality Ladder

            1. shell first

            ```bash
            ## not a heading inside code
            echo hi
            ```

            ### Pattern 6: Audit Pipeline

            Measure, then judge.

            #### Detail

            Deeper text.

            ## 2. Timeouts

            Other.
            """;

    @Test
    void headingsSkipCodeBlocks() {
        assertThat(McpGuides.headings(MARKDOWN)).containsExactly(
                "## 1. Basics", "### Block Choice: Frugality Ladder", "### Pattern 6: Audit Pipeline", "## 2. Timeouts");
    }

    @Test
    void sectionRunsToTheNextHeadingOfTheSameLevelAndIgnoresHashesInCode() {
        String section = McpGuides.section(MARKDOWN, "frugality ladder").orElseThrow();

        assertThat(section).startsWith("### Block Choice: Frugality Ladder")
                .contains("## not a heading inside code")
                .doesNotContain("Audit Pipeline");
    }

    @Test
    void sectionKeepsDeeperHeadings() {
        String section = McpGuides.section(MARKDOWN, "Audit Pipeline").orElseThrow();

        assertThat(section).contains("#### Detail", "Deeper text.").doesNotContain("Timeouts");
    }

    @Test
    void unknownSectionIsEmpty() {
        assertThat(McpGuides.section(MARKDOWN, "nothing like this")).isEmpty();
    }
}
