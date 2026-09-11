package com.automation.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Todo;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.TaskExportCapable;
import com.automation.service.todo.TodoTrackerExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The tracker export talks HTTP (token refresh, tracker create). With HikariCP pool-size 1 that
 * must happen outside any transaction, otherwise the single connection is pinned for the whole
 * round-trip (TODO f510b330). Runs against the real transaction manager and asserts, from inside
 * the mocked tracker call, that no transaction is active — and that the export link is persisted
 * only after a successful call.
 */
@SpringBootTest
@ActiveProfiles("integration")
class TodoTrackerExportIntegrationTest {

    @Autowired private TodoTrackerExportService exportService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TodoRepository todoRepository;
    @MockitoBean private IntegrationRegistry integrationRegistry;
    @MockitoBean private IntegrationOAuthService integrationOAuthService;

    private IntegrationProvider taskProvider;
    private final List<Runnable> cleanup = new ArrayList<>();

    @BeforeEach
    void setUp() {
        taskProvider = mock(IntegrationProvider.class, withSettings().extraInterfaces(TaskExportCapable.class));
        when(integrationRegistry.hasProvider("GITHUB")).thenReturn(true);
        when(integrationRegistry.getProvider("GITHUB")).thenReturn(taskProvider);
        when(integrationOAuthService.getConnection("GITHUB")).thenReturn(Optional.of(
                IntegrationConnection.builder().status(IntegrationConnection.ConnectionStatus.CONNECTED).build()));
    }

    @AfterEach
    void cleanUp() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try { cleanup.get(i).run(); } catch (Exception ignored) { /* already gone */ }
        }
        cleanup.clear();
    }

    private Todo persistedTodo() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("export-it-" + nano).type(ProjectType.CODE)
                .path("/tmp/export-it-" + nano).status(ProjectStatus.STOPPED).build());
        cleanup.add(() -> projectRepository.deleteById(project.getId()));
        Todo todo = todoRepository.save(Todo.builder()
                .title("Export IT todo " + nano).description("desc").tags("[\"backend\"]")
                .projectId(project.getId()).build());
        cleanup.add(() -> todoRepository.deleteById(todo.getId()));
        return todo;
    }

    @Test
    void exportToTracker_httpCallsRunOutsideTransaction_andLinkIsPersisted() {
        Todo todo = persistedTodo();
        AtomicReference<Boolean> txDuringToken = new AtomicReference<>();
        AtomicReference<Boolean> txDuringCreate = new AtomicReference<>();
        when(integrationOAuthService.getValidAccessToken("GITHUB")).thenAnswer(inv -> {
            txDuringToken.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "tok";
        });
        when(((TaskExportCapable) taskProvider).createTask(any(), any())).thenAnswer(inv -> {
            txDuringCreate.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new TaskExportCapable.TaskRef("GITHUB", "42", "https://github.com/org/repo/issues/42");
        });

        TaskExportCapable.TaskRef ref = exportService.exportToTracker(todo.getId(), "GITHUB", "org/repo");

        assertThat(ref.referenceId()).isEqualTo("42");
        assertThat(txDuringToken.get()).isFalse();
        assertThat(txDuringCreate.get()).isFalse();
        Todo reloaded = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(reloaded.getExportedProvider()).isEqualTo("GITHUB");
        assertThat(reloaded.getExportedRef()).isEqualTo("42");
        assertThat(reloaded.getExportedUrl()).isEqualTo("https://github.com/org/repo/issues/42");
    }

    @Test
    void exportToTracker_trackerFails_leavesTodoUnlinked() {
        Todo todo = persistedTodo();
        when(integrationOAuthService.getValidAccessToken("GITHUB")).thenReturn("tok");
        when(((TaskExportCapable) taskProvider).createTask(any(), any())).thenThrow(new IllegalStateException("502"));

        assertThatThrownBy(() -> exportService.exportToTracker(todo.getId(), "GITHUB", "org/repo"))
                .isInstanceOf(IllegalStateException.class);

        Todo reloaded = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(reloaded.getExportedProvider()).isNull();
        assertThat(reloaded.getExportedRef()).isNull();
    }
}
