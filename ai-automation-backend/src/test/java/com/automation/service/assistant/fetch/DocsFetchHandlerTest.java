package com.automation.service.assistant.fetch;

import com.automation.executor.spec.BlockSpecs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        String out = handler.formatDocsSection("architektur/anything");

        assertThat(out).startsWith("**Documentation: architektur**\n\n");
    }

    @Test
    void formatBlockSpec_unknownType_listsAvailableTypes() {
        String out = handler.formatBlockSpec("teleport");

        assertThat(out).startsWith("Unknown block type 'teleport'. Available: [")
                .contains("shell").contains("compress-ai").contains("human-input");
    }

    /**
     * The four types that answered "Unknown block type" (TODO 321b414e) name their required config.
     * Keys stand in backticks since TODO c4b81017 — the convention BlockSpecConfigKeysGateTest reads.
     */
    @Test
    void formatBlockSpec_todoAndDashboardBlocks_nameTheirRequiredConfig() {
        assertThat(handler.formatBlockSpec("todo-create"))
                .contains("Required config: `title`").contains("`variable_name` (default \"todo_id\")");
        assertThat(handler.formatBlockSpec("todo-update")).contains("Required config: `id`");
        assertThat(handler.formatBlockSpec("todo-close")).contains("Required config: `id`").contains("{{ todo_id }}");
        assertThat(handler.formatBlockSpec("dashboard-report")).contains("Required config: `status` (GREEN/YELLOW/RED");
    }

    /**
     * The keys the executors read, where the older texts named others (TODO c4b81017). The drift gate
     * BlockSpecConfigKeysGateTest proves that a named key is read — this pins that the right ones are named.
     */
    @Test
    void formatBlockSpec_namesTheKeysTheExecutorsRead() {
        Map<String, List<String>> expected = Map.ofEntries(
                Map.entry("agentic", List.of("Required config: `goal`", "`max_total_tokens`")),
                Map.entry("conditional", List.of("Required config: `condition`", "`then_command`", "`else_command`")),
                Map.entry("foreach", List.of("Required config: `command`", "`items_source`", "`items_command`")),
                Map.entry("while", List.of("Required config: `command`", "`condition_command`", "`max_iterations`")),
                Map.entry("retry", List.of("Required config: `command`", "`max_retries`", "`delay`")),
                Map.entry("switch", List.of("Required config: `expression`", "`cases`", "Optional: `default`")),
                Map.entry("approval", List.of("`timeout_minutes`", "`reject_options`", "`on_no`", "`mode`")),
                Map.entry("skill-capability", List.of("Required config: `capability`", "`params`", "`allow_writes`")),
                Map.entry("integration", List.of("Required config: `provider`", "`action`", "`params`")),
                Map.entry("document", List.of("Required config: `file_path`", "`fail_on_empty`", "`min_characters`")),
                Map.entry("human-input", List.of("`default_value`", "`use_default_on_timeout`")),
                Map.entry("sub-workflow", List.of("Required config: `workflow_id`", "no input mapping")));

        expected.forEach((type, parts) ->
                assertThat(handler.formatBlockSpec(type)).as("block-spec %s", type).contains(parts));
    }

    /** What the workflow-building prompts get (TODO cf8db6f8): the specs, without the AI override line. */
    @Test
    void reference_rendersTheSpecsOfTheTypesInOrder_withoutAiOverrides() {
        String reference = BlockSpecs.reference(List.of("foreach", "shell"));

        assertThat(reference).startsWith("**Block type: foreach**").endsWith("\n\n")
                .contains("\n\n**Block type: shell**").contains("Example config: {\"items_source\": \"command\"")
                .doesNotContain("AI overrides:");
        assertThat(handler.formatBlockSpec("foreach")).contains("AI overrides:");
    }

    @Test
    void reference_unknownType_isRefused() {
        assertThatThrownBy(() -> BlockSpecs.reference(List.of("forEach")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forEach");
    }

    @Test
    void formatBlockSpec_approval_documentsAnswerField() {
        assertThat(handler.formatBlockSpec("approval"))
                .startsWith("**Block type: approval**")
                .contains("\"answer\": \"Yes\"");
    }
}
