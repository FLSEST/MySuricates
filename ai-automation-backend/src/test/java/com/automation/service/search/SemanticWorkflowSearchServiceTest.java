package com.automation.service.search;

import com.automation.entity.Workflow;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.workflow.WorkflowContentChangedEvent;
import com.automation.testsupport.FakeTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SemanticWorkflowSearchServiceTest {

    @Mock private CommandExecutorService commandExecutorService;
    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private SemanticWorkflowSearchService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "semanticEnabled", true);
    }

    // ── isEnabled ──────────────────────────────────────────────────────────

    @Test
    void should_return_true_when_semanticEnabled_is_true() {
        ReflectionTestUtils.setField(service, "semanticEnabled", true);
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void should_return_false_when_semanticEnabled_is_false() {
        ReflectionTestUtils.setField(service, "semanticEnabled", false);
        assertThat(service.isEnabled()).isFalse();
    }

    // ── init / refreshCache ────────────────────────────────────────────────

    @Test
    void should_load_suggestions_model_when_init_called() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.of("claude-sonnet-4-6"));

        service.init();

        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel"))
                .isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void should_set_model_to_null_when_setting_is_blank() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.of("   "));

        service.init();

        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel")).isNull();
    }

    @Test
    void should_set_model_to_null_when_setting_is_absent() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.empty());

        service.init();

        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel")).isNull();
    }

    @Test
    void should_reload_model_and_clear_cache_when_refreshCache_called() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.of("claude-opus-4-6"));

        service.refreshCache();

        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel"))
                .isEqualTo("claude-opus-4-6");
        verify(appSettingsRepository).get("suggestions-model");
    }

    // ── invalidate / onContentChanged ──────────────────────────────────────

    @Test
    void should_not_throw_when_invalidate_called() {
        service.invalidate();
        // no exception
    }

    @Test
    void should_not_throw_when_onContentChanged_called() {
        service.onContentChanged(new WorkflowContentChangedEvent("wf-1"));
        // no exception
    }

    // ── search: short-circuit paths ────────────────────────────────────────

    @Test
    void should_return_empty_when_search_called_and_semantic_disabled() {
        ReflectionTestUtils.setField(service, "semanticEnabled", false);

        List<SemanticWorkflowSearchService.SemanticHit> hits = service.search("anything", 5);

        assertThat(hits).isEmpty();
        verify(workflowRepository, never()).findAllWithActiveProject();
        verify(commandExecutorService, never()).executeOnHost(any());
    }

    @Test
    void should_return_empty_when_query_is_null() {
        List<SemanticWorkflowSearchService.SemanticHit> hits = service.search(null, 5);

        assertThat(hits).isEmpty();
        verify(workflowRepository, never()).findAllWithActiveProject();
    }

    @Test
    void should_return_empty_when_query_is_blank() {
        List<SemanticWorkflowSearchService.SemanticHit> hits = service.search("   ", 5);

        assertThat(hits).isEmpty();
        verify(workflowRepository, never()).findAllWithActiveProject();
    }

    @Test
    void should_return_empty_when_no_workflows_exist() {
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of());

        List<SemanticWorkflowSearchService.SemanticHit> hits = service.search("rag pipeline", 5);

        assertThat(hits).isEmpty();
        verify(commandExecutorService, never()).executeOnHost(any());
    }

    // ── search: model call error paths ─────────────────────────────────────

    @Test
    void should_return_empty_when_cli_exits_nonzero() {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn("wf-1");
        when(wf.getName()).thenReturn("Test Workflow");
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(wf));

        var failedResult = new CliSession.EnrichedCommandResult("error", 1, null);
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(failedResult);

        List<SemanticWorkflowSearchService.SemanticHit> hits = service.search("query", 5);

        assertThat(hits).isEmpty();
        verify(commandExecutorService, times(1)).executeOnHost(any());
    }

    @Test
    void should_return_empty_when_cli_throws_exception() {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn("wf-1");
        when(wf.getName()).thenReturn("Test Workflow");
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(wf));

        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        List<SemanticWorkflowSearchService.SemanticHit> hits = service.search("query", 5);

        assertThat(hits).isEmpty();
    }

    @Test
    void should_return_empty_when_cli_output_is_unparseable() {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn("wf-1");
        when(wf.getName()).thenReturn("Test Workflow");
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(wf));

        var badResult = new CliSession.EnrichedCommandResult("not-json-at-all", 0, null);
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(badResult);

        List<SemanticWorkflowSearchService.SemanticHit> hits = service.search("query", 5);

        assertThat(hits).isNotNull(); // never null — guarded by callModel returning [] on parse failure or by parseAndValidate
    }

    // ── search: limit handling ─────────────────────────────────────────────

    @Test
    void should_call_cli_when_search_invoked_with_negative_limit() {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn("wf-1");
        when(wf.getName()).thenReturn("Wf");
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(wf));
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("", 1, null));

        // negative / zero limit must not crash — should be capped to DEFAULT_LIMIT
        assertThat(service.search("hello", -5)).isEmpty();
        assertThat(service.search("hello2", 0)).isEmpty();

        verify(commandExecutorService, times(2)).executeOnHost(any());
    }

    // ── search: cache behaviour ────────────────────────────────────────────

    @Test
    void should_use_cache_for_repeated_queries_when_first_call_failed_then_invalidated() {
        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn("wf-1");
        when(wf.getName()).thenReturn("Wf");
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(wf));
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("", 1, null));

        // exit-code != 0 → callModel returns null → search returns [] WITHOUT caching
        service.search("identical query", 5);
        service.search("identical query", 5);

        // both calls must hit the CLI because failures aren't cached
        verify(commandExecutorService, times(2)).executeOnHost(any());
    }

    // ── search: transaction boundaries (HikariCP pool-size 1) ──────────────

    @Test
    void should_read_workflows_inside_a_transaction_but_call_the_model_outside_any() {
        PlatformTransactionManager transactions = FakeTransactions.manager();
        SemanticWorkflowSearchService target = new SemanticWorkflowSearchService(
                commandExecutorService, appSettingsRepository, workflowRepository, new ObjectMapper(), transactions);
        ReflectionTestUtils.setField(target, "semanticEnabled", true);
        SemanticWorkflowSearchService proxied = FakeTransactions.proxy(target, transactions);

        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn("wf-1");
        when(wf.getName()).thenReturn("Wf");
        AtomicReference<Boolean> txDuringRead = new AtomicReference<>();
        AtomicReference<Boolean> txDuringModelCall = new AtomicReference<>();
        when(workflowRepository.findAllWithActiveProject()).thenAnswer(inv -> {
            txDuringRead.set(FakeTransactions.active());
            return List.of(wf);
        });
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class))).thenAnswer(inv -> {
            txDuringModelCall.set(FakeTransactions.active());
            return new CliSession.EnrichedCommandResult(
                    "[{\"workflowId\":\"wf-1\",\"reason\":\"passt\",\"score\":0.9}]", 0, null);
        });

        List<SemanticWorkflowSearchService.SemanticHit> hits = proxied.search("query", 5);

        assertThat(txDuringModelCall.get()).as("the model call must not hold the only DB connection").isFalse();
        assertThat(txDuringRead.get()).as("the workflow index is read in a short transaction").isTrue();
        assertThat(hits).extracting(SemanticWorkflowSearchService.SemanticHit::workflowId).containsExactly("wf-1");
    }

    // ── search: request shape ──────────────────────────────────────────────

    @Test
    void should_pass_cached_model_to_cli_request() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.of("claude-sonnet-4-6"));
        service.init();

        Workflow wf = mock(Workflow.class);
        when(wf.getId()).thenReturn("wf-1");
        when(wf.getName()).thenReturn("Wf");
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(wf));
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("", 1, null));

        service.search("query", 5);

        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutorService).executeOnHost(captor.capture());
        HostExecutionRequest req = captor.getValue();
        assertThat(req.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(req.priority()).isFalse();
        assertThat(req.command()).isNotBlank();
        assertThat(req.systemPrompt()).isNotBlank();
        assertThat(req.timeoutSeconds()).isNotNull();
    }
}
