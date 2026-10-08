package com.automation.service.project;


import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.exception.ContainerException;
import com.automation.repository.ContainerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.testsupport.ParkedThreads;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerServiceTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ContainerRepository containerRepository;

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
                new ProjectContainerClaudeAccess(hostProcessUtils, name -> null, java.nio.file.Path.of("/nonexistent-home")),
                100_000L,           // cpuQuota
                4_294_967_296L,     // memoryLimit (4GB)
                100L                // pidsLimit
        );
        service = new ContainerService(
                containerRepository,
                activityLogService,
                pathValidator,
                transactionManager,
                docker,
                new DockerExpectation(dockerClient, "tcp://docker.test:2375"), // Docker eingerichtet
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
        when(createCmd.withEnv(anyList())).thenReturn(createCmd);
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
    void createAndStart_existingStale_reusesRowWithNewDockerId() {
        Container stale = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-stale")
                .status(ContainerStatus.STOPPED)
                .stoppedAt(Instant.now())
                .build();
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.of(stale));
        when(containerRepository.findById("c1")).thenReturn(Optional.of(stale));
        stubDockerRemoveCommands();
        stubDockerCreateAndStart("docker-new");
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        Container result = service.createAndStartContainer(testProject);

        // Upsert, not delete+insert: the row keeps its identity, so the UNIQUE(project_id)
        // constraint can never be hit by a second INSERT for the same project.
        assertThat(result.getId()).isEqualTo("c1");
        assertThat(result.getDockerContainerId()).isEqualTo("docker-new");
        assertThat(result.getStatus()).isEqualTo(ContainerStatus.RUNNING);
        assertThat(result.getStoppedAt()).isNull();
        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getLastActivityAt()).isNotNull();
        assertThat(result.getMounts()).containsEntry("workspace", "/test/project");
        verify(containerRepository).save(result);
        verify(containerRepository, never()).deleteById(any());
    }

    @Test
    void createAndStart_staleRowVanishedMeanwhile_insertsFreshRow() {
        Container stale = Container.builder()
                .id("c1")
                .project(testProject)
                .dockerContainerId("docker-stale")
                .status(ContainerStatus.RUNNING)
                .build();
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.of(stale));
        when(containerRepository.findById("c1")).thenReturn(Optional.empty());
        stubDockerInspectNotRunning("docker-stale");
        stubDockerRemoveCommands();
        stubDockerCreateAndStart("docker-new");
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));

        Container result = service.createAndStartContainer(testProject);

        assertThat(result.getId()).isNull(); // new row, id assigned by the DB
        assertThat(result.getDockerContainerId()).isEqualTo("docker-new");
        assertThat(result.getProject()).isSameAs(testProject);
    }

    /**
     * Runs that find the container stopped (e.g. by the idle reaper) all try to start it at once.
     * Only one Docker create may happen per project; the others wait - parked, not blocked on a
     * monitor, because runs execute on virtual threads and a monitor would pin the carrier - and
     * get the same container. A run that arrives while the start is still going joins it as well,
     * so no second start can ever overlap with the first (the UNIQUE(project_id) race).
     */
    @Test
    void createAndStart_concurrentCallsForSameProject_shareOneDockerStart() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));
        stubDockerRemoveCommands();
        stubDockerCreateAndStartBlocking("docker-shared", () -> {
            creates.incrementAndGet();
            firstInside.countDown();
            ParkedThreads.await(releaseFirst);
        });

        AtomicReference<Container> first = new AtomicReference<>();
        AtomicReference<Container> second = new AtomicReference<>();
        AtomicReference<Container> third = new AtomicReference<>();
        Thread t1 = ParkedThreads.start(() -> first.set(service.createAndStartContainer(testProject)));
        assertThat(firstInside.await(5, TimeUnit.SECONDS)).as("T1 inside Docker create").isTrue();
        // Each run loads its own Project entity; the start is shared by project id.
        Thread t2 = ParkedThreads.start(() -> second.set(service.createAndStartContainer(sameProjectAgain())));
        Thread t3 = ParkedThreads.start(() -> third.set(service.createAndStartContainer(sameProjectAgain())));
        ParkedThreads.awaitParked(t2);
        ParkedThreads.awaitParked(t3);

        releaseFirst.countDown();
        ParkedThreads.join(t1);
        ParkedThreads.join(t2);
        ParkedThreads.join(t3);

        assertThat(creates.get()).as("one Docker create for the project").isEqualTo(1);
        assertThat(first.get().getDockerContainerId()).isEqualTo("docker-shared");
        assertThat(second.get()).as("waiters get the started container").isSameAs(first.get());
        assertThat(third.get()).isSameAs(first.get());
        verify(containerRepository, times(1)).save(any(Container.class));
        assertThat(service.startsInFlight()).as("no per-project entry left behind").isZero();
    }

    @Test
    void createAndStart_failedStart_reachesEveryWaiter_andNextCallStartsAgain() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));
        stubDockerRemoveCommands();
        stubDockerCreateAndStartBlocking("docker-retry", () -> {
            if (creates.incrementAndGet() == 1) {
                firstInside.countDown();
                ParkedThreads.await(releaseFirst);
                throw new RuntimeException("Docker error");
            }
        });

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread t1 = ParkedThreads.start(() -> firstFailure.set(catchThrowable(
                () -> service.createAndStartContainer(testProject))));
        assertThat(firstInside.await(5, TimeUnit.SECONDS)).as("T1 inside Docker create").isTrue();
        Thread t2 = ParkedThreads.start(() -> secondFailure.set(catchThrowable(
                () -> service.createAndStartContainer(sameProjectAgain()))));
        ParkedThreads.awaitParked(t2);

        releaseFirst.countDown();
        ParkedThreads.join(t1);
        ParkedThreads.join(t2);

        assertThat(firstFailure.get())
                .isInstanceOf(ContainerException.class)
                .hasMessageContaining("Failed to create container");
        assertThat(secondFailure.get()).as("the waiter gets the same failure").isSameAs(firstFailure.get());
        verify(activityLogService, times(1)).error(any(), anyString(), anyString(), anyString());
        assertThat(service.startsInFlight()).as("failed start leaves no entry behind").isZero();

        Container retried = service.createAndStartContainer(testProject);

        assertThat(retried.getDockerContainerId()).isEqualTo("docker-retry");
        assertThat(creates.get()).as("a later call starts afresh").isEqualTo(2);
    }

    /**
     * A cancelled run interrupts its thread while it starts the container. The run waiting for
     * that start must not inherit the failure and silently fall back to the host: it starts the
     * container itself, as it did when it queued on a monitor.
     */
    @Test
    void createAndStart_cancelledStart_letsTheWaitingRunStartTheContainerItself() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        when(containerRepository.findByProjectId("project-1")).thenReturn(Optional.empty());
        when(containerRepository.save(any(Container.class))).thenAnswer(inv -> inv.getArgument(0));
        stubDockerRemoveCommands();
        stubDockerCreateAndStartBlocking("docker-second", () -> {
            if (creates.incrementAndGet() == 1) {
                firstInside.countDown();
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("docker call interrupted", e);
                }
            }
        });

        AtomicReference<Throwable> cancelledRun = new AtomicReference<>();
        AtomicReference<Container> waitingRun = new AtomicReference<>();
        Thread t1 = ParkedThreads.start(() -> cancelledRun.set(catchThrowable(
                () -> service.createAndStartContainer(testProject))));
        assertThat(firstInside.await(5, TimeUnit.SECONDS)).as("T1 inside Docker create").isTrue();
        Thread t2 = ParkedThreads.start(() -> waitingRun.set(service.createAndStartContainer(sameProjectAgain())));
        ParkedThreads.awaitParked(t2);

        t1.interrupt();
        ParkedThreads.join(t1);
        ParkedThreads.join(t2);

        assertThat(cancelledRun.get()).isInstanceOf(ContainerException.class);
        assertThat(waitingRun.get()).as("the waiting run starts the container itself").isNotNull();
        assertThat(waitingRun.get().getDockerContainerId()).isEqualTo("docker-second");
        assertThat(creates.get()).isEqualTo(2);
        assertThat(service.startsInFlight()).isZero();
    }

    private Project sameProjectAgain() {
        return Project.builder()
                .id(testProject.getId())
                .name(testProject.getName())
                .path(testProject.getPath())
                .type(testProject.getType())
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubDockerCreateAndStartBlocking(String responseId, Runnable insideCreate) {
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn(responseId);

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_DEEP_STUBS);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any())).thenReturn(createCmd);
        when(createCmd.withUser(anyString())).thenReturn(createCmd);
        when(createCmd.withEnv(anyList())).thenReturn(createCmd);
        when(createCmd.withWorkingDir(anyString())).thenReturn(createCmd);
        when(createCmd.withCmd(any(String[].class))).thenReturn(createCmd);
        when(createCmd.exec()).thenAnswer(inv -> {
            insideCreate.run();
            return createResponse;
        });
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(responseId)).thenReturn(startCmd);
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
