package com.automation.service.assistant.fetch;

import com.automation.entity.BlockDefinition;
import com.automation.executor.spec.BlockSpecs;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockConfigSummaryFormatterTest {

    @Mock private WorkflowRepository workflowRepository;
    @InjectMocks private BlockConfigSummaryFormatter formatter;

    private String summary(String type, Map<String, Object> cfg) {
        StringBuilder sb = new StringBuilder();
        BlockDefinition block = BlockDefinition.builder().type(type).config(cfg).build();
        formatter.appendBlockConfigSummary(sb, type, cfg, formatter.subWorkflowNames(List.of(block)));
        return sb.toString();
    }

    /** simple keeps its prompt in command, agentic in goal — the summary read prompt and showed neither (TODO e6ea63bc). */
    @Test
    void simpleAndAgentic_showTrimmedPromptOrGoalAndNonBlankModel() {
        assertThat(summary("simple", Map.of("command", "  Analysiere X  ", "model", "opus")))
                .isEqualTo("     command: Analysiere X\n     model: opus\n");
        assertThat(summary("agentic", Map.of("goal", "Refactor", "success_criteria", "mvn test grün",
                "max_iterations", 5, "model", " ")))
                .as("blank model is omitted")
                .isEqualTo("     goal: Refactor\n     success_criteria: mvn test grün\n     max_iterations: 5\n");
    }

    @Test
    void simple_longPrompt_isTruncatedAt200() {
        String out = summary("simple", Map.of("command", "p".repeat(250)));

        assertThat(out).contains("p".repeat(200))
                .doesNotContain("p".repeat(201))
                .contains("[Output truncated: 200 of 250 characters]");
    }

    @Test
    void shell_showsCommandTruncatedAt300() {
        assertThat(summary("shell", Map.of("command", " ls -la "))).isEqualTo("     command: ls -la\n");
        assertThat(summary("shell", Map.of("command", "make", "container_image", "node:22")))
                .isEqualTo("     command: make\n     container_image: node:22\n");
        assertThat(summary("shell", Map.of("command", "x".repeat(400))))
                .contains("[Output truncated: 300 of 400 characters]");
        assertThat(summary("shell", Map.of())).isEmpty();
    }

    /** The branches are commands — the summary counted true_blocks/false_blocks and always said 0/0. */
    @Test
    void conditional_showsConditionAndBothBranches() {
        String out = summary("conditional", Map.of(
                "condition", "test -f package.json", "then_command", "npm test", "else_command", "echo none",
                "execution_mode", "shell"));

        assertThat(out).isEqualTo("     condition: test -f package.json\n     then_command: npm test\n"
                + "     else_command: echo none\n     execution_mode: shell\n");
    }

    @Test
    void switch_showsExpressionAndCountsCasesAndDefault() {
        assertThat(summary("switch", Map.of("expression", "{{ env }}",
                "cases", List.of(Map.of(), Map.of(), Map.of()), "default", List.of(Map.of()))))
                .isEqualTo("     expression: {{ env }}\n     cases: 3\n     default: 1\n");
        assertThat(summary("switch", Map.of("cases", "not-a-list"))).as("a non-list is no count").isEmpty();
    }

    @Test
    void while_showsConditionCommandAndLimit() {
        assertThat(summary("while", Map.of("condition_command", "test ! -f done", "command", "./next.sh",
                "max_iterations", 10)))
                .isEqualTo("     condition_command: test ! -f done\n     command: ./next.sh\n     max_iterations: 10\n");
    }

    @Test
    void approval_showsQuestion() {
        assertThat(summary("approval", Map.of("question", "Deployen?"))).isEqualTo("     question: Deployen?\n");
    }

    /** retry runs a command — the summary read max_attempts and an inner block, keys no executor has. */
    @Test
    void retry_showsCommandRetriesAndMode() {
        assertThat(summary("retry", Map.of("command", "npm test", "max_retries", 2, "execution_mode", "shell")))
                .isEqualTo("     command: npm test\n     max_retries: 2\n     execution_mode: shell\n");
        assertThat(summary("retry", Map.of("max_attempts", 3))).as("keys retry does not read are not shown").isEmpty();
    }

    @Test
    void compress_showsSourceStageCountAndInstruction() {
        String out = summary("compress", Map.of(
                "source", "backend_tests",
                "stages", List.of(Map.of("op", "text.normalize"), Map.of("op", "lines.head"))));
        assertThat(out).isEqualTo("     source: backend_tests\n     stages: 2\n");

        assertThat(summary("compress-ai", Map.of("source", "previous", "instruction", " Kurz fassen ")))
                .isEqualTo("     source: previous\n     instruction: Kurz fassen\n");
    }

    /** The real type names are lowercase or hyphenated (TODO f8d74d01); foreach runs a command per item. */
    @Test
    void foreach_showsWhereItsItemsComeFromAndItsCommand() {
        assertThat(summary("foreach", Map.of("items_source", "command", "items_command", "ls docs",
                "command", "wc -l {{ item }}", "execution_mode", "shell")))
                .isEqualTo("     items_source: command\n     items_command: ls docs\n     command: wc -l {{ item }}\n"
                        + "     execution_mode: shell\n");
        assertThat(summary("foreach", Map.of("items", List.of("a", "b"), "blocks", List.of(Map.of()))))
                .isEqualTo("     items: [a, b]\n");
    }

    @Test
    void subWorkflow_showsTheWorkflowNameInsteadOfItsId() {
        when(workflowRepository.findIdAndNameByIdIn(Set.of("wf-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"wf-1", "Nachtlauf"}));

        assertThat(summary("sub-workflow", Map.of("workflow_id", " wf-1 ")))
                .isEqualTo("     sub-workflow: Nachtlauf\n");
    }

    /** Unknown or soft-deleted workflows are missing from the result — then the ID is shown as before. */
    @Test
    void subWorkflow_unknownWorkflow_showsItsId() {
        when(workflowRepository.findIdAndNameByIdIn(Set.of("wf-gone"))).thenReturn(List.of());

        assertThat(summary("sub-workflow", Map.of("workflow_id", "wf-gone")))
                .isEqualTo("     sub-workflow: wf-gone\n");
    }

    /** bf009b6f: the names of all sub-workflow blocks come from one query, not from a findById per block. */
    @Test
    void subWorkflowNames_resolvesAllSubWorkflowBlocksInOneQuery() {
        List<BlockDefinition> blocks = List.of(
                BlockDefinition.builder().type("sub-workflow").config(Map.of("workflow_id", "wf-1")).build(),
                BlockDefinition.builder().type("shell").config(Map.of("command", "ls", "workflow_id", "wf-x")).build(),
                BlockDefinition.builder().type("sub-workflow").config(Map.of("workflow_id", "wf-2")).build(),
                BlockDefinition.builder().type("sub-workflow").config(Map.of("workflow_id", "wf-1")).build(),
                BlockDefinition.builder().type("sub-workflow").config(Map.of("workflow_id", " ")).build(),
                BlockDefinition.builder().type("sub-workflow").build());
        when(workflowRepository.findIdAndNameByIdIn(Set.of("wf-1", "wf-2")))
                .thenReturn(List.of(new Object[]{"wf-1", "Eins"}, new Object[]{"wf-2", "Zwei"}));

        assertThat(formatter.subWorkflowNames(blocks)).containsExactlyInAnyOrderEntriesOf(Map.of("wf-1", "Eins", "wf-2", "Zwei"));
        verify(workflowRepository, times(1)).findIdAndNameByIdIn(any());
        verify(workflowRepository, never()).findById(any());
    }

    @Test
    void subWorkflowNames_withoutSubWorkflowBlocks_queriesNothing() {
        assertThat(formatter.subWorkflowNames(List.of(
                BlockDefinition.builder().type("shell").config(Map.of("command", "ls")).build()))).isEmpty();
        assertThat(formatter.subWorkflowNames(null)).isEmpty();
        verifyNoInteractions(workflowRepository);
    }

    /** The list both the workflow and the pool detail show: numbered, labelled, each block with its summary. */
    @Test
    void appendBlockList_numbersBlocksWithLabelAndSummary() {
        Map<String, Object> blankLabel = new LinkedHashMap<>();
        blankLabel.put("label", " ");
        blankLabel.put("question", "Deployen?");
        List<BlockDefinition> blocks = List.of(
                BlockDefinition.builder().type("shell").config(Map.of("label", "Build", "command", "make")).build(),
                BlockDefinition.builder().type("approval").config(blankLabel).build(),
                BlockDefinition.builder().type("sub-workflow").config(Map.of("workflow_id", "wf-1")).build(),
                BlockDefinition.builder().type("approval").config(null).build());
        when(workflowRepository.findIdAndNameByIdIn(Set.of("wf-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"wf-1", "Nachtlauf"}));
        StringBuilder sb = new StringBuilder("head\n");

        formatter.appendBlockList(sb, blocks);

        assertThat(sb).hasToString("head\n\nBlocks (4):\n"
                + "  1. [shell] Build\n     command: make\n"
                + "  2. [approval]\n     question: Deployen?\n"
                + "  3. [sub-workflow]\n     sub-workflow: Nachtlauf\n"
                + "  4. [approval]\n");
        verify(workflowRepository, times(1)).findIdAndNameByIdIn(any());
    }

    @Test
    void appendBlockList_withoutBlocks_appendsNothing() {
        StringBuilder sb = new StringBuilder("head\n");

        formatter.appendBlockList(sb, null);
        formatter.appendBlockList(sb, List.of());

        assertThat(sb).hasToString("head\n");
        verifyNoInteractions(workflowRepository);
    }

    @Test
    void humanInput_showsPromptInputTypeAndFormFields() {
        assertThat(summary("human-input", Map.of("prompt", "Welche Version?", "input_type", "select",
                "fields", List.of(Map.of(), Map.of()))))
                .isEqualTo("     prompt: Welche Version?\n     input_type: select\n     fields: 2\n");
    }

    @Test
    void unknownType_dumpsNonBlankEntriesTruncatedAt80() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("provider_id", "github");
        cfg.put("empty", " ");
        cfg.put("missing", null);
        cfg.put("long", "y".repeat(100));

        String out = summary("integration", cfg);

        assertThat(out).startsWith("     provider_id: github\n     long: " + "y".repeat(80))
                .contains("[Output truncated: 80 of 100 characters]")
                .doesNotContain("empty")
                .doesNotContain("missing");
        verifyNoInteractions(workflowRepository);
    }

    /**
     * Every key the one-glance summary shows ({@link BlockConfigSummaryFormatter}) must be named in
     * the spec of its type, so the assistant can look up what it sees.
     */
    @Test
    void everyKeyTheSummaryShowsIsNamedInTheSpec() {
        BlockConfigSummaryFormatter.shownKeys().forEach((type, keys) -> {
            assertThat(BlockSpecs.types()).as("summary type %s has a spec", type).contains(type);
            String spec = BlockSpecs.format(type);
            for (String key : keys) {
                assertThat(spec).as("spec of %s names `%s`", type, key).contains("`" + key + "`");
            }
        });
    }
}
