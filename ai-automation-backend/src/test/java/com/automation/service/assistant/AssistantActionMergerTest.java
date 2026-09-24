package com.automation.service.assistant;

import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.dto.PendingTool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantActionMergerTest {

    private static AssistantAction action(String type, String id, String label) {
        return new AssistantAction(type, id, "name-" + id, label);
    }

    private static PendingTool tool(String id) {
        return new PendingTool(id, "create_todo", "desc " + id, "{}", Instant.now().plusSeconds(60));
    }

    @Test
    void mergeActions_bothEmptyOrNull_returnsEmpty() {
        assertThat(AssistantActionMerger.mergeActions(null, null)).isEmpty();
        assertThat(AssistantActionMerger.mergeActions(List.of(), List.of())).isEmpty();
    }

    @Test
    void mergeActions_inlineFirstThenSuggested() {
        var merged = AssistantActionMerger.mergeActions(
                List.of(action("show_run", "r1", "inline")),
                List.of(action("show_workflow", "w1", "suggested")));

        assertThat(merged).extracting(AssistantAction::label).containsExactly("inline", "suggested");
    }

    @Test
    void mergeActions_sameTypeAndId_inlineWins() {
        var merged = AssistantActionMerger.mergeActions(
                List.of(action("run_workflow", "w1", "inline")),
                List.of(action("run_workflow", "w1", "suggested")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).label()).isEqualTo("inline");
    }

    @Test
    void mergeActions_sameTypeDifferentId_bothKept() {
        var merged = AssistantActionMerger.mergeActions(
                List.of(action("show_run", "r1", "a")),
                List.of(action("show_run", "r2", "b")));

        assertThat(merged).hasSize(2);
    }

    @Test
    void mergeActions_nullIdTreatedAsEmptyKey_dedupes() {
        var merged = AssistantActionMerger.mergeActions(
                List.of(action("start_wish", null, "inline")),
                List.of(action("start_wish", null, "suggested")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).label()).isEqualTo("inline");
    }

    @Test
    void mergeActions_nullEntriesAndNullTypes_skipped() {
        List<AssistantAction> inline = new ArrayList<>(Arrays.asList(null, action(null, "x", "no-type")));

        assertThat(AssistantActionMerger.mergeActions(inline, List.of(action("show_run", "r1", "ok"))))
                .extracting(AssistantAction::label).containsExactly("ok");
    }

    @Test
    void mergePendingTools_bothEmpty_returnsEmpty() {
        assertThat(AssistantActionMerger.mergePendingTools(null, null)).isEmpty();
        assertThat(AssistantActionMerger.mergePendingTools(List.of(), null)).isEmpty();
    }

    @Test
    void mergePendingTools_inlineBeforeSuggested_noDedup() {
        var merged = AssistantActionMerger.mergePendingTools(
                List.of(tool("a")), List.of(tool("b"), tool("a")));

        assertThat(merged).extracting(PendingTool::id).containsExactly("a", "b", "a");
    }
}
