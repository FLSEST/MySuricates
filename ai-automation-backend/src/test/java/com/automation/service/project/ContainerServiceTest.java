package com.automation.service.project;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.exception.ContainerException;
import com.automation.repository.ContainerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import com.automation.util.HostProcessUtils;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerServiceTest {

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
    private PathValidator pathValidator;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private ContainerService service;

    private Project testProject;

    @BeforeEach
    void setUp() {
        lenient().when(hostProcessUtils.getHostUid()).thenReturn("1000");
        // TransactionTemplate.execute() asks the manager for a status, runs the
        // callback, then commits — returning a mock status lets the callback
        // run unchanged and turns commit/rollback into no-ops, which is what
        // the unit test wants.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        DockerContainerGateway docker = new DockerContainerGateway(
                dockerClient, hostProcessUtils,
                100_000L,           // cpuQuota
                4_294_967_296L,     // memoryLimit (4GB)
                100L                // pidsLimit
        );
        service = new ContainerService(
                containerRepository,
                executionRegistry,
                activityLogService,
                pathValidator,
                transactionManager,
                docker,
                new DockerOrphanReconciler(docker, activityLogService, 60),
                60,                 // idleTimeoutMinutes
                "/home/test"        // hostHome
        );

        testProject = Project.builder()
                .id("project-1")
                .name("Test Project")
                .path("/test/project")
                .type(ProjectType.CODE)
                .build();
    }

    private void stubDockerRemoveCommands() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(stopCmd.withTimeout(anyInt())).thenReturn(stopCmd);
        lenient().when(dockerClient.stopContainerCmd(anyString())).thenReturn(stopCmd);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
        lenient().when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
    }

    private void stubDockerInspectRunning(String containerId) {
        InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(true);
        when(inspectResponse.getState()).thenReturn(state);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
    }

    private void stubDockerInspectNotRunning(String containerId) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenThrow(new RuntimeException("not found"));
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
    }

    @SuppressWarnings("unchecked")
    private void stubDockerCreateAndStart(String responseId) {
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn(responseId);

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_DEEP_STUBS);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any())).thenReturn(createCmd);
        when(createCmd.withUser(anyString())).thenReturn(createCmd);
        when(createCmd.withWorkingDir(anyString())).thenReturn(createCmd);
        when(createCmd.withCmd(any(String[].class))).thenReturn(createCmd);
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(responseId)).thenReturn(startCmd);
    }

    @Test
    void createAndStart_noExisting_createsNewContainer() {
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());
        stubDockerRemoveCommands();
        stubDockerCreateAndStart("docker-123");
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        Container result = service.createAndStartContainer(testProject);

        assertThat(result.getDockerContainerId()).isEqualTo("docker-123");
        assertThat(result.getStatus()).isEqualTo(ContainerStatus.RUNNING);
        verify(containerRepository).save(any(Container.class));
    }

    @Test
    void createAndStart_existingRunning_reusesContainer() {
        Container existing = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-existing")
                .status(ContainerStatus.RUNNING)
                .build();
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.of(existing));
        stubDockerInspectRunning("docker-existing");

        Container result = service.createAndStartContainer(testProject);

        assertThat(result.getDockerContainerId()).isEqualTo("docker-existing");
        verify(dockerClient, never()).createContainerCmd(anyString());
    }

    @Test
    void createAndStart_existingStale_removesAndCreatesNew() {
        Container stale = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-stale")
                .status(ContainerStatus.RUNNING)
                .build();
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.of(stale));
        stubDockerInspectNotRunning("docker-stale");
        stubDockerRemoveCommands();
        stubDockerCreateAndStart("docker-new");
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        Container result = service.createAndStartContainer(testProject);

        assertThat(result.getDockerContainerId()).isEqualTo("docker-new");
        verify(containerRepository).deleteById("c1");
    }

    @Test
    void createAndStart_dockerFails_throwsContainerException() {
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());
        stubDockerRemoveCommands();
        when(dockerClient.createContainerCmd(anyString())).thenThrow(new RuntimeException("Docker error"));

        assertThatThrownBy(() -> service.createAndStartContainer(testProject))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Failed to create container");
    }

    @Test
    void stopContainer_existingContainer_stopsAndUpdates() {
        Container container = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-stop")
                .status(ContainerStatus.RUNNING)
                .build();
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.of(container));
        when(containerRepository.findById("c1")).thenReturn(Optional.of(container));
        stubDockerRemoveCommands();
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        service.stopContainer(testProject);

        assertThat(container.getStatus()).isEqualTo(ContainerStatus.STOPPED);
        assertThat(container.getStoppedAt()).isNotNull();
        verify(containerRepository).save(container);
    }

    @Test
    void stopContainer_noContainer_doesNothing() {
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());

        service.stopContainer(testProject);

        verify(containerRepository, never()).save(any());
    }

    @Test
    void getDockerContainerId_runningContainer_returnsId() {
        Container container = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-running")
                .status(ContainerStatus.RUNNING)
                .build();
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.of(container));
        stubDockerInspectRunning("docker-running");

        String id = service.getDockerContainerId(testProject);

        assertThat(id).isEqualTo("docker-running");
        // Read burst committed before the Docker inspect; no write burst on the happy path.
        InOrder inOrder = inOrder(transactionManager, dockerClient);
        inOrder.verify(transactionManager).commit(transactionStatus);
        inOrder.verify(dockerClient).inspectContainerCmd("docker-running");
        verify(transactionManager, times(1)).getTransaction(any());
        verify(containerRepository, never()).save(any());
    }

    @Test
    void getDockerContainerId_staleContainer_correctsStatus() {
        Container container = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-gone")
                .status(ContainerStatus.RUNNING)
                .build();
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.of(container));
        when(containerRepository.findById("c1")).thenReturn(Optional.of(container));
        stubDockerInspectNotRunning("docker-gone");
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.getDockerContainerId(testProject))
                .isInstanceOf(ContainerException.class);

        assertThat(container.getStatus()).isEqualTo(ContainerStatus.STOPPED);
        assertThat(container.getStoppedAt()).isNotNull();
        // Read burst → Docker inspect outside any transaction → separate write burst (re-loaded by id).
        InOrder inOrder = inOrder(transactionManager, dockerClient, containerRepository);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(transactionManager).commit(transactionStatus);
        inOrder.verify(dockerClient).inspectContainerCmd("docker-gone");
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(containerRepository).findById("c1");
        inOrder.verify(containerRepository).save(container);
        inOrder.verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void getDockerContainerId_noContainer_throwsException() {
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDockerContainerId(testProject))
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("No running container");
    }

    @Test
    void cleanupIdle_idleContainer_stopsIt() {
        Container idle = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-idle")
                .status(ContainerStatus.RUNNING)
                .lastActivityAt(Instant.now().minus(120, ChronoUnit.MINUTES))
                .startedAt(Instant.now().minus(120, ChronoUnit.MINUTES))
                .build();
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of(idle));
        when(containerRepository.findById("c1")).thenReturn(Optional.of(idle));
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerRemoveCommands();
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cleanupIdleContainers();

        assertThat(idle.getStatus()).isEqualTo(ContainerStatus.STOPPED);
        verify(containerRepository).save(idle);
    }

    // --- Reconciliation: Docker-Container ohne RUNNING-DB-Zeile (State-Drift) ---

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

    @Test
    void reconcile_orphanedDockerContainer_isForceStopped() {
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of());
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerList(List.of(dockerContainer("docker-orphan", Instant.now().minus(2, ChronoUnit.HOURS))));
        stubDockerRemoveCommands();

        service.cleanupIdleContainers();

        verify(dockerClient).stopContainerCmd("docker-orphan");
        verify(dockerClient).removeContainerCmd("docker-orphan");
    }

    @Test
    void reconcile_dockerContainerWithRunningDbRow_notTouched() {
        Container tracked = Container.builder()
                .id("c1").project(testProject).dockerContainerId("docker-known")
                .status(ContainerStatus.RUNNING)
                .lastActivityAt(Instant.now()).startedAt(Instant.now())
                .build();
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of(tracked));
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerList(List.of(dockerContainer("docker-known", Instant.now().minus(2, ChronoUnit.HOURS))));

        service.cleanupIdleContainers();

        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    @Test
    void reconcile_recentlyCreatedOrphan_skippedByGracePeriod() {
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of());
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of());
        stubDockerList(List.of(dockerContainer("docker-fresh", Instant.now())));

        service.cleanupIdleContainers();

        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    @Test
    void reconcile_containerWithActiveExecution_skipped() {
        when(containerRepository.findByStatus(ContainerStatus.RUNNING)).thenReturn(List.of());
        when(executionRegistry.getActiveContainerIds()).thenReturn(Set.of("docker-busy"));
        stubDockerList(List.of(dockerContainer("docker-busy", Instant.now().minus(2, ChronoUnit.HOURS))));

        service.cleanupIdleContainers();

        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    // --- Defense-in-depth: PathValidator must be invoked for both project path and hostHome (H2 + M3) ---

    @Test
    void createAndStart_callsValidateForContainerMount_forProjectPath() {
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());
        stubDockerRemoveCommands();
        stubDockerCreateAndStart("docker-123");
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createAndStartContainer(testProject);

        // Regression guard: if a future refactor removes the line in ContainerService.doCreateAndStart,
        // this verify fails. Direct evidence of defense-in-depth.
        verify(pathValidator).validateForContainerMount("/test/project");
    }

    @Test
    void createAndStart_callsValidateForContainerMount_forHostHome() {
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());
        stubDockerRemoveCommands();
        stubDockerCreateAndStart("docker-123");
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createAndStartContainer(testProject);

        verify(pathValidator).validateForContainerMount("/home/test");
    }

    @Test
    void createAndStart_invalidProjectPath_doesNotCallDocker() {
        doThrow(new IllegalArgumentException("Path points to a restricted system directory: /etc"))
                .when(pathValidator).validateForContainerMount("/test/project");

        assertThatThrownBy(() -> service.createAndStartContainer(testProject))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted system directory");

        verify(dockerClient, never()).createContainerCmd(anyString());
        verify(dockerClient, never()).startContainerCmd(anyString());
        verify(containerRepository, never()).save(any(Container.class));
    }
}
