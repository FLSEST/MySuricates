package com.automation.dto;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSnapshotTest {

    // --- from() factory method ---

    @Test
    void from_fullWorkflow_copiesAllFields() {
        Skill skill1 = Skill.builder().id("skill-1").name("Lint").build();
        Skill skill2 = Skill.builder().id("skill-2").name("Test").build();

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo hi")))
                .build();

        Workflow workflow = Workflow.builder()
                .blocks(new ArrayList<>(List.of(block)))
                .instructions("Do the thing")
                .model("claude-opus-4-6")
                .provider("CLAUDE")
                .metadata(new HashMap<>(Map.of("autoTodo", true)))
                .defaultContext(new HashMap<>(Map.of("env", "prod")))
                .activatedSkills(new ArrayList<>(List.of(skill1, skill2)))
                .build();

        WorkflowSnapshot snapshot = WorkflowSnapshot.from(workflow);

        assertThat(snapshot.blocks()).containsExactly(block);
        assertThat(snapshot.instructions()).isEqualTo("Do the thing");
        assertThat(snapshot.model()).isEqualTo("claude-opus-4-6");
        assertThat(snapshot.provider()).isEqualTo("CLAUDE");
        assertThat(snapshot.metadata()).containsEntry("autoTodo", true);
        assertThat(snapshot.defaultContext()).containsEntry("env", "prod");
        assertThat(snapshot.activatedSkillIds()).containsExactly("skill-1", "skill-2");
    }

    @Test
    void from_nullBlocks_returnsEmptyList() {
        Workflow workflow = Workflow.builder().build();
        workflow.setBlocks(null);

        WorkflowSnapshot snapshot = WorkflowSnapshot.from(workflow);

        assertThat(snapshot.blocks()).isEmpty();
    }

    @Test
    void from_nullMetadata_returnsEmptyMap() {
        Workflow workflow = Workflow.builder().build();
        workflow.setMetadata(null);

        WorkflowSnapshot snapshot = WorkflowSnapshot.from(workflow);

        assertThat(snapshot.metadata()).isEmpty();
    }

    @Test
    void from_nullDefaultContext_returnsEmptyMap() {
        Workflow workflow = Workflow.builder().build();
        workflow.setDefaultContext(null);

        WorkflowSnapshot snapshot = WorkflowSnapshot.from(workflow);

        assertThat(snapshot.defaultContext()).isEmpty();
    }

    @Test
    void from_nullActivatedSkills_returnsEmptyList() {
        Workflow workflow = Workflow.builder().build();
        workflow.setActivatedSkills(null);

        WorkflowSnapshot snapshot = WorkflowSnapshot.from(workflow);

        assertThat(snapshot.activatedSkillIds()).isEmpty();
    }

    @Test
    void from_nullInstructionsModelProvider_preservesNulls() {
        Workflow workflow = Workflow.builder().build();
        workflow.setInstructions(null);
        workflow.setModel(null);
        workflow.setProvider(null);

        WorkflowSnapshot snapshot = WorkflowSnapshot.from(workflow);

        assertThat(snapshot.instructions()).isNull();
        assertThat(snapshot.model()).isNull();
        assertThat(snapshot.provider()).isNull();
    }

    @Test
    void from_createsDefensiveCopies() {
        List<BlockDefinition> originalBlocks = new ArrayList<>();
        originalBlocks.add(BlockDefinition.builder().type("shell").build());

        Map<String, Object> originalMeta = new HashMap<>(Map.of("key", "val"));
        Map<String, Object> originalCtx = new HashMap<>(Map.of("var", "x"));

        Workflow workflow = Workflow.builder()
                .blocks(originalBlocks)
                .metadata(originalMeta)
                .defaultContext(originalCtx)
                .build();

        WorkflowSnapshot snapshot = WorkflowSnapshot.from(workflow);

        // Mutating originals should not affect the snapshot
        originalBlocks.add(BlockDefinition.builder().type("simple").build());
        originalMeta.put("new", "entry");
        originalCtx.put("new", "entry");

        assertThat(snapshot.blocks()).hasSize(1);
        assertThat(snapshot.metadata()).doesNotContainKey("new");
        assertThat(snapshot.defaultContext()).doesNotContainKey("new");
    }

    // --- differs() ---

    @Test
    void differs_identicalSnapshots_returnsFalse() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), "instr", "model", "provider",
                Map.of("k", "v"), Map.of("c", "d"), List.of("s1"));
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), "instr", "model", "provider",
                Map.of("k", "v"), Map.of("c", "d"), List.of("s1"));

        assertThat(a.differs(b)).isFalse();
    }

    @Test
    void differs_bothNullStrings_returnsFalse() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of(), List.of());

        assertThat(a.differs(b)).isFalse();
    }

    @Test
    void differs_differentBlocks_returnsTrue() {
        BlockDefinition block1 = BlockDefinition.builder().type("shell").build();
        BlockDefinition block2 = BlockDefinition.builder().type("simple").build();

        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(block1), null, null, null,
                Map.of(), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(block2), null, null, null,
                Map.of(), Map.of(), List.of());

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_differentInstructions_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), "v1", null, null,
                Map.of(), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), "v2", null, null,
                Map.of(), Map.of(), List.of());

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_differentModel_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, "opus", null,
                Map.of(), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), null, "sonnet", null,
                Map.of(), Map.of(), List.of());

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_differentProvider_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, null, "CLAUDE",
                Map.of(), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), null, null, "CODEX",
                Map.of(), Map.of(), List.of());

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_differentMetadata_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of("a", 1), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of("a", 2), Map.of(), List.of());

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_differentDefaultContext_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of("x", "1"), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of("x", "2"), List.of());

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_differentSkillIds_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of(), List.of("s1"));
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of(), List.of("s2"));

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_nullVsNonNullInstruction_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(), "something", null, null,
                Map.of(), Map.of(), List.of());

        assertThat(a.differs(b)).isTrue();
    }

    @Test
    void differs_emptyVsPopulatedCollections_returnsTrue() {
        WorkflowSnapshot a = new WorkflowSnapshot(
                List.of(), null, null, null,
                Map.of(), Map.of(), List.of());
        WorkflowSnapshot b = new WorkflowSnapshot(
                List.of(BlockDefinition.builder().type("shell").build()),
                null, null, null,
                Map.of(), Map.of(), List.of());

        assertThat(a.differs(b)).isTrue();
    }
}
