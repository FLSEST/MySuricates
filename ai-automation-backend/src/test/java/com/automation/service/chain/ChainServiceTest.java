package com.automation.service.chain;

import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.UpdateChainRequest;
import com.automation.entity.ChainEntry;
import com.automation.entity.WorkflowChain;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainServiceTest {

    @Mock
    private WorkflowChainRepository chainRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @InjectMocks
    private ChainService chainService;

    private WorkflowChain testChain;

    @BeforeEach
    void setUp() {
        testChain = WorkflowChain.builder()
                .id("chain-1")
                .name("Test Chain")
                .description("A test chain")
                .icon("link")
                .entries(List.of(
                        ChainEntry.builder()
                                .workflowId("wf-1")
                                .label("Step 1")
                                .sortOrder(0)
                                .stopOnFailure(true)
                                .build()
                ))
                .stopOnFailure(true)
                .build();
    }

    @Test
    void findAll_returnsAllChains() {
        WorkflowChain second = WorkflowChain.builder()
                .id("chain-2")
                .name("Second Chain")
                .build();
        when(chainRepository.findAll()).thenReturn(List.of(testChain, second));

        List<WorkflowChain> result = chainService.findAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(WorkflowChain::getName)
                .containsExactly("Test Chain", "Second Chain");
    }

    @Test
    void findById_existingId_returnsChain() {
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));

        WorkflowChain result = chainService.findById("chain-1");

        assertThat(result.getId()).isEqualTo("chain-1");
        assertThat(result.getName()).isEqualTo("Test Chain");
        assertThat(result.getEntries()).hasSize(1);
    }

    @Test
    void findById_nonExistentId_throwsNoSuchElement() {
        when(chainRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chainService.findById("unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Chain not found: unknown");
    }

    @Test
    void create_validRequest_savesChain() {
        ChainEntryRequest entryRequest = new ChainEntryRequest(
                "wf-1", "Step 1", 0, true, Map.of("key", "value"));
        CreateChainRequest request = new CreateChainRequest(
                "New Chain", "Description", "rocket",
                List.of(entryRequest), true);

        when(workflowRepository.existsById("wf-1")).thenReturn(true);
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> {
            WorkflowChain saved = inv.getArgument(0);
            saved.setId("chain-new");
            return saved;
        });

        WorkflowChain result = chainService.create(request);

        assertThat(result.getId()).isEqualTo("chain-new");
        assertThat(result.getName()).isEqualTo("New Chain");
        assertThat(result.getDescription()).isEqualTo("Description");
        assertThat(result.getIcon()).isEqualTo("rocket");
        assertThat(result.isStopOnFailure()).isTrue();
        assertThat(result.getEntries()).hasSize(1);
        assertThat(result.getEntries().get(0).getWorkflowId()).isEqualTo("wf-1");
        assertThat(result.getEntries().get(0).getLabel()).isEqualTo("Step 1");
        assertThat(result.getEntries().get(0).getInputContext()).containsEntry("key", "value");
        verify(chainRepository).save(any(WorkflowChain.class));
    }

    @Test
    void create_nullStopOnFailure_defaultsToTrue() {
        ChainEntryRequest entryRequest = new ChainEntryRequest(
                "wf-1", "Step 1", 0, null, null);
        CreateChainRequest request = new CreateChainRequest(
                "Chain", null, null, List.of(entryRequest), null);

        when(workflowRepository.existsById("wf-1")).thenReturn(true);
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowChain result = chainService.create(request);

        assertThat(result.isStopOnFailure()).isTrue();
        assertThat(result.getEntries().get(0).isStopOnFailure()).isTrue();
        assertThat(result.getEntries().get(0).getInputContext()).isNotNull().isEmpty();
    }

    @Test
    void create_invalidWorkflowId_throwsIllegalArgument() {
        ChainEntryRequest entryRequest = new ChainEntryRequest(
                "non-existent-wf", "Step 1", 0, true, null);
        CreateChainRequest request = new CreateChainRequest(
                "Chain", null, null, List.of(entryRequest), true);

        when(workflowRepository.existsById("non-existent-wf")).thenReturn(false);

        assertThatThrownBy(() -> chainService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workflow not found: non-existent-wf");

        verify(chainRepository, never()).save(any());
    }

    @Test
    void update_nameOnly_updatesOnlyName() {
        UpdateChainRequest request = new UpdateChainRequest(
                "Updated Name", null, null, null, null);

        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowChain result = chainService.update("chain-1", request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getDescription()).isEqualTo("A test chain");
        assertThat(result.getIcon()).isEqualTo("link");
        assertThat(result.getEntries()).hasSize(1);
        assertThat(result.isStopOnFailure()).isTrue();
    }

    @Test
    void update_entries_validatesAndUpdates() {
        ChainEntryRequest newEntry = new ChainEntryRequest(
                "wf-2", "New Step", 0, false, Map.of("env", "prod"));
        UpdateChainRequest request = new UpdateChainRequest(
                null, null, null, List.of(newEntry), false);

        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(workflowRepository.existsById("wf-2")).thenReturn(true);
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowChain result = chainService.update("chain-1", request);

        assertThat(result.getName()).isEqualTo("Test Chain");
        assertThat(result.isStopOnFailure()).isFalse();
        assertThat(result.getEntries()).hasSize(1);
        assertThat(result.getEntries().get(0).getWorkflowId()).isEqualTo("wf-2");
        assertThat(result.getEntries().get(0).getLabel()).isEqualTo("New Step");
        assertThat(result.getEntries().get(0).isStopOnFailure()).isFalse();
        assertThat(result.getEntries().get(0).getInputContext()).containsEntry("env", "prod");
        verify(workflowRepository).existsById("wf-2");
    }

    @Test
    void delete_existingChain_deletesSuccessfully() {
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));

        chainService.delete("chain-1");

        verify(chainRepository).delete(testChain);
    }
}
