package com.automation.service.todo;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.exception.ErrorCode;
import com.automation.exception.ResourceNotFoundException;
import com.automation.exception.TaskExportException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.TaskExportCapable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
class TodoTrackerExportServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private IntegrationRegistry integrationRegistry;
    @Mock private IntegrationOAuthService integrationOAuthService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private TodoTrackerExportService service;
    private IntegrationProvider taskProvider;

    @BeforeEach
    void setUp() {
        // TransactionTemplate asks the manager for a status, runs the callback and commits;
        // a mock status keeps the callbacks unchanged and makes commit/rollback no-ops.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new TodoTrackerExportService(todoRepository, projectRepository, integrationRegistry,
                integrationOAuthService, transactionManager);
        taskProvider = mock(IntegrationProvider.class, withSettings().extraInterfaces(TaskExportCapable.class));
    }

    private Todo todo(String projectId) {
        return Todo.builder().id("t-1").title("Fix bug").description("desc")
                .tags("[\"backend\",\"bug\"]").projectId(projectId).build();
    }

    private void stubProject(String providerId, String target) {
        Map<String, Object> cfg = new HashMap<>();
        Map<String, Object> taskExport = new HashMap<>();
        if (providerId != null) taskExport.put("provider", providerId);
        if (target != null) taskExport.put("target", target);
        cfg.put("taskExport", taskExport);
        Project p = Project.builder().id("p-1").name("P").build();
        p.setConfig(cfg);
        when(projectRepository.findById("p-1")).thenReturn(Optional.of(p));
    }

    private void stubConnectedTaskProvider(String providerId) {
        when(integrationRegistry.hasProvider(providerId)).thenReturn(true);
        when(integrationRegistry.getProvider(providerId)).thenReturn(taskProvider);
        when(integrationOAuthService.getConnection(providerId)).thenReturn(Optional.of(
                IntegrationConnection.builder().status(IntegrationConnection.ConnectionStatus.CONNECTED).build()));
    }

    @Test
    void exportToTracker_unknownTodo_throwsResourceNotFound() {
        when(todoRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exportToTracker("missing", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void exportToTracker_alreadyExported_returnsExistingRefWithoutTrackerCall() {
        Todo t = todo(null);
        t.setExportedProvider("GITHUB");
        t.setExportedRef("gh-1");
        t.setExportedUrl("https://x/1");
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(t));

        var ref = service.exportToTracker("t-1", "JIRA", "PROJ");

        assertThat(ref).isEqualTo(new TaskExportCapable.TaskRef("GITHUB", "gh-1", "https://x/1"));
        verify(integrationRegistry, never()).hasProvider(anyString());
        verify(todoRepository, never()).save(any());
    }

    @Test
    void exportToTracker_noProviderAnywhere_throwsNoTaskTarget() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo(null)));

        assertThatThrownBy(() -> service.exportToTracker("t-1", null, null))
                .isInstanceOf(TaskExportException.class)
                .extracting("code").isEqualTo(ErrorCode.NO_TASK_TARGET);
    }

    @Test
    void exportToTracker_unknownProvider_throwsNotTaskCapable() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo(null)));
        when(integrationRegistry.hasProvider("NOPE")).thenReturn(false);

        assertThatThrownBy(() -> service.exportToTracker("t-1", "NOPE", "x"))
                .isInstanceOf(TaskExportException.class)
                .extracting("code").isEqualTo(ErrorCode.PROVIDER_NOT_TASK_CAPABLE);
    }

    @Test
    void exportToTracker_providerWithoutTaskCapability_throwsNotTaskCapable() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo(null)));
        when(integrationRegistry.hasProvider("CAL")).thenReturn(true);
        when(integrationRegistry.getProvider("CAL")).thenReturn(mock(IntegrationProvider.class));

        assertThatThrownBy(() -> service.exportToTracker("t-1", "CAL", "x"))
                .isInstanceOf(TaskExportException.class)
                .extracting("code").isEqualTo(ErrorCode.PROVIDER_NOT_TASK_CAPABLE);
    }

    @Test
    void exportToTracker_providerNotConnected_throwsNotConnected() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo(null)));
        when(integrationRegistry.hasProvider("GITHUB")).thenReturn(true);
        when(integrationRegistry.getProvider("GITHUB")).thenReturn(taskProvider);
        when(integrationOAuthService.getConnection("GITHUB")).thenReturn(Optional.of(
                IntegrationConnection.builder().status(IntegrationConnection.ConnectionStatus.EXPIRED).build()));

        assertThatThrownBy(() -> service.exportToTracker("t-1", "GITHUB", "owner/repo"))
                .isInstanceOf(TaskExportException.class)
                .extracting("code").isEqualTo(ErrorCode.PROVIDER_NOT_CONNECTED);
    }

    @Test
    void exportToTracker_noTarget_throwsNoTaskTarget() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo(null)));
        stubConnectedTaskProvider("GITHUB");

        assertThatThrownBy(() -> service.exportToTracker("t-1", "GITHUB", null))
                .isInstanceOf(TaskExportException.class)
                .extracting("code").isEqualTo(ErrorCode.NO_TASK_TARGET);
        verify(integrationOAuthService, never()).getValidAccessToken(anyString());
    }

    @Test
    void exportToTracker_happyPath_createsTaskAndWritesExportLinkBack() {
        Todo t = todo("p-1");
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(t));
        stubProject("GITHUB", "owner/repo");
        stubConnectedTaskProvider("GITHUB");
        when(integrationOAuthService.getValidAccessToken("GITHUB")).thenReturn("tok");
        var ref = new TaskExportCapable.TaskRef("GITHUB", "42", "https://github.com/owner/repo/issues/42");
        when(((TaskExportCapable) taskProvider).createTask(any(), any())).thenReturn(ref);

        var result = service.exportToTracker("t-1", null, null);

        assertThat(result).isEqualTo(ref);
        ArgumentCaptor<TaskExportCapable.TaskExportInput> input = ArgumentCaptor.forClass(TaskExportCapable.TaskExportInput.class);
        verify((TaskExportCapable) taskProvider).createTask(input.capture(), org.mockito.ArgumentMatchers.eq("tok"));
        assertThat(input.getValue().title()).isEqualTo("Fix bug");
        assertThat(input.getValue().body()).isEqualTo("desc");
        assertThat(input.getValue().labels()).containsExactly("backend", "bug");
        assertThat(input.getValue().target()).isEqualTo("owner/repo");
        assertThat(t.getExportedProvider()).isEqualTo("GITHUB");
        assertThat(t.getExportedRef()).isEqualTo("42");
        assertThat(t.getExportedUrl()).isEqualTo("https://github.com/owner/repo/issues/42");
        verify(todoRepository).save(t);
    }

    @Test
    void exportToTracker_overridesBeatProjectConfig() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo("p-1")));
        stubProject("GITHUB", "owner/repo");
        stubConnectedTaskProvider("JIRA");
        when(integrationOAuthService.getValidAccessToken("JIRA")).thenReturn("tok");
        when(((TaskExportCapable) taskProvider).createTask(any(), any()))
                .thenReturn(new TaskExportCapable.TaskRef("JIRA", "PROJ-1", "u"));

        service.exportToTracker("t-1", "JIRA", "PROJ");

        ArgumentCaptor<TaskExportCapable.TaskExportInput> input = ArgumentCaptor.forClass(TaskExportCapable.TaskExportInput.class);
        verify((TaskExportCapable) taskProvider).createTask(input.capture(), any());
        assertThat(input.getValue().target()).isEqualTo("PROJ");
        verify(integrationRegistry, never()).hasProvider("GITHUB");
    }

    @Test
    void exportToTracker_blankOverrideFallsBackToProjectConfig() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo("p-1")));
        stubProject("GITHUB", "owner/repo");
        stubConnectedTaskProvider("GITHUB");
        when(integrationOAuthService.getValidAccessToken("GITHUB")).thenReturn("tok");
        lenient().when(((TaskExportCapable) taskProvider).createTask(any(), any()))
                .thenReturn(new TaskExportCapable.TaskRef("GITHUB", "1", "u"));

        service.exportToTracker("t-1", "  ", "");

        verify(integrationRegistry).hasProvider("GITHUB");
    }

    @Test
    void exportToTracker_isNotTransactional_httpRunsOutsideTx() throws NoSuchMethodException {
        // TODO f510b330: token refresh + tracker call are HTTP; with pool-size 1 they must not hold the connection.
        var m = TodoTrackerExportService.class.getMethod("exportToTracker", String.class, String.class, String.class);

        assertThat(m.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(TodoTrackerExportService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void exportToTracker_readBurstCommitsBeforeTrackerCall_thenSeparateWriteBurst() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo("p-1")));
        stubProject("GITHUB", "org/repo");
        stubConnectedTaskProvider("GITHUB");
        when(integrationOAuthService.getValidAccessToken("GITHUB")).thenReturn("tok");
        when(((TaskExportCapable) taskProvider).createTask(any(), any()))
                .thenReturn(new TaskExportCapable.TaskRef("GITHUB", "7", "u"));

        service.exportToTracker("t-1", null, null);

        InOrder inOrder = inOrder(transactionManager, integrationOAuthService, taskProvider, todoRepository);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(transactionManager).commit(transactionStatus);
        inOrder.verify(integrationOAuthService).getValidAccessToken("GITHUB");
        inOrder.verify((TaskExportCapable) taskProvider).createTask(any(), any());
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(todoRepository).findById("t-1");
        inOrder.verify(todoRepository).save(any(Todo.class));
        inOrder.verify(transactionManager).commit(transactionStatus);
        verify(transactionManager, times(2)).getTransaction(any());
    }

    @Test
    void exportToTracker_trackerCallFails_propagatesAndWritesNothing() {
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo("p-1")));
        stubProject("GITHUB", "org/repo");
        stubConnectedTaskProvider("GITHUB");
        when(integrationOAuthService.getValidAccessToken("GITHUB")).thenReturn("tok");
        when(((TaskExportCapable) taskProvider).createTask(any(), any())).thenThrow(new IllegalStateException("502"));

        assertThatThrownBy(() -> service.exportToTracker("t-1", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("502");

        verify(todoRepository, never()).save(any());
        verify(transactionManager, times(1)).getTransaction(any());
    }

    @Test
    void exportToTracker_exportedConcurrently_keepsFirstLinkOnWriteBack() {
        Todo fresh = todo("p-1");
        Todo exportedMeanwhile = todo("p-1");
        exportedMeanwhile.setExportedProvider("JIRA");
        exportedMeanwhile.setExportedRef("PROJ-9");
        exportedMeanwhile.setExportedUrl("https://jira/PROJ-9");
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(fresh)).thenReturn(Optional.of(exportedMeanwhile));
        stubProject("GITHUB", "org/repo");
        stubConnectedTaskProvider("GITHUB");
        when(integrationOAuthService.getValidAccessToken("GITHUB")).thenReturn("tok");
        when(((TaskExportCapable) taskProvider).createTask(any(), any()))
                .thenReturn(new TaskExportCapable.TaskRef("GITHUB", "7", "u"));

        TaskExportCapable.TaskRef ref = service.exportToTracker("t-1", null, null);

        assertThat(ref).isEqualTo(new TaskExportCapable.TaskRef("JIRA", "PROJ-9", "https://jira/PROJ-9"));
        verify(todoRepository, never()).save(any());
    }

    @Test
    void parseTags_jsonArrayAndCsvAndBlank() {
        assertThat(TodoTrackerExportService.parseTags("[\"a\", \"b\"]")).containsExactly("a", "b");
        assertThat(TodoTrackerExportService.parseTags("a, b ,,c")).containsExactly("a", "b", "c");
        assertThat(TodoTrackerExportService.parseTags("  ")).isEmpty();
        assertThat(TodoTrackerExportService.parseTags(null)).isEmpty();
        assertThat(TodoTrackerExportService.parseTags("[]")).isEmpty();
        assertThat(TodoTrackerExportService.parseTags(List.of("x").toString())).containsExactly("x");
    }
}
