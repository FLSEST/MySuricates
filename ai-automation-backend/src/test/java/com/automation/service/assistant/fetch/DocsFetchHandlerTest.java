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

    /** Die Überschrift heißt „## Anhang: Template-Kurzreferenz“, nicht „## Appendix“ (TODO f8d74d01). */
    @Test
    void appendixSection_isFound() {
        assertThat(handler.formatDocsSection("workflow-guide/appendix"))
                .startsWith("**workflow-guide / appendix:**\n\n## Anhang");
    }

    /** Ein „## …“ in einem Codeblock ist Beispielausgabe, keine Überschrift (TODO f8d74d01). */
    @Test
    void headingInsideCodeFence_isNeitherTocEntryNorSectionEnd() {
        assertThat(handler.formatDocsSection("workflow-guide")).doesNotContain("## backend_tests");
        String doc = "## 2. Blöcke\nText\n```\n## beispiel — FAILED\n```\nnoch Abschnitt 2\n## 3. Weiter\n";
        assertThat(DocsFetchHandler.extractSection(doc, "## 2."))
                .contains("## beispiel — FAILED")
                .endsWith("noch Abschnitt 2")
                .doesNotContain("## 3.");
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

    /** The four types that answered "Unknown block type" (TODO 321b414e) name their required config. */
    @Test
    void formatBlockSpec_todoAndDashboardBlocks_nameTheirRequiredConfig() {
        assertThat(handler.formatBlockSpec("todo-create"))
                .contains("Required config: title").contains("variable_name (default \"todo_id\")");
        assertThat(handler.formatBlockSpec("todo-update")).contains("Required config: id");
        assertThat(handler.formatBlockSpec("todo-close")).contains("Required config: id").contains("{{ todo_id }}");
        assertThat(handler.formatBlockSpec("dashboard-report")).contains("Required config: status (GREEN/YELLOW/RED");
    }

    @Test
    void formatBlockSpec_approval_documentsAnswerField() {
        assertThat(handler.formatBlockSpec("approval"))
                .startsWith("**Block type: approval**")
                .contains("\"answer\": \"Yes\"");
    }
}
