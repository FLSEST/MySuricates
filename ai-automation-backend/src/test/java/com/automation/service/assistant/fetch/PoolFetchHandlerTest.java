package com.automation.service.assistant.fetch;

import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.repository.WorkflowRepository;
import com.automation.service.pool.WorkflowPoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PoolFetchHandlerTest {

    @Mock private WorkflowPoolService workflowPoolService;
    @Mock private WorkflowRepository workflowRepository;

    private PoolFetchHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PoolFetchHandler(workflowPoolService, new BlockConfigSummaryFormatter(workflowRepository));
    }

    private static PoolWorkflowEntry entry(String id, Map<String, String> name, Map<String, String> description,
                                           String category, List<String> tags, List<BlockDefinition> blocks,
                                           String instructions, String model, Map<String, Object> defaultContext) {
        return new PoolWorkflowEntry(id, name, description, null, category, null, tags, blocks,
                instructions, model, null, null, null, defaultContext, Map.of(), id + ".json");
    }

    @Test
    void formatPoolWorkflows_empty_returnsHint() {
        when(workflowPoolService.listAll()).thenReturn(List.of());

        assertThat(handler.formatPoolWorkflows()).isEqualTo("No pool workflows available.\n");
    }

    @Test
    void formatPoolWorkflows_prefersGermanThenEnglishThenId() {
        when(workflowPoolService.listAll()).thenReturn(List.of(
                entry("p-de", Map.of("de", "Deutsch", "en", "English"), Map.of("de", "Beschreibung"),
                        "ops", List.of("a", "b"), List.of(BlockDefinition.builder().type("shell").build()),
                        null, null, null),
                entry("p-en", Map.of("en", "Only English"), Map.of("en", "English desc"),
                        null, List.of(), null, null, null, null),
                entry("p-none", null, null, null, null, null, null, null, null)));

        String out = handler.formatPoolWorkflows();

        assertThat(out)
                .startsWith("**Workflow pool (3 templates):**")
                .contains("- **Deutsch** (ID: `p-de`, cat: ops, 1 blocks) Tags: a, b\n  Beschreibung\n")
                .contains("- **Only English** (ID: `p-en`)\n  English desc\n")
                .contains("- **p-none** (ID: `p-none`)\n")
                .contains("<fetch type=\"pool_workflow\"");
    }

    @Test
    void formatPoolWorkflows_serviceFails_returnsFallbackMessage() {
        when(workflowPoolService.listAll()).thenThrow(new IllegalStateException("pool dir missing"));

        assertThat(handler.formatPoolWorkflows()).isEqualTo("Pool workflows could not be loaded.\n");
    }

    @Test
    void formatPoolWorkflowDetail_minimalEntry_fallsBackToIdAndSkipsEmptySections() {
        when(workflowPoolService.findById("p1")).thenReturn(
                entry("p1", null, null, null, null, List.of(), " ", null, Map.of()));

        assertThat(handler.formatPoolWorkflowDetail("p1")).isEqualTo("**Pool workflow: p1** (p1)\n");
    }

    @Test
    void formatPoolWorkflowDetail_blocksUseSummaryFormatterAndLabels() {
        BlockDefinition labelled = BlockDefinition.builder().type("shell")
                .config(Map.of("label", "Build", "command", "make")).build();
        BlockDefinition bare = BlockDefinition.builder().type("approval").config(null).build();
        when(workflowPoolService.findById("p1")).thenReturn(
                entry("p1", Map.of("de", "Vorlage"), null, null, null, List.of(labelled, bare),
                        "i".repeat(600), null, null));

        String out = handler.formatPoolWorkflowDetail("p1");

        assertThat(out)
                .contains("Instructions: " + "i".repeat(500))
                .contains("[Output truncated: 500 of 600 characters]")
                .contains("\nBlocks (2):\n  1. [shell] Build\n     command: make\n  2. [approval]\n");
    }

    @Test
    void formatPoolWorkflowDetail_unknownOrFailing_returnsNotFound() {
        when(workflowPoolService.findById("missing")).thenReturn(null);
        when(workflowPoolService.findById("boom")).thenThrow(new RuntimeException("io"));

        assertThat(handler.formatPoolWorkflowDetail("missing")).isEqualTo("Pool workflow 'missing' not found.\n");
        assertThat(handler.formatPoolWorkflowDetail("boom")).isEqualTo("Pool workflow 'boom' not found.\n");
    }
}
