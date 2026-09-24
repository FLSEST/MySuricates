package com.automation.service.workflow;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.repository.ContainerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.project.DockerContainerGateway;
import com.automation.service.project.DockerExpectation;
import com.automation.service.project.DockerOrphanReconciler;
import com.automation.service.project.ProjectContainerClaudeAccess;
import com.automation.util.HostProcessUtils;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdleContainerReaperTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ContainerRepository containerRepository;

    @Mock
    private WorkflowExecutionRegistry executionRegistry;

    @Mock
    private ActivityLogService activityLogService;

    @Mock
    private HostProcessUtils hostProcessUtils;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private Project testProject;

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute() asks the manager for a status, runs the callback,
        // then commits — a mock status turns commit/rollback into no-ops.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        testProject = Project.builder()
                .id("project-1")
                .name("Test Project")
                .path("/test/project")
                .type(ProjectType.CODE)
                .status(ProjectStatus.RUNNING)
                .build();
    }

    private IdleContainerReaper reaper(String dockerHost) {
        DockerContainerGateway docker = new DockerContainerGateway(
                dockerClient, hostProcessUtils, mock(ProjectContainerClaudeAccess.class),
                100_000L, 4_294_967_296L, 100L);
        return new IdleContainerReaper(
                containerRepository,
                executionRegistry,
                activityLogService,
                transactionManager,
                docker,
                new DockerOrphanReconciler(docker, activityLogService, 60),
                new DockerExpectation(dockerClient, dockerHost),
                60);                // idleTimeoutMinutes
    }

    private IdleContainerReaper reaper() {
        return reaper("tcp://docker.test:2375"); // Docker eingerichtet
    }

    private void stubDockerRemoveCommands() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        lenient().when(dockerClient.stopContainerCmd(anyString())).thenReturn(stopCmd);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        lenient().when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
    }

    private void stubDockerList(List<com.github.dockerjava.api.model.Container> containers) {
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        lenient().when(listCmd.withNameFilter(any())).thenReturn(listCmd);
        lenient().when(listCmd.exec()).thenReturn(containers);
        lenient().when(dockerClient.listContainersCmd()).thenReturn(listCmd);
    }

    private com.github.dockerjava.api.model.Container dockerContainer(String id, Instant created) {
        var dc = mock(com.github.dockerjava.api.model.Container.class);
        lenient().when(dc.getId()).thenReturn(id);
        lenient().when(dc.getCreated()).thenReturn(created.getEpochSecond());
        return dc;
    }

    private Container runningRow(String dockerId, Instant lastActivity) {
        return Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId(dockerId)
                .status(ContainerStatus.RUNNING)
                .lastActivityAt(lastActivity)
                .startedAt(lastActivity)
                .build();
    }

    @Test
    void cleanupIdle_idleContainer_stopsIt() {
        Container idle = runningRow("docker-idle", Instant.now().minus(120, ChronoUnit.MINUTES));
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of(idle));
        when(containerRepository.findById("c1")).thenReturn(Optional.of(idle));
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerRemoveCommands();
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        reaper().cleanupIdleContainers();

        assertThat(idle.getStatus()).isEqualTo(ContainerStatus.STOPPED);
        assertThat(idle.getStoppedAt()).isNotNull();
        assertThat(testProject.getStatus()).isEqualTo(ProjectStatus.STOPPED);
        verify(dockerClient).stopContainerCmd("docker-idle");
        verify(dockerClient).removeContainerCmd("docker-idle");
        verify(containerRepository).save(idle);
    }

    @Test
    void cleanupIdle_recentlyActiveContainer_keepsRunning() {
        Container fresh = runningRow("docker-fresh", Instant.now().minus(5, ChronoUnit.MINUTES));
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of(fresh));
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());

        reaper().cleanupIdleContainers();

        assertThat(fresh.getStatus()).isEqualTo(ContainerStatus.RUNNING);
        verify(dockerClient, never()).stopContainerCmd(anyString());
        verify(containerRepository, never()).save(any());
    }

    @Test
    void cleanupIdle_idleContainerWithActiveExecution_skipped() {
        Container busy = runningRow("docker-busy", Instant.now().minus(120, ChronoUnit.MINUTES));
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of(busy));
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of("docker-busy"));

        reaper().cleanupIdleContainers();

        assertThat(busy.getStatus()).isEqualTo(ContainerStatus.RUNNING);
        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    @Test
    void cleanupIdle_dockerNotConfigured_doesNothing() {
        reaper("").cleanupIdleContainers();

        verifyNoInteractions(containerRepository, executionRegistry, dockerClient);
    }

    // --- Reconciliation: Docker-Container ohne RUNNING-DB-Zeile (State-Drift) ---

    @Test
    void reconcile_orphanedDockerContainer_isForceStopped() {
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of());
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerList(List.of(dockerContainer("docker-orphan", Instant.now().minus(2, ChronoUnit.HOURS))));
        stubDockerRemoveCommands();

        reaper().cleanupIdleContainers();

        verify(dockerClient).stopContainerCmd("docker-orphan");
        verify(dockerClient).removeContainerCmd("docker-orphan");
    }

    @Test
    void reconcile_dockerContainerWithRunningDbRow_notTouched() {
        Container tracked = runningRow("docker-known", Instant.now());
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of(tracked));
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerList(List.of(dockerContainer("docker-known", Instant.now().minus(2, ChronoUnit.HOURS))));

        reaper().cleanupIdleContainers();

        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    @Test
    void reconcile_recentlyCreatedOrphan_skippedByGracePeriod() {
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of());
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerList(List.of(dockerContainer("docker-fresh", Instant.now())));

        reaper().cleanupIdleContainers();

        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    @Test
    void reconcile_containerWithActiveExecution_skipped() {
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of());
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of("docker-busy"));
        stubDockerList(List.of(dockerContainer("docker-busy", Instant.now().minus(2, ChronoUnit.HOURS))));

        reaper().cleanupIdleContainers();

        verify(dockerClient, never()).stopContainerCmd(anyString());
    }
}
