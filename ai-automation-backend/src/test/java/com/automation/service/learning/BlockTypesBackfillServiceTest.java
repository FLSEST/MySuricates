package com.automation.service.learning;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockTypesBackfillServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @InjectMocks
    private BlockTypesBackfillService backfillService;

    @Test
    void backfillBlockTypes_noWorkflowsToBackfill_doesNothing() {
        when(workflowRepository.findWithEmptyBlockTypes()).thenReturn(List.of());

        backfillService.backfillBlockTypes();

        verify(workflowRepository, never()).save(any());
    }

    @Test
    void backfillBlockTypes_updatesWorkflowsWithBlocks() {
        Workflow wf = Workflow.builder()
                .id("wf-1")
                .name("Test")
                .blocks(List.of(
                        BlockDefinition.builder().type("simple").build(),
                        BlockDefinition.builder().type("retry").build(),
                        BlockDefinition.builder().type("simple").build()
                ))
                .build();

        when(workflowRepository.findWithEmptyBlockTypes()).thenReturn(List.of(wf));
        when(workflowRepository.save(any())).thenReturn(wf);

        backfillService.backfillBlockTypes();

        verify(workflowRepository).save(wf);
        assertThat(wf.getBlockTypes()).isEqualTo("retry,simple");
    }

    @Test
    void backfillBlockTypes_skipsWorkflowsWithoutBlocks() {
        Workflow wf = Workflow.builder()
                .id("wf-empty")
                .name("Empty")
                .blocks(List.of())
                .build();

        when(workflowRepository.findWithEmptyBlockTypes()).thenReturn(List.of(wf));

        backfillService.backfillBlockTypes();

        verify(workflowRepository, never()).save(any());
    }

    @Test
    void computeBlockTypes_sortedAndDeduplicated() {
        List<BlockDefinition> blocks = List.of(
                BlockDefinition.builder().type("retry").build(),
                BlockDefinition.builder().type("simple").build(),
                BlockDefinition.builder().type("agentic").build(),
                BlockDefinition.builder().type("simple").build()
        );

        String result = BlockTypesBackfillService.computeBlockTypes(blocks);

        assertThat(result).isEqualTo("agentic,retry,simple");
    }

    @Test
    void computeBlockTypes_emptyBlocks_returnsEmpty() {
        assertThat(BlockTypesBackfillService.computeBlockTypes(List.of())).isEmpty();
        assertThat(BlockTypesBackfillService.computeBlockTypes(null)).isEmpty();
    }
}
