package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerTest {

    // --- Builder defaults ---

    @Test
    void should_defaultStatusToCreated_when_usingBuilder() {
        Container container = Container.builder().build();

        assertThat(container.getStatus()).isEqualTo(ContainerStatus.CREATED);
    }

    @Test
    void should_defaultMountsToEmptyMap_when_usingBuilder() {
        Container container = Container.builder().build();

        assertThat(container.getMounts()).isNotNull().isEmpty();
        assertThat(container.getMounts()).isInstanceOf(HashMap.class);
    }

    @Test
    void should_haveNullTimestamps_when_builtWithDefaults() {
        Container container = Container.builder().build();

        assertThat(container.getStartedAt()).isNull();
        assertThat(container.getStoppedAt()).isNull();
        assertThat(container.getLastActivityAt()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeProject_when_setViaSetter() {
        Container container = new Container();
        Project project = new Project();
        project.setId("proj-1");

        container.setProject(project);

        assertThat(container.getProject()).isSameAs(project);
    }

    @Test
    void should_storeDockerContainerId_when_setViaSetter() {
        Container container = new Container();

        container.setDockerContainerId("abc123def456");

        assertThat(container.getDockerContainerId()).isEqualTo("abc123def456");
    }

    @Test
    void should_storeImageName_when_setViaSetter() {
        Container container = new Container();

        container.setImageName("ubuntu:22.04");

        assertThat(container.getImageName()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void should_storeStatus_when_setViaSetter() {
        Container container = new Container();

        container.setStatus(ContainerStatus.RUNNING);

        assertThat(container.getStatus()).isEqualTo(ContainerStatus.RUNNING);
    }

    @Test
    void should_storeStartedAt_when_setViaSetter() {
        Container container = new Container();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        container.setStartedAt(ts);

        assertThat(container.getStartedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeStoppedAt_when_setViaSetter() {
        Container container = new Container();
        Instant ts = Instant.parse("2024-06-15T11:00:00Z");

        container.setStoppedAt(ts);

        assertThat(container.getStoppedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeLastActivityAt_when_setViaSetter() {
        Container container = new Container();
        Instant ts = Instant.parse("2024-06-15T12:00:00Z");

        container.setLastActivityAt(ts);

        assertThat(container.getLastActivityAt()).isEqualTo(ts);
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        Container container = new Container();

        assertThat(container.getId()).isNull();
    }

    @Test
    void should_defaultProjectToNull_when_usingNoArgsConstructor() {
        Container container = new Container();

        assertThat(container.getProject()).isNull();
    }

    @Test
    void should_defaultDockerContainerIdToNull_when_usingNoArgsConstructor() {
        Container container = new Container();

        assertThat(container.getDockerContainerId()).isNull();
    }

    @Test
    void should_defaultImageNameToNull_when_usingNoArgsConstructor() {
        Container container = new Container();

        assertThat(container.getImageName()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Project project = new Project();
        project.setId("proj-1");
        Instant started = Instant.parse("2024-06-15T10:00:00Z");
        Instant stopped = Instant.parse("2024-06-15T10:30:00Z");
        Instant lastActivity = Instant.parse("2024-06-15T10:25:00Z");
        Map<String, Object> mounts = new HashMap<>(Map.of("/host/path", "/container/path"));

        Container container = new Container(
                "container-id", project, "docker-abc123",
                "nginx:latest", ContainerStatus.RUNNING,
                mounts, started, stopped, lastActivity
        );

        assertThat(container.getId()).isEqualTo("container-id");
        assertThat(container.getProject()).isSameAs(project);
        assertThat(container.getDockerContainerId()).isEqualTo("docker-abc123");
        assertThat(container.getImageName()).isEqualTo("nginx:latest");
        assertThat(container.getStatus()).isEqualTo(ContainerStatus.RUNNING);
        assertThat(container.getMounts()).containsEntry("/host/path", "/container/path");
        assertThat(container.getStartedAt()).isEqualTo(started);
        assertThat(container.getStoppedAt()).isEqualTo(stopped);
        assertThat(container.getLastActivityAt()).isEqualTo(lastActivity);
    }

    // --- OneToOne relation ---

    @Test
    void should_associateProject_when_usingBuilder() {
        Project project = new Project();
        project.setId("proj-42");

        Container container = Container.builder()
                .project(project)
                .dockerContainerId("docker-xyz")
                .imageName("python:3.12")
                .build();

        assertThat(container.getProject()).isSameAs(project);
        assertThat(container.getProject().getId()).isEqualTo("proj-42");
    }

    // --- ContainerStatus enum ---

    @Test
    void should_haveAllContainerStatusValues_when_checkingDeclaredOrder() {
        assertThat(ContainerStatus.values())
                .containsExactly(
                        ContainerStatus.CREATED,
                        ContainerStatus.RUNNING,
                        ContainerStatus.STOPPED,
                        ContainerStatus.ERROR
                );
    }

    @Test
    void should_resolveContainerStatusViaValueOf_when_givenValidName() {
        assertThat(ContainerStatus.valueOf("CREATED")).isEqualTo(ContainerStatus.CREATED);
        assertThat(ContainerStatus.valueOf("RUNNING")).isEqualTo(ContainerStatus.RUNNING);
        assertThat(ContainerStatus.valueOf("STOPPED")).isEqualTo(ContainerStatus.STOPPED);
        assertThat(ContainerStatus.valueOf("ERROR")).isEqualTo(ContainerStatus.ERROR);
    }

    @Test
    void should_throwException_when_containerStatusValueOfReceivesInvalidName() {
        assertThatThrownBy(() -> ContainerStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Map mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultMounts() {
        Container container = Container.builder().build();

        container.getMounts().put("/data", "/mnt/data");

        assertThat(container.getMounts()).hasSize(1).containsEntry("/data", "/mnt/data");
    }

    @Test
    void should_allowRemovingEntries_when_mountsHasEntries() {
        Map<String, Object> mounts = new HashMap<>(Map.of("/src", "/app/src", "/config", "/app/config"));
        Container container = Container.builder().mounts(mounts).build();

        container.getMounts().remove("/src");

        assertThat(container.getMounts()).hasSize(1).containsEntry("/config", "/app/config");
    }
}
