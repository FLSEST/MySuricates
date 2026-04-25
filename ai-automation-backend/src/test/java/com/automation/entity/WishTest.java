package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WishTest {

    // --- Builder defaults ---

    @Test
    void should_defaultStatusToReceived_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("Build me a workflow").build();
        assertThat(wish.getStatus()).isEqualTo(WishStatus.RECEIVED);
    }

    @Test
    void should_defaultDecompositionToEmptyMap_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.getDecomposition()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultGeneratedWorkflowIdsToEmptyList_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.getGeneratedWorkflowIds()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultReferenceWorkflowIdsToEmptyList_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.getReferenceWorkflowIds()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultIterationCountToZero_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.getIterationCount()).isZero();
    }

    @Test
    void should_defaultMaxIterationsToFive_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.getMaxIterations()).isEqualTo(5);
    }

    @Test
    void should_defaultAutoRefineToFalse_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.isAutoRefine()).isFalse();
    }

    @Test
    void should_defaultIterationHistoryToEmptyList_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.getIterationHistory()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultMetadataToEmptyMap_when_usingBuilderDefaults() {
        Wish wish = Wish.builder().originalText("x").build();
        assertThat(wish.getMetadata()).isNotNull().isEmpty();
    }

    // --- NoArgsConstructor / Setter / field access ---

    @Test
    void should_createInstance_when_usingNoArgsConstructor() {
        Wish wish = new Wish();
        assertThat(wish).isNotNull();
        assertThat(wish.getId()).isNull();
    }

    @Test
    void should_storeId_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setId("wish-123");
        assertThat(wish.getId()).isEqualTo("wish-123");
    }

    @Test
    void should_storeOriginalText_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setOriginalText("Bitte automatisiere meine Wochenberichte");
        assertThat(wish.getOriginalText()).isEqualTo("Bitte automatisiere meine Wochenberichte");
    }

    @Test
    void should_storeStatus_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setStatus(WishStatus.COMPLETED);
        assertThat(wish.getStatus()).isEqualTo(WishStatus.COMPLETED);
    }

    @Test
    void should_storeProjectId_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setProjectId("proj-42");
        assertThat(wish.getProjectId()).isEqualTo("proj-42");
    }

    @Test
    void should_storeDecomposition_when_setViaSetter() {
        Wish wish = new Wish();
        Map<String, Object> decomp = new HashMap<>();
        decomp.put("steps", List.of("step1", "step2"));
        decomp.put("complexity", "medium");

        wish.setDecomposition(decomp);

        assertThat(wish.getDecomposition()).hasSize(2);
        assertThat(wish.getDecomposition()).containsEntry("complexity", "medium");
    }

    @Test
    void should_storeGeneratedWorkflowIds_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setGeneratedWorkflowIds(List.of("wf-1", "wf-2", "wf-3"));
        assertThat(wish.getGeneratedWorkflowIds()).containsExactly("wf-1", "wf-2", "wf-3");
    }

    @Test
    void should_storeReferenceWorkflowIds_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setReferenceWorkflowIds(List.of("ref-a", "ref-b"));
        assertThat(wish.getReferenceWorkflowIds()).containsExactly("ref-a", "ref-b");
    }

    @Test
    void should_storeGeneratedChainId_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setGeneratedChainId("chain-xyz");
        assertThat(wish.getGeneratedChainId()).isEqualTo("chain-xyz");
    }

    @Test
    void should_storeIterationCount_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setIterationCount(3);
        assertThat(wish.getIterationCount()).isEqualTo(3);
    }

    @Test
    void should_storeMaxIterations_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setMaxIterations(10);
        assertThat(wish.getMaxIterations()).isEqualTo(10);
    }

    @Test
    void should_storeAutoRefine_when_setToTrue() {
        Wish wish = new Wish();
        wish.setAutoRefine(true);
        assertThat(wish.isAutoRefine()).isTrue();
    }

    @Test
    void should_storeCurrentRunId_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setCurrentRunId("run-555");
        assertThat(wish.getCurrentRunId()).isEqualTo("run-555");
    }

    @Test
    void should_storeResultSummary_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setResultSummary("Workflow erfolgreich erzeugt und 3x ausgeführt");
        assertThat(wish.getResultSummary()).isEqualTo("Workflow erfolgreich erzeugt und 3x ausgeführt");
    }

    @Test
    void should_storeError_when_setViaSetter() {
        Wish wish = new Wish();
        wish.setError("Decomposition failed: invalid input");
        assertThat(wish.getError()).isEqualTo("Decomposition failed: invalid input");
    }

    @Test
    void should_storeIterationHistory_when_setViaSetter() {
        Wish wish = new Wish();
        Map<String, Object> entry1 = new HashMap<>();
        entry1.put("iteration", 1);
        entry1.put("status", "OK");
        Map<String, Object> entry2 = new HashMap<>();
        entry2.put("iteration", 2);
        entry2.put("status", "FAILED");

        wish.setIterationHistory(List.of(entry1, entry2));

        assertThat(wish.getIterationHistory()).hasSize(2);
        assertThat(wish.getIterationHistory().get(0)).containsEntry("iteration", 1);
    }

    @Test
    void should_storeMetadata_when_setViaSetter() {
        Wish wish = new Wish();
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "user-chat");
        meta.put("priority", "HIGH");

        wish.setMetadata(meta);

        assertThat(wish.getMetadata()).containsEntry("source", "user-chat");
        assertThat(wish.getMetadata()).containsEntry("priority", "HIGH");
    }

    // --- Audit fields ---

    @Test
    void should_defaultCreatedAtToNull_when_newInstance() {
        Wish wish = new Wish();
        assertThat(wish.getCreatedAt()).isNull();
    }

    @Test
    void should_defaultUpdatedAtToNull_when_newInstance() {
        Wish wish = new Wish();
        assertThat(wish.getUpdatedAt()).isNull();
    }

    @Test
    void should_allowSettingCreatedAt_when_usingSetter() {
        Wish wish = new Wish();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        wish.setCreatedAt(created);
        assertThat(wish.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void should_allowSettingUpdatedAt_when_usingSetter() {
        Wish wish = new Wish();
        Instant updated = Instant.parse("2026-04-25T12:00:00Z");
        wish.setUpdatedAt(updated);
        assertThat(wish.getUpdatedAt()).isEqualTo(updated);
    }

    // --- Builder full / chaining ---

    @Test
    void should_buildFullyPopulatedInstance_when_usingBuilderWithAllFields() {
        Map<String, Object> decomp = new HashMap<>(Map.of("complexity", "high"));
        Map<String, Object> meta = new HashMap<>(Map.of("origin", "test"));
        List<String> genIds = List.of("wf-1");
        List<String> refIds = List.of("ref-1", "ref-2");
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(new HashMap<>(Map.of("step", 1)));

        Wish wish = Wish.builder()
                .id("wish-001")
                .originalText("Bitte erstelle einen täglichen Report")
                .status(WishStatus.EXECUTING)
                .projectId("proj-1")
                .decomposition(decomp)
                .generatedWorkflowIds(genIds)
                .referenceWorkflowIds(refIds)
                .generatedChainId("chain-1")
                .iterationCount(2)
                .maxIterations(7)
                .autoRefine(true)
                .currentRunId("run-1")
                .resultSummary("Erste Iteration ok")
                .error(null)
                .iterationHistory(history)
                .metadata(meta)
                .build();

        assertThat(wish.getId()).isEqualTo("wish-001");
        assertThat(wish.getOriginalText()).isEqualTo("Bitte erstelle einen täglichen Report");
        assertThat(wish.getStatus()).isEqualTo(WishStatus.EXECUTING);
        assertThat(wish.getProjectId()).isEqualTo("proj-1");
        assertThat(wish.getDecomposition()).containsEntry("complexity", "high");
        assertThat(wish.getGeneratedWorkflowIds()).containsExactly("wf-1");
        assertThat(wish.getReferenceWorkflowIds()).containsExactly("ref-1", "ref-2");
        assertThat(wish.getGeneratedChainId()).isEqualTo("chain-1");
        assertThat(wish.getIterationCount()).isEqualTo(2);
        assertThat(wish.getMaxIterations()).isEqualTo(7);
        assertThat(wish.isAutoRefine()).isTrue();
        assertThat(wish.getCurrentRunId()).isEqualTo("run-1");
        assertThat(wish.getResultSummary()).isEqualTo("Erste Iteration ok");
        assertThat(wish.getError()).isNull();
        assertThat(wish.getIterationHistory()).hasSize(1);
        assertThat(wish.getMetadata()).containsEntry("origin", "test");
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant now = Instant.parse("2026-04-25T10:00:00Z");
        Map<String, Object> decomp = new HashMap<>(Map.of("k", "v"));
        Map<String, Object> meta = new HashMap<>(Map.of("m", "n"));
        List<String> genIds = new ArrayList<>(List.of("g1"));
        List<String> refIds = new ArrayList<>(List.of("r1"));
        List<Map<String, Object>> history = new ArrayList<>();

        Wish wish = new Wish(
                "id-1",
                "wish text",
                WishStatus.COMPLETED,
                "proj-1",
                decomp,
                genIds,
                refIds,
                "chain-1",
                4,
                8,
                true,
                "run-1",
                "summary",
                "no error",
                history,
                meta,
                now,
                now
        );

        assertThat(wish.getId()).isEqualTo("id-1");
        assertThat(wish.getOriginalText()).isEqualTo("wish text");
        assertThat(wish.getStatus()).isEqualTo(WishStatus.COMPLETED);
        assertThat(wish.getProjectId()).isEqualTo("proj-1");
        assertThat(wish.getDecomposition()).isEqualTo(decomp);
        assertThat(wish.getGeneratedWorkflowIds()).isEqualTo(genIds);
        assertThat(wish.getReferenceWorkflowIds()).isEqualTo(refIds);
        assertThat(wish.getGeneratedChainId()).isEqualTo("chain-1");
        assertThat(wish.getIterationCount()).isEqualTo(4);
        assertThat(wish.getMaxIterations()).isEqualTo(8);
        assertThat(wish.isAutoRefine()).isTrue();
        assertThat(wish.getCurrentRunId()).isEqualTo("run-1");
        assertThat(wish.getResultSummary()).isEqualTo("summary");
        assertThat(wish.getError()).isEqualTo("no error");
        assertThat(wish.getIterationHistory()).isEqualTo(history);
        assertThat(wish.getMetadata()).isEqualTo(meta);
        assertThat(wish.getCreatedAt()).isEqualTo(now);
        assertThat(wish.getUpdatedAt()).isEqualTo(now);
    }

    // --- Collection mutability ---

    @Test
    void should_allowAddingToDefaultGeneratedWorkflowIds_when_listIsMutable() {
        Wish wish = Wish.builder().originalText("x").build();
        wish.getGeneratedWorkflowIds().add("wf-new");
        assertThat(wish.getGeneratedWorkflowIds()).containsExactly("wf-new");
    }

    @Test
    void should_allowAddingToDefaultIterationHistory_when_listIsMutable() {
        Wish wish = Wish.builder().originalText("x").build();
        wish.getIterationHistory().add(Map.of("iteration", 1));
        assertThat(wish.getIterationHistory()).hasSize(1);
    }

    @Test
    void should_allowPuttingIntoDefaultMetadata_when_mapIsMutable() {
        Wish wish = Wish.builder().originalText("x").build();
        wish.getMetadata().put("k", "v");
        assertThat(wish.getMetadata()).containsEntry("k", "v");
    }
}
