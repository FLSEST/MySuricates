package com.automation.repository;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class ContainerRepositoryTest {

    @Autowired
    private ContainerRepository containerRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void cleanUp() {
        containerRepository.deleteAll();
        projectRepository.deleteAll();
    }

    private Project saveProject(String name) {
        Project project = Project.builder()
                .name(name)
                .type(ProjectType.CODE)
                .path("/tmp/" + name)
                .build();
        return projectRepository.save(project);
    }

    private Container saveContainer(Project project, String dockerId, ContainerStatus status) {
        Container container = Container.builder()
                .project(project)
                .dockerContainerId(dockerId)
                .imageName("alpine:latest")
                .status(status)
                .lastActivityAt(Instant.now())
                .build();
        return containerRepository.save(container);
    }

    @Test
    void should_find_container_by_project_id_when_present() {
        Project project = saveProject("Project A");
        Container saved = saveContainer(project, "docker-abc", ContainerStatus.RUNNING);

        Optional<Container> found = containerRepository.findByProjectId(project.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getDockerContainerId()).isEqualTo("docker-abc");
    }

    @Test
    void should_return_empty_when_no_container_for_project_id() {
        Optional<Container> found = containerRepository.findByProjectId("non-existent-id");

        assertThat(found).isEmpty();
    }

    @Test
    void should_find_container_by_docker_container_id_when_present() {
        Project project = saveProject("Project B");
        Container saved = saveContainer(project, "docker-xyz-123", ContainerStatus.RUNNING);

        Optional<Container> found = containerRepository.findByDockerContainerId("docker-xyz-123");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void should_return_empty_when_no_container_for_docker_id() {
        Optional<Container> found = containerRepository.findByDockerContainerId("missing-docker-id");

        assertThat(found).isEmpty();
    }

    @Test
    void should_find_all_containers_with_given_status() {
        Project p1 = saveProject("P1");
        Project p2 = saveProject("P2");
        Project p3 = saveProject("P3");
        saveContainer(p1, "d1", ContainerStatus.RUNNING);
        saveContainer(p2, "d2", ContainerStatus.RUNNING);
        saveContainer(p3, "d3", ContainerStatus.STOPPED);

        List<Container> running = containerRepository.findByStatus(ContainerStatus.RUNNING);
        List<Container> stopped = containerRepository.findByStatus(ContainerStatus.STOPPED);

        assertThat(running).hasSize(2);
        assertThat(running).extracting(Container::getDockerContainerId)
                .containsExactlyInAnyOrder("d1", "d2");
        assertThat(stopped).hasSize(1);
        assertThat(stopped.get(0).getDockerContainerId()).isEqualTo("d3");
    }

    @Test
    void should_return_empty_list_when_no_container_with_status() {
        List<Container> found = containerRepository.findByStatus(ContainerStatus.STOPPED);

        assertThat(found).isEmpty();
    }
}
