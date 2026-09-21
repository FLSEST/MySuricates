package com.automation.service.assistant.fetch;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowFetchHandlerTest {

    @Mock private WorkflowRepository workflowRepository;
    private WorkflowFetchHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WorkflowFetchHandler(workflowRepository, new BlockConfigSummaryFormatter(workflowRepository));
    }

    @Test
    void detail_withoutProjectOrOptionalFields_usesDashAndSkipsLines() {
        Workflow wf = Workflow.builder().id("wf-1").name("Solo").description(" ").model("").build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        assertThat(handler.formatWorkflowDetail("wf-1")).isEqualTo("**Workflow: Solo** (—)\nProject ID: —\n");
    }

    @Test
    void detail_blockWithNullConfig_isListedWithoutSummary() {
        Workflow wf = Workflow.builder().id("wf-1").name("W")
                .blocks(List.of(BlockDefinition.builder().type("approval").config(null).build())).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        assertThat(handler.formatWorkflowDetail("wf-1")).endsWith("\nBlocks (1):\n  1. [approval]\n");
    }

    @Test
    void detail_repositoryFailure_returnsEmptyString() {
        when(workflowRepository.findById("wf-1")).thenThrow(new IllegalStateException("db locked"));

        assertThat(handler.formatWorkflowDetail("wf-1")).isEmpty();
        assertThat(handler.formatWorkflowFull("wf-1")).isEmpty();
        assertThat(handler.formatWorkflowInputs("wf-1")).isEmpty();
    }

    @Test
    void full_rendersNullValuesAndTruncatesLongConfigValues() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("command", "c".repeat(350));
        cfg.put("label", null);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("env", "prod");
        Workflow wf = Workflow.builder().id("wf-1").name("Full")
                .project(Project.builder().id("p1").name("Alpha").build())
                .defaultContext(ctx)
                .blocks(List.of(BlockDefinition.builder().type("shell").config(cfg).build())).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        String out = handler.formatWorkflowFull("wf-1");

        assertThat(out)
                .startsWith("**Workflow (full): Full** (Alpha)\nID: wf-1\n")
                .contains("Default inputs: {env=prod}")
                .contains("Blocks (1, full config):\n  1. type: shell\n       command: " + "c".repeat(300))
                .contains("[Output truncated: 300 of 350 characters]")
                .contains("       label: null\n");
    }

    @Test
    void inputs_nullValueIsRenderedLiterally() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("branch", "main");
        ctx.put("tag", null);
        Workflow wf = Workflow.builder().id("wf-1").name("W").defaultContext(ctx).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        assertThat(handler.formatWorkflowInputs("wf-1"))
                .isEqualTo("**Inputs for workflow: W** (id: wf-1)\n  - branch = main\n  - tag = null\n");
    }

    @Test
    void find_trimsQuery_andCapsResultsAtTwenty() {
        List<Workflow> matches = IntStream.range(0, 23)
                .mapToObj(i -> Workflow.builder().id("id-" + i).name("Report " + i).build())
                .toList();
        when(workflowRepository.findByNameContainingIgnoreCase("report")).thenReturn(matches);

        String out = handler.formatFindWorkflow("  report ");

        assertThat(out)
                .contains(":** 23 matches\n")
                .contains("  - **Report 19** (id: `id-19`, project: —)")
                .doesNotContain("Report 20")
                .contains("  … 3 more matches (refine the search).");
    }

    @Test
    void find_nullQuery_hintsWithoutRepositoryCall_andFailureIsReported() {
        assertThat(handler.formatFindWorkflow(null)).startsWith("Search term missing.");
        verifyNoInteractions(workflowRepository);

        when(workflowRepository.findByNameContainingIgnoreCase("x")).thenThrow(new RuntimeException("boom"));
        assertThat(handler.formatFindWorkflow("x")).isEqualTo("Workflow search failed.\n");
    }
}
