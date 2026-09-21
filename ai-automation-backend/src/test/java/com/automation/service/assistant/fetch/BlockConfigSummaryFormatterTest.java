package com.automation.service.assistant.fetch;

import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockConfigSummaryFormatterTest {

    @Mock private WorkflowRepository workflowRepository;
    @InjectMocks private BlockConfigSummaryFormatter formatter;

    private String summary(String type, Map<String, Object> cfg) {
        StringBuilder sb = new StringBuilder();
        formatter.appendBlockConfigSummary(sb, type, cfg);
        return sb.toString();
    }

    @Test
    void simpleAndAgentic_showTrimmedPromptAndNonBlankModel() {
        assertThat(summary("simple", Map.of("prompt", "  Analysiere X  ", "model", "opus")))
                .isEqualTo("     prompt: Analysiere X\n     model: opus\n");
        assertThat(summary("agentic", Map.of("prompt", "Refactor", "model", " ")))
                .as("blank model is omitted")
                .isEqualTo("     prompt: Refactor\n");
    }

    @Test
    void simple_longPrompt_isTruncatedAt200() {
        String out = summary("simple", Map.of("prompt", "p".repeat(250)));

        assertThat(out).contains("p".repeat(200))
                .doesNotContain("p".repeat(201))
                .contains("[Output truncated: 200 of 250 characters]");
    }

    @Test
    void shell_showsCommandTruncatedAt300() {
        assertThat(summary("shell", Map.of("command", " ls -la "))).isEqualTo("     command: ls -la\n");
        assertThat(summary("shell", Map.of("command", "x".repeat(400))))
                .contains("[Output truncated: 300 of 400 characters]");
        assertThat(summary("shell", Map.of())).isEmpty();
    }

    @Test
    void conditional_countsBranchBlocks_nonListCountsAsZero() {
        String out = summary("conditional", Map.of(
                "condition", "{{score}} > 0.8",
                "true_blocks", List.of(Map.of(), Map.of()),
                "false_blocks", "not-a-list"));

        assertThat(out).isEqualTo("     condition: {{score}} > 0.8\n"
                + "     if true: 2 block(s), if false: 0 block(s)\n");
    }

    @Test
    void switch_countsCases() {
        assertThat(summary("switch", Map.of("cases", List.of(Map.of(), Map.of(), Map.of()))))
                .isEqualTo("     cases: 3\n");
        assertThat(summary("switch", Map.of())).isEqualTo("     cases: 0\n");
    }

    @Test
    void approval_showsQuestion() {
        assertThat(summary("approval", Map.of("question", "Deployen?"))).isEqualTo("     question: Deployen?\n");
    }

    @Test
    void retry_showsMaxAttemptsAndInnerBlockType() {
        assertThat(summary("retry", Map.of("max_attempts", 3, "block", Map.of("type", "shell"))))
                .isEqualTo("     max_attempts: 3\n     inner block type: shell\n");
        assertThat(summary("retry", Map.of("block", "kein-map")))
                .as("non-map inner block is skipped silently")
                .isEmpty();
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

    /** Die echten Typnamen sind kleingeschrieben bzw. mit Bindestrich (TODO f8d74d01). */
    @Test
    void foreach_showsItemsAndNestedBlockCount() {
        assertThat(summary("foreach", Map.of("items", "{{ dateien }}", "blocks", List.of(Map.of(), Map.of()))))
                .isEqualTo("     items: {{ dateien }}\n     nested blocks: 2\n");
    }

    @Test
    void subWorkflow_showsTheWorkflowNameInsteadOfItsId() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(Workflow.builder().name("Nachtlauf").build()));

        assertThat(summary("sub-workflow", Map.of("workflow_id", "wf-1")))
                .isEqualTo("     sub-workflow: Nachtlauf\n");
    }

    @Test
    void humanInput_showsPromptAndInputType() {
        assertThat(summary("human-input", Map.of("prompt", "Welche Version?", "input_type", "text")))
                .contains("     prompt: Welche Version?\n").contains("     input_type: text\n");
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
}
