package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Welches Anfrage-Feld wohin geht (TODO 5c419baa). Was ueber den Service laeuft – Pruefung vor dem
 * Speichern, Versionseintrag, Events – steht in {@link WorkflowServiceTest}; hier die Regeln der
 * Felduebernahme selbst.
 */
class WorkflowRequestFieldsTest {

    private static Workflow workflow() {
        return Workflow.builder()
                .name("Alt")
                .description("Beschreibung")
                .instructions("alte Anweisungen")
                .model("opus")
                .metadata(new java.util.HashMap<>(Map.of("keep", 1, "over", "alt")))
                .defaultContext(new java.util.HashMap<>(Map.of("a", "1")))
                .build();
    }

    private static UpdateWorkflowRequest request(String name, String icon, String instructions) {
        return new UpdateWorkflowRequest(name, null, icon, null, instructions, null, null, null, null);
    }

    /** Nur Name, Beschreibung und Bloecke sind Inhalt, den WorkflowContentChangedEvent ankuendigt. */
    @Test
    void apply_reportsContentChange_onlyForNameDescriptionOrBlocks() {
        assertThat(WorkflowRequestFields.apply(workflow(), request(null, "star", "neu"))).isFalse();
        assertThat(WorkflowRequestFields.apply(workflow(), request("Neu", null, null))).isTrue();
        List<BlockDefinition> blocks = List.of(BlockDefinition.builder().type("shell").config(Map.of("command", "true")).build());
        assertThat(WorkflowRequestFields.apply(workflow(),
                new UpdateWorkflowRequest(null, null, null, blocks, null, null, null, null, null))).isTrue();
    }

    @Test
    void apply_leavesEveryFieldTheRequestDoesNotCarry() {
        Workflow workflow = workflow();

        WorkflowRequestFields.apply(workflow, request(null, "star", null));

        assertThat(workflow.getIcon()).isEqualTo("star");
        assertThat(workflow.getName()).isEqualTo("Alt");
        assertThat(workflow.getInstructions()).isEqualTo("alte Anweisungen");
        assertThat(workflow.getModel()).isEqualTo("opus");
        assertThat(workflow.getVersion()).isEqualTo("1.0.0");
    }

    /** Metadaten werden gemischt, der Standard-Kontext ersetzt. */
    @Test
    void apply_mergesMetadata_butReplacesTheDefaultContext() {
        Workflow workflow = workflow();

        WorkflowRequestFields.apply(workflow, new UpdateWorkflowRequest(null, null, null, null, null, null, null,
                Map.of("over", "neu", "add", true), Map.of("b", "2")));

        assertThat(workflow.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of("keep", 1, "over", "neu", "add", true));
        assertThat(workflow.getDefaultContext()).containsExactlyEntriesOf(Map.of("b", "2"));
    }

    /** Von Hand gesetzte Tags oder Kategorie sperren die automatische Verschlagwortung. */
    @Test
    void apply_tagsOrCategory_lockTheAutoTagging() {
        Workflow tagged = workflow();
        WorkflowRequestFields.apply(tagged, new UpdateWorkflowRequest(null, null, null, null, null, null, null,
                null, null, null, List.of("audit"), null, null));
        assertThat(tagged.getTags()).containsExactly("audit");
        assertThat(tagged.isTagsUserEdited()).isTrue();
        assertThat(tagged.getTagsGeneratedAt()).isNotNull();

        Workflow categorized = workflow();
        WorkflowRequestFields.apply(categorized, new UpdateWorkflowRequest(null, null, null, null, null, null, null,
                null, null, null, null, "ops", null));
        assertThat(categorized.getCategory()).isEqualTo("ops");
        assertThat(categorized.isTagsUserEdited()).isTrue();
    }

    /** Eine Wiederherstellung ersetzt jedes versionierte Feld, auch mit null, statt zu mischen. */
    @Test
    void restore_replacesEveryVersionedField_nullIncluded() {
        Workflow workflow = workflow();

        WorkflowRequestFields.restore(workflow, new WorkflowSnapshot(null, null, null, "CODEX", null, null, List.of(), "high"));

        assertThat(workflow.getBlocks()).isEmpty();
        assertThat(workflow.getInstructions()).isNull();
        assertThat(workflow.getModel()).isNull();
        assertThat(workflow.getProvider()).isEqualTo("CODEX");
        assertThat(workflow.getEffort()).isEqualTo("high");
        assertThat(workflow.getMetadata()).isEmpty();
        assertThat(workflow.getDefaultContext()).isEmpty();
        assertThat(workflow.getName()).as("kein versioniertes Feld").isEqualTo("Alt");
    }

    @Test
    void normalizeVersion_trimsAndBoundsTheLabel() {
        assertThat(WorkflowRequestFields.normalizeVersion("  4.5.1 ")).isEqualTo("4.5.1");
        assertThatThrownBy(() -> WorkflowRequestFields.normalizeVersion("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowRequestFields.normalizeVersion("1." + "0".repeat(WorkflowRequestFields.MAX_VERSION_LENGTH)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeEffort_blankClears_unknownIsRejected() {
        assertThat(WorkflowRequestFields.normalizeEffort(" ")).isNull();
        assertThat(WorkflowRequestFields.normalizeEffort(null)).isNull();
        assertThat(WorkflowRequestFields.normalizeEffort("HIGH")).isEqualTo("high");
        assertThatThrownBy(() -> WorkflowRequestFields.normalizeEffort("turbo")).isInstanceOf(IllegalArgumentException.class);
    }
}
