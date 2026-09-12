package com.automation.integration;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.exception.ContainerException;
import com.automation.repository.ContainerRepository;
import com.automation.repository.ProjectRepository;
import com.automation.service.project.ContainerService;
import com.automation.service.project.DockerContainerGateway;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Restarting a project container whose Docker container is gone must leave exactly one row in
 * {@code containers} — the table has UNIQUE(project_id).
 *
 * <p>Deliberately NOT {@code @Transactional}: {@link ContainerService} runs its own
 * {@code TransactionTemplate} bursts, and the bug under test (a delete that Hibernate un-schedules
 * during flush because {@code Project.container} cascades PERSIST) only shows up when those bursts
 * really commit against the database.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ContainerServiceStaleRowIntegrationTest {

    private static final String GONE_DOCKER_ID = "gone";
    private static final String NEW_DOCKER_ID = "new-id";

    @Autowired private ContainerService containerService;
    @Autowired private ContainerRepository containerRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private DockerContainerGateway docker;

    @TempDir
    Path tempDir;

    private Project project;

    @BeforeEach
    void seedStaleContainerRow() throws Exception {
        Path projectPath = tempDir.resolve("container-stale");
        Files.createDirectories(projectPath);
        project = projectRepository.saveAndFlush(Project.builder()
                .name("container-stale-" + System.nanoTime())
                .type(ProjectType.CODE)
                .path(projectPath.toString())
                .status(ProjectStatus.RUNNING)
                .build());
        containerRepository.saveAndFlush(Container.builder()
                .project(project)
                .dockerContainerId(GONE_DOCKER_ID)
                .imageName("automation-code:latest")
                .status(ContainerStatus.RUNNING)
                .mounts(Map.of("workspace", projectPath.toString()))
                .startedAt(Instant.now())
                .lastActivityAt(Instant.now())
                .build());
        entityManager.clear();

        when(docker.isRunning(GONE_DOCKER_ID)).thenReturn(false);
        when(docker.createAndStart(any(Project.class), anyString(), anyString(), anyString()))
                .thenReturn(NEW_DOCKER_ID);
    }

    @AfterEach
    void cleanup() {
        containerRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void restartAfterDockerContainerVanished_keepsExactlyOneRow() {
        // The Docker container is gone: the lookup fails and flips the row to STOPPED.
        assertThatThrownBy(() -> containerService.getDockerContainerId(project))
                .isInstanceOf(ContainerException.class);

        assertThatCode(() -> containerService.createAndStartContainer(project))
                .doesNotThrowAnyException();

        entityManager.clear();
        List<Container> rows = containerRepository.findAll().stream()
                .filter(c -> c.getProject() != null && project.getId().equals(c.getProject().getId()))
                .toList();
        assertThat(rows).hasSize(1);
        Container row = rows.get(0);
        assertThat(row.getDockerContainerId()).isEqualTo(NEW_DOCKER_ID);
        assertThat(row.getStatus()).isEqualTo(ContainerStatus.RUNNING);
        assertThat(row.getStoppedAt()).isNull();
        assertThat(containerRepository.findByProjectId(project.getId()))
                .get()
                .extracting(Container::getDockerContainerId)
                .isEqualTo(NEW_DOCKER_ID);
    }
}
