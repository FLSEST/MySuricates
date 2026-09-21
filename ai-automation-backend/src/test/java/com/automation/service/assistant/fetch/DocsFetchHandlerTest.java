package com.automation.service.assistant.fetch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real bundled docs from the classpath (maven-resources-plugin copies repo docs/
 * into target/classes/docs), so the section IDs are checked against the actual guide.
 */
class DocsFetchHandlerTest {

    private final DocsFetchHandler handler = new DocsFetchHandler();

    @Test
    void unknownDocId_isRejectedWithoutClasspathAccess() {
        assertThat(handler.formatDocsSection("secret-notes")).isEqualTo("Unknown documentation: secret-notes\n");
        assertThat(handler.formatDocsSection("secret-notes/intro")).isEqualTo("Unknown documentation: secret-notes\n");
    }

    @Test
    void workflowGuideWithoutSection_returnsTocWithOnlyLevelTwoHeadingsAndSectionIds() {
        String out = handler.formatDocsSection("workflow-guide");

        assertThat(out)
                .startsWith("**Documentation: workflow-guide** — Table of contents")
                .contains("## 5. Timeout-Konfiguration")
                .contains("Available section IDs")
                .contains("  timeouts\n")
                .contains("  dos-and-donts\n")
                .endsWith("Fetch a section to get details.\n");
        assertThat(out.lines().filter(l -> l.startsWith("### "))).isEmpty();
    }

    @Test
    void tocOfOtherDocs_hasNoSectionIdHint() {
        String out = handler.formatDocsSection("architektur");

        assertThat(out).contains("**Documentation: architektur** — Table of contents")
                .doesNotContain("Available section IDs");
    }

    @Test
    void workflowGuideSection_isCutAtTheNextLevelTwoHeading() {
        String out = handler.formatDocsSection("workflow-guide/timeouts");

        assertThat(out).startsWith("**workflow-guide / timeouts:**\n\n## 5. Timeout-Konfiguration")
                .doesNotContain("## 6.")
                .doesNotContain("## 4.");
    }

    @Test
    void sectionOne_doesNotSwallowSectionTen() {
        // "## 1." is a prefix check — it must not also match "## 10. Chains".
        String out = handler.formatDocsSection("workflow-guide/quick-start");

        assertThat(out).contains("## 1. Schnellstart").doesNotContain("## 10.").doesNotContain("## 2.");
    }

    @Test
    void unknownSectionKey_fallsBackToWholeDocTruncatedAt8000() {
        String out = handler.formatDocsSection("workflow-guide/no-such-section");

        assertThat(out).startsWith("**Documentation: workflow-guide**\n\n")
                .contains("[Output truncated: 8000 of ");
    }

    @Test
    void sectionOnDocWithoutSectionMap_returnsWholeDoc() {
        String out = handler.formatDocsSection("vision/anything");

        assertThat(out).startsWith("**Documentation: vision**\n\n");
    }

    @Test
    void formatBlockSpec_unknownType_listsAvailableTypes() {
        String out = handler.formatBlockSpec("teleport");

        assertThat(out).startsWith("Unknown block type 'teleport'. Available: [")
                .contains("shell").contains("compress-ai").contains("human-input");
    }

    @Test
    void formatBlockSpec_approval_documentsAnswerField() {
        assertThat(handler.formatBlockSpec("approval"))
                .startsWith("**Block type: approval**")
                .contains("\"answer\": \"Yes\"");
    }
}
