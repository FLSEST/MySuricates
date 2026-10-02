package com.automation.service.chain;

import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.UpdateChainRequest;
import com.automation.entity.ChainEntry;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowChain;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedStateException;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainServiceTest {

    @Mock
    private WorkflowChainRepository chainRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ChainRunRepository chainRunRepository;

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

        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
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

        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
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

        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of());

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
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-2"));
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowChain result = chainService.update("chain-1", request);

        assertThat(result.getName()).isEqualTo("Test Chain");
        assertThat(result.isStopOnFailure()).isFalse();
        assertThat(result.getEntries()).hasSize(1);
        assertThat(result.getEntries().get(0).getWorkflowId()).isEqualTo("wf-2");
        assertThat(result.getEntries().get(0).getLabel()).isEqualTo("New Step");
        assertThat(result.getEntries().get(0).isStopOnFailure()).isFalse();
        assertThat(result.getEntries().get(0).getInputContext()).containsEntry("env", "prod");
        verify(workflowRepository).findExistingIdsIn(Set.of("wf-2"));
    }

    @Test
    void delete_existingChain_deletesSuccessfully() {
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));

        chainService.delete("chain-1");

        verify(chainRepository).delete(testChain);
    }

    /**
     * Ein laufender oder pausierter Ketten-Lauf startet nach dem Loeschen weiter Workflows (und
     * deren Projekt-Container) — Loeschen wird deshalb abgewiesen (409), bis der Lauf vorbei oder
     * abgebrochen ist (Befund aus chains.cy.ts, TODO 413b9f62).
     */
    @Test
    void delete_chainWithActiveRun_isRefusedWithoutDeleting() {
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(chainRunRepository.existsByChainIdAndStatusIn(eq("chain-1"), any())).thenReturn(true);

        assertThatThrownBy(() -> chainService.delete("chain-1"))
                .isInstanceOfSatisfying(LocalizedStateException.class, ex ->
                        assertThat(ex.errorMessage()).isEqualTo(ErrorMessage.CHAIN_RUN_ACTIVE));
        verify(chainRepository, never()).delete(any(WorkflowChain.class));
    }

    @Test
    void delete_checksPendingRunningAndPausedRuns() {
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));

        chainService.delete("chain-1");

        verify(chainRunRepository).existsByChainIdAndStatusIn("chain-1",
                java.util.EnumSet.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.PAUSED));
        verify(chainRepository).delete(testChain);
    }

    @Test
    void findAll_noChains_returnsEmptyList() {
        when(chainRepository.findAll()).thenReturn(List.of());

        List<WorkflowChain> result = chainService.findAll();

        assertThat(result).isEmpty();
    }

    @Test
    void create_explicitStopOnFailureFalse_persistsFalse() {
        ChainEntryRequest entryRequest = new ChainEntryRequest(
                "wf-1", "Step 1", 0, true, Map.of());
        CreateChainRequest request = new CreateChainRequest(
                "Chain", "desc", "icon", List.of(entryRequest), false);
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
        when(chainRepository.save(any(WorkflowChain.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowChain result = chainService.create(request);

        assertThat(result.isStopOnFailure()).isFalse();
    }

    @Test
    void create_mapsEntryFields() {
        ChainEntryRequest entryRequest = new ChainEntryRequest(
                "wf-1", "My Label", 3, true, Map.of("key", "value"));
        CreateChainRequest request = new CreateChainRequest(
                "Chain", "desc", "icon", List.of(entryRequest), true);
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
        ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
        when(chainRepository.save(captor.capture()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        chainService.create(request);

        ChainEntry saved = captor.getValue().getEntries().get(0);
        assertThat(saved.getWorkflowId()).isEqualTo("wf-1");
        assertThat(saved.getLabel()).isEqualTo("My Label");
        assertThat(saved.getSortOrder()).isEqualTo(3);
        assertThat(saved.getInputContext()).containsEntry("key", "value");
    }

    @Test
    void create_entryNullDefaults_appliedByToEntries() {
        ChainEntryRequest entryRequest = new ChainEntryRequest(
                "wf-1", "Step", 0, null, null);
        CreateChainRequest request = new CreateChainRequest(
                "Chain", "desc", "icon", List.of(entryRequest), true);
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
        ArgumentCaptor<WorkflowChain> captor = ArgumentCaptor.forClass(WorkflowChain.class);
        when(chainRepository.save(captor.capture()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        chainService.create(request);

        ChainEntry saved = captor.getValue().getEntries().get(0);
        assertThat(saved.isStopOnFailure()).isTrue();
        assertThat(saved.getInputContext()).isEmpty();
    }

    @Test
    void update_nonExistentChain_throwsNoSuchElement() {
        UpdateChainRequest request = new UpdateChainRequest(
                "New Name", null, null, null, null);
        when(chainRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chainService.update("unknown", request))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Chain not found: unknown");
        verify(chainRepository, never()).save(any());
    }

    @Test
    void update_invalidWorkflowId_throwsIllegalArgument() {
        ChainEntryRequest entryRequest = new ChainEntryRequest(
                "wf-missing", "Step", 0, true, Map.of());
        UpdateChainRequest request = new UpdateChainRequest(
                null, null, null, List.of(entryRequest), null);
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> chainService.update("chain-1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workflow not found: wf-missing");
        verify(chainRepository, never()).save(any());
    }

    @Test
    void update_stopOnFailure_togglesFlag() {
        UpdateChainRequest request = new UpdateChainRequest(
                null, null, null, null, false);
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(chainRepository.save(any(WorkflowChain.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowChain result = chainService.update("chain-1", request);

        assertThat(result.isStopOnFailure()).isFalse();
    }

    @Test
    void update_descriptionAndIcon_updatesOnlyThoseFields() {
        UpdateChainRequest request = new UpdateChainRequest(
                null, "New description", "new-icon", null, null);
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(chainRepository.save(any(WorkflowChain.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowChain result = chainService.update("chain-1", request);

        assertThat(result.getDescription()).isEqualTo("New description");
        assertThat(result.getIcon()).isEqualTo("new-icon");
        assertThat(result.getName()).isEqualTo("Test Chain");
    }

    @Test
    void delete_nonExistentChain_throwsNoSuchElement() {
        when(chainRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chainService.delete("unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Chain not found: unknown");
        verify(chainRepository, never()).delete(any(WorkflowChain.class));
    }

    // --- Chain-Runs ---

    @Test
    void findRunsFiltered_delegatesToRepository() {
        org.springframework.data.domain.Page<ChainRun> page =
                new org.springframework.data.domain.PageImpl<>(List.of());
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, 20);
        when(chainRunRepository.findFiltered(RunStatus.COMPLETED, "foo", pageable)).thenReturn(page);

        assertThat(chainService.findRunsFiltered(RunStatus.COMPLETED, "foo", pageable)).isEqualTo(page);
        verify(chainRunRepository).findFiltered(RunStatus.COMPLETED, "foo", pageable);
    }

    @Test
    void findRunsByChainId_existingChain_returnsRuns() {
        ChainRun run = ChainRun.builder().id("run-1").chain(testChain).build();
        when(chainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(chainRunRepository.findByChainIdOrderByStartedAtDesc("chain-1")).thenReturn(List.of(run));

        List<ChainRun> result = chainService.findRunsByChainId("chain-1");

        assertThat(result).containsExactly(run);
    }

    @Test
    void findRunsByChainId_missingChain_throwsNoSuchElement() {
        when(chainRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chainService.findRunsByChainId("unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Chain not found: unknown");
        verify(chainRunRepository, never()).findByChainIdOrderByStartedAtDesc(any());
    }

    @Test
    void findRunById_existingRun_returnsRun() {
        ChainRun run = ChainRun.builder().id("run-1").chain(testChain).build();
        when(chainRunRepository.findById("run-1")).thenReturn(Optional.of(run));

        assertThat(chainService.findRunById("run-1")).isEqualTo(run);
    }

    @Test
    void findRunById_missingRun_throwsNoSuchElement() {
        when(chainRunRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chainService.findRunById("missing"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Chain run not found: missing");
    }

    @Test
    void create_rejectsEntryTimeoutOutsideRange() {
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
        CreateChainRequest request = new CreateChainRequest("K", null, null,
                List.of(new ChainEntryRequest("wf-1", null, 0, true, Map.of(), 2)), true);

        assertThatThrownBy(() -> chainService.create(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 und 1440");
    }

    @Test
    void create_keepsEntryTimeout() {
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
        when(chainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreateChainRequest request = new CreateChainRequest("K", null, null,
                List.of(new ChainEntryRequest("wf-1", null, 0, true, Map.of(), 1200)), true);

        WorkflowChain saved = chainService.create(request);

        assertThat(saved.getEntries().get(0).getTimeoutMinutes()).isEqualTo(1200);
    }
    /** 6299a860: alle Workflow-IDs in einer Abfrage, gemeldet wird die erste fehlende in Eintragsreihenfolge. */
    @Test
    void create_checksAllWorkflowIdsInOneQuery_andNamesTheFirstMissingInEntryOrder() {
        when(workflowRepository.findExistingIdsIn(any())).thenReturn(List.of("wf-1"));
        CreateChainRequest request = new CreateChainRequest("K", null, null, List.of(
                new ChainEntryRequest("wf-1", null, 0, true, Map.of()),
                new ChainEntryRequest("wf-gone-a", null, 1, true, Map.of()),
                new ChainEntryRequest("wf-gone-b", null, 2, true, Map.of()),
                new ChainEntryRequest("wf-1", null, 3, true, Map.of())), true);

        assertThatThrownBy(() -> chainService.create(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workflow not found: wf-gone-a");
        verify(workflowRepository, times(1)).findExistingIdsIn(Set.of("wf-1", "wf-gone-a", "wf-gone-b"));
        verify(workflowRepository, never()).existsById(any());
        verify(chainRepository, never()).save(any());
    }

    @Test
    void create_withoutEntries_queriesNoWorkflows() {
        when(chainRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        chainService.create(new CreateChainRequest("K", null, null, List.of(), true));

        verifyNoInteractions(workflowRepository);
    }
}
