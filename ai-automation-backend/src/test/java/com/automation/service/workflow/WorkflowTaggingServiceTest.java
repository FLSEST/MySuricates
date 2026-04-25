package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.cli.CommandExecutorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowTaggingServiceTest {

    @Mock private CommandExecutorService commandExecutorService;
    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private WorkflowRepository workflowRepository;

    private ObjectMapper objectMapper;
    private WorkflowTaggingService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Build a real instance; self-proxy is set to "this" via reflection so
        // self.* calls inside the service hit the same instance.
        service = new WorkflowTaggingService(
                commandExecutorService,
                appSettingsRepository,
                workflowRepository,
                objectMapper,
                null
        );
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "autoTaggingEnabled", true);
    }

    // -------- init / refreshCache --------

    @Test
    void should_cacheSuggestionsModel_when_init() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.of("claude-opus-4-6"));

        service.init();

        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel"))
                .isEqualTo("claude-opus-4-6");
    }

    @Test
    void should_cacheNull_when_initSettingMissing() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.empty());

        service.init();

        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel")).isNull();
    }

    @Test
    void should_cacheNull_when_initSettingBlank() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.of("   "));

        service.init();

        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel")).isNull();
    }

    @Test
    void should_updateCache_when_refreshCacheCalled() {
        when(appSettingsRepository.get("suggestions-model"))
                .thenReturn(Optional.of("first"))
                .thenReturn(Optional.of("second"));

        service.init();
        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel")).isEqualTo("first");

        service.refreshCache();
        assertThat(ReflectionTestUtils.getField(service, "cachedSuggestionsModel")).isEqualTo("second");
    }

    // -------- isEnabled --------

    @Test
    void should_returnTrue_when_autoTaggingEnabled() {
        ReflectionTestUtils.setField(service, "autoTaggingEnabled", true);
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void should_returnFalse_when_autoTaggingDisabled() {
        ReflectionTestUtils.setField(service, "autoTaggingEnabled", false);
        assertThat(service.isEnabled()).isFalse();
    }

    // -------- listUntaggedIds --------

    @Test
    void should_returnIds_when_listUntaggedIdsCalled() {
        Workflow w1 = new Workflow();
        w1.setId("id-1");
        Workflow w2 = new Workflow();
        w2.setId("id-2");
        when(workflowRepository.findUntagged()).thenReturn(List.of(w1, w2));

        List<String> ids = service.listUntaggedIds();

        assertThat(ids).containsExactly("id-1", "id-2");
    }

    @Test
    void should_returnEmpty_when_noUntaggedWorkflows() {
        when(workflowRepository.findUntagged()).thenReturn(List.of());

        assertThat(service.listUntaggedIds()).isEmpty();
    }

    // -------- loadAndBuildPrompt --------

    @Test
    void should_returnNull_when_workflowNotFound() {
        when(workflowRepository.findByIdWithSkills("missing")).thenReturn(Optional.empty());

        assertThat(service.loadAndBuildPrompt("missing")).isNull();
    }

    @Test
    void should_returnNull_when_workflowUserEdited() {
        Workflow w = new Workflow();
        w.setId("wf-1");
        w.setName("My WF");
        w.setTagsUserEdited(true);
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(w));

        assertThat(service.loadAndBuildPrompt("wf-1")).isNull();
    }

    @Test
    void should_returnPrompt_when_workflowEligible() {
        Workflow w = new Workflow();
        w.setId("wf-1");
        w.setName("Daily Cleanup");
        w.setDescription("Removes stale files");
        w.setTagsUserEdited(false);
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(w));

        String prompt = service.loadAndBuildPrompt("wf-1");

        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("Daily Cleanup");
    }

    // -------- persistTags --------

    @Test
    void should_persistTags_when_workflowEligible() {
        Workflow w = new Workflow();
        w.setId("wf-1");
        w.setTagsUserEdited(false);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(w));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowTaggingService.TagResult result =
                new WorkflowTaggingService.TagResult("Dev", List.of("backend", "automation"));

        boolean ok = service.persistTags("wf-1", result);

        assertThat(ok).isTrue();
        assertThat(w.getCategory()).isEqualTo("Dev");
        assertThat(w.getTags()).containsExactly("backend", "automation");
        assertThat(w.getTagsGeneratedAt()).isNotNull();
        verify(workflowRepository).save(w);
    }

    @Test
    void should_skipPersist_when_workflowMissing() {
        // findById returns Optional.empty() by default from Mockito — no stub needed.
        WorkflowTaggingService.TagResult result =
                new WorkflowTaggingService.TagResult("Dev", List.of("a", "b"));

        boolean ok = service.persistTags("missing", result);

        assertThat(ok).isFalse();
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void should_skipPersist_when_userEditedConcurrently() {
        Workflow w = new Workflow();
        w.setId("wf-1");
        w.setTagsUserEdited(true);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(w));

        WorkflowTaggingService.TagResult result =
                new WorkflowTaggingService.TagResult("Dev", List.of("a", "b"));

        boolean ok = service.persistTags("wf-1", result);

        assertThat(ok).isFalse();
        verify(workflowRepository, never()).save(any());
    }

    // -------- tagSingle --------

    @Test
    void should_returnFalse_when_tagSingleAndDisabled() {
        ReflectionTestUtils.setField(service, "autoTaggingEnabled", false);

        assertThat(service.tagSingle("wf-1")).isFalse();
        verifyNoInteractions(workflowRepository);
        verifyNoInteractions(commandExecutorService);
    }

    @Test
    void should_returnFalse_when_tagSinglePromptIsNull() {
        when(workflowRepository.findByIdWithSkills("missing")).thenReturn(Optional.empty());

        boolean ok = service.tagSingle("missing");

        assertThat(ok).isFalse();
        verifyNoInteractions(commandExecutorService);
    }

    // -------- onContentChanged --------

    @Test
    void should_doNothing_when_onContentChangedAndDisabled() {
        ReflectionTestUtils.setField(service, "autoTaggingEnabled", false);

        service.onContentChanged(new WorkflowContentChangedEvent("wf-1"));

        verifyNoInteractions(workflowRepository);
        verifyNoInteractions(commandExecutorService);
    }

    @Test
    void should_attemptTagging_when_onContentChangedAndEnabled() {
        // Workflow not found → tagSingle returns false, no CLI call.
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.empty());

        service.onContentChanged(new WorkflowContentChangedEvent("wf-1"));

        verify(workflowRepository).findByIdWithSkills("wf-1");
        verifyNoInteractions(commandExecutorService);
    }

    @Test
    void should_swallowException_when_onContentChangedFails() {
        when(workflowRepository.findByIdWithSkills(anyString()))
                .thenThrow(new RuntimeException("DB down"));

        // Should not throw.
        service.onContentChanged(new WorkflowContentChangedEvent("wf-1"));

        verify(workflowRepository).findByIdWithSkills("wf-1");
    }

    // -------- backfillUntagged --------

    @Test
    void should_skip_when_backfillAndDisabled() {
        ReflectionTestUtils.setField(service, "autoTaggingEnabled", false);

        service.backfillUntagged();

        verifyNoInteractions(workflowRepository);
    }

    @Test
    void should_doNothing_when_backfillAndNoUntagged() {
        when(workflowRepository.findUntagged()).thenReturn(List.of());

        service.backfillUntagged();

        verify(workflowRepository).findUntagged();
        verifyNoInteractions(commandExecutorService);
    }

    @Test
    void should_processIds_when_backfillFindsUntagged() {
        Workflow w1 = new Workflow();
        w1.setId("a");
        Workflow w2 = new Workflow();
        w2.setId("b");
        when(workflowRepository.findUntagged()).thenReturn(List.of(w1, w2));
        // Both workflows missing on second lookup → tagSingle returns false; no CLI call.
        when(workflowRepository.findByIdWithSkills(anyString())).thenReturn(Optional.empty());

        service.backfillUntagged();

        verify(workflowRepository).findUntagged();
        verify(workflowRepository).findByIdWithSkills("a");
        verify(workflowRepository).findByIdWithSkills("b");
        verifyNoInteractions(commandExecutorService);
    }

    // -------- onAppReady --------

    @Test
    void should_triggerBackfill_when_onAppReady() {
        when(workflowRepository.findUntagged()).thenReturn(List.of());

        service.onAppReady();

        verify(workflowRepository).findUntagged();
    }

    // -------- TagResult record --------

    @Test
    void should_exposeFields_when_tagResultCreated() {
        WorkflowTaggingService.TagResult r =
                new WorkflowTaggingService.TagResult("Dev", List.of("x", "y"));

        assertThat(r.category()).isEqualTo("Dev");
        assertThat(r.tags()).containsExactly("x", "y");
    }
}
