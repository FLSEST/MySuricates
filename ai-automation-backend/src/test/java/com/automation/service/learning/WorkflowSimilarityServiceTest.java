package com.automation.service.learning;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSimilarityServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @InjectMocks private WorkflowSimilarityService service;

    // ── calculateSetSimilarity (Jaccard index) ──────────────────────────

    @Test
    void calculateSetSimilarity_identicalSets_returns1() {
        Set<String> a = Set.of("shell", "simple");
        Set<String> b = Set.of("shell", "simple");

        assertThat(service.calculateSetSimilarity(a, b)).isEqualTo(1.0);
    }

    @Test
    void calculateSetSimilarity_disjointSets_returns0() {
        Set<String> a = Set.of("shell", "simple");
        Set<String> b = Set.of("agentic", "approval");

        assertThat(service.calculateSetSimilarity(a, b)).isEqualTo(0.0);
    }

    @Test
    void calculateSetSimilarity_partialOverlap_returnsJaccardIndex() {
        Set<String> a = Set.of("shell", "simple", "retry");
        Set<String> b = Set.of("shell", "simple", "conditional");

        // intersection={shell,simple}=2, union={shell,simple,retry,conditional}=4 → 0.5
        assertThat(service.calculateSetSimilarity(a, b)).isCloseTo(0.5, within(0.001));
    }

    @Test
    void calculateSetSimilarity_bothEmpty_returns1() {
        assertThat(service.calculateSetSimilarity(Set.of(), Set.of())).isEqualTo(1.0);
    }

    @Test
    void calculateSetSimilarity_oneEmpty_returns0() {
        assertThat(service.calculateSetSimilarity(Set.of("shell"), Set.of())).isEqualTo(0.0);
        assertThat(service.calculateSetSimilarity(Set.of(), Set.of("shell"))).isEqualTo(0.0);
    }

    // ── getSimilarWorkflows ─────────────────────────────────────────────

    @Test
    void getSimilarWorkflows_workflowNotFound_throws() {
        when(workflowRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSimilarWorkflows("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSimilarWorkflows_noBlocks_returnsEmpty() {
        Workflow wf = Workflow.builder().id("wf-1").name("Empty").blocks(List.of()).build();
        // blockTypes column is null, blocks list is empty → empty set
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        List<Map<String, Object>> result = service.getSimilarWorkflows("wf-1");
        assertThat(result).isEmpty();
    }

    @Test
    void getSimilarWorkflows_returnsSimilarAboveThreshold() {
        Workflow wf = Workflow.builder()
                .id("wf-1").name("Main").blockTypes("shell,simple").blocks(List.of()).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        // Other workflows: one similar (>0.5), one not similar (<=0.5)
        List<Object[]> rows = List.of(
                new Object[]{"wf-2", "Similar", "shell,simple,retry"},       // 2/3 = 0.666
                new Object[]{"wf-3", "Different", "agentic,approval,while"} // 0/5 = 0.0
        );
        when(workflowRepository.findAllIdNameBlockTypes("wf-1")).thenReturn(rows);

        List<Map<String, Object>> result = service.getSimilarWorkflows("wf-1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("workflowId")).isEqualTo("wf-2");
        assertThat(result.getFirst().get("workflowName")).isEqualTo("Similar");
        assertThat((Long) result.getFirst().get("similarity")).isEqualTo(67L);
    }

    @Test
    void getSimilarWorkflows_sortsDescendingBySimilarity() {
        Workflow wf = Workflow.builder()
                .id("wf-1").name("Main").blockTypes("shell,simple,retry").blocks(List.of()).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        List<Object[]> rows = List.of(
                new Object[]{"wf-2", "Medium Match", "shell,simple"},         // 2/3 = 0.666
                new Object[]{"wf-3", "High Match", "shell,simple,retry"}      // 3/3 = 1.0
        );
        when(workflowRepository.findAllIdNameBlockTypes("wf-1")).thenReturn(rows);

        List<Map<String, Object>> result = service.getSimilarWorkflows("wf-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("workflowName")).isEqualTo("High Match");
        assertThat(result.get(1).get("workflowName")).isEqualTo("Medium Match");
    }

    @Test
    void getSimilarWorkflows_skipsNullBlockTypes() {
        Workflow wf = Workflow.builder()
                .id("wf-1").name("Main").blockTypes("shell").blocks(List.of()).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        List<Object[]> rows = List.of(
                new Object[]{"wf-2", "NoBlocks", null},
                new Object[]{"wf-3", "EmptyBlocks", ""}
        );
        when(workflowRepository.findAllIdNameBlockTypes("wf-1")).thenReturn(rows);

        List<Map<String, Object>> result = service.getSimilarWorkflows("wf-1");
        assertThat(result).isEmpty();
    }

    @Test
    void getSimilarWorkflows_usesBlockDefinitionsWhenBlockTypesNull() {
        BlockDefinition shellBlock = new BlockDefinition();
        shellBlock.setType("shell");
        BlockDefinition simpleBlock = new BlockDefinition();
        simpleBlock.setType("simple");

        Workflow wf = Workflow.builder()
                .id("wf-1").name("Main").blocks(List.of(shellBlock, simpleBlock)).build();
        // blockTypes is null → falls back to extracting from blocks
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"wf-2", "Match", "shell,simple"});
        when(workflowRepository.findAllIdNameBlockTypes("wf-1")).thenReturn(rows);

        List<Map<String, Object>> result = service.getSimilarWorkflows("wf-1");
        assertThat(result).hasSize(1);
        assertThat((Long) result.getFirst().get("similarity")).isEqualTo(100L);
    }

    @Test
    void getSimilarWorkflows_limitsTo20Results() {
        Workflow wf = Workflow.builder()
                .id("wf-1").name("Main").blockTypes("shell").blocks(List.of()).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));

        // Create 25 workflows that all match perfectly
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(new Object[]{"wf-" + (i + 2), "Match-" + i, "shell"});
        }
        when(workflowRepository.findAllIdNameBlockTypes("wf-1")).thenReturn(rows);

        List<Map<String, Object>> result = service.getSimilarWorkflows("wf-1");
        assertThat(result).hasSize(20);
    }

    @Test
    void getSimilarWorkflows_cacheReturnsFromSecondCall() {
        Workflow wf = Workflow.builder()
                .id("wf-1").name("Main").blockTypes("shell").blocks(List.of()).build();
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));
        List<Object[]> cacheRows = new ArrayList<>();
        cacheRows.add(new Object[]{"wf-2", "Match", "shell"});
        when(workflowRepository.findAllIdNameBlockTypes("wf-1")).thenReturn(cacheRows);

        // First call populates cache
        List<Map<String, Object>> first = service.getSimilarWorkflows("wf-1");
        // Second call should return cached result (same object reference)
        List<Map<String, Object>> second = service.getSimilarWorkflows("wf-1");

        assertThat(first).isSameAs(second);
    }
}
