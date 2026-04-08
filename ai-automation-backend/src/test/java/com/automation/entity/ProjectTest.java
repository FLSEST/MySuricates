package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectTest {

    @Test
    void should_defaultConfigToEmptyMap_when_usingBuilderDefaults() {
        Project project = Project.builder()
                .name("test")
                .type(ProjectType.CODE)
                .path("/tmp/test")
                .build();

        assertThat(project.getConfig()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultStatusToStopped_when_usingBuilderDefaults() {
        Project project = Project.builder()
                .name("test")
                .type(ProjectType.CODE)
                .path("/tmp/test")
                .build();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.STOPPED);
    }

    @Test
    void should_defaultWorkflowsToEmptyList_when_usingBuilderDefaults() {
        Project project = Project.builder()
                .name("test")
                .type(ProjectType.CODE)
                .path("/tmp/test")
                .build();

        assertThat(project.getWorkflows()).isNotNull().isEmpty();
    }

    @Test
    void should_storeAiContext_when_setViaSetter() {
        Project project = new Project();
        project.setAiContext("This project uses Spring Boot");

        assertThat(project.getAiContext()).isEqualTo("This project uses Spring Boot");
    }

    @Test
    void should_storeArchivedAt_when_setViaSetter() {
        Project project = new Project();
        Instant archivedAt = Instant.parse("2024-06-15T10:00:00Z");

        project.setArchivedAt(archivedAt);

        assertThat(project.getArchivedAt()).isEqualTo(archivedAt);
    }

    @Test
    void should_defaultArchivedAtToNull_when_newInstance() {
        Project project = new Project();

        assertThat(project.getArchivedAt()).isNull();
    }

    @Test
    void should_allowSettingAuditTimestamps_when_usingSetter() {
        Project project = new Project();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        Instant updated = Instant.parse("2024-06-01T12:00:00Z");

        project.setCreatedAt(created);
        project.setUpdatedAt(updated);

        assertThat(project.getCreatedAt()).isEqualTo(created);
        assertThat(project.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void should_defaultAuditTimestampsToNull_when_newInstance() {
        Project project = new Project();

        assertThat(project.getCreatedAt()).isNull();
        assertThat(project.getUpdatedAt()).isNull();
    }

    @Test
    void should_storeContainer_when_setViaSetter() {
        Project project = new Project();
        Container container = new Container();
        container.setId("container-1");

        project.setContainer(container);

        assertThat(project.getContainer()).isNotNull();
        assertThat(project.getContainer().getId()).isEqualTo("container-1");
    }

    @Test
    void should_defaultContainerToNull_when_newInstance() {
        Project project = new Project();

        assertThat(project.getContainer()).isNull();
    }

    @Test
    void should_allowAddingWorkflows_when_usingBuilderDefault() {
        Project project = Project.builder()
                .name("test")
                .type(ProjectType.CODE)
                .path("/tmp/test")
                .build();

        Workflow workflow = new Workflow();
        workflow.setId("wf-1");
        project.getWorkflows().add(workflow);

        assertThat(project.getWorkflows()).hasSize(1);
        assertThat(project.getWorkflows().get(0).getId()).isEqualTo("wf-1");
    }

    @Test
    void should_storeConfigEntries_when_configMapPopulated() {
        Map<String, Object> config = new HashMap<>(Map.of("key1", "value1", "key2", 42));

        Project project = Project.builder()
                .name("test")
                .type(ProjectType.CODE)
                .path("/tmp/test")
                .config(config)
                .build();

        assertThat(project.getConfig())
                .containsEntry("key1", "value1")
                .containsEntry("key2", 42);
    }

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant now = Instant.now();
        Container container = new Container();
        ArrayList<Workflow> workflows = new ArrayList<>();

        Project project = new Project(
                "test-id", "Test Project", "Subtitle", "Description",
                ProjectType.RESEARCH, "/tmp/test",
                new HashMap<>(Map.of("key", "val")),
                ProjectStatus.RUNNING,
                "AI context text",
                container, workflows,
                now, now, now
        );

        assertThat(project.getId()).isEqualTo("test-id");
        assertThat(project.getName()).isEqualTo("Test Project");
        assertThat(project.getSubtitle()).isEqualTo("Subtitle");
        assertThat(project.getDescription()).isEqualTo("Description");
        assertThat(project.getType()).isEqualTo(ProjectType.RESEARCH);
        assertThat(project.getPath()).isEqualTo("/tmp/test");
        assertThat(project.getConfig()).containsEntry("key", "val");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.RUNNING);
        assertThat(project.getAiContext()).isEqualTo("AI context text");
        assertThat(project.getContainer()).isSameAs(container);
        assertThat(project.getWorkflows()).isSameAs(workflows);
        assertThat(project.getArchivedAt()).isEqualTo(now);
        assertThat(project.getCreatedAt()).isEqualTo(now);
        assertThat(project.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void should_acceptAllProjectTypes_when_setViaBuilder() {
        for (ProjectType type : ProjectType.values()) {
            Project project = Project.builder()
                    .name("test")
                    .type(type)
                    .path("/tmp/test")
                    .build();

            assertThat(project.getType()).isEqualTo(type);
        }
    }

    @Test
    void should_acceptAllProjectStatuses_when_setViaBuilder() {
        for (ProjectStatus status : ProjectStatus.values()) {
            Project project = Project.builder()
                    .name("test")
                    .type(ProjectType.CODE)
                    .path("/tmp/test")
                    .status(status)
                    .build();

            assertThat(project.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void should_defaultConfigToEmptyMap_when_usingNoArgsConstructor() {
        Project project = new Project();

        assertThat(project.getConfig()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultStatusToStopped_when_usingNoArgsConstructor() {
        Project project = new Project();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.STOPPED);
    }

    @Test
    void should_defaultWorkflowsToEmptyList_when_usingNoArgsConstructor() {
        Project project = new Project();

        assertThat(project.getWorkflows()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultAiContextToNull_when_newInstance() {
        Project project = new Project();

        assertThat(project.getAiContext()).isNull();
    }

    @Test
    void should_allowResettingArchivedAtToNull_when_unarchiving() {
        Project project = new Project();
        project.setArchivedAt(Instant.parse("2024-06-15T10:00:00Z"));

        project.setArchivedAt(null);

        assertThat(project.getArchivedAt()).isNull();
    }

    @Test
    void should_storeSubtitleAndDescription_when_setViaBuilder() {
        Project project = Project.builder()
                .name("test")
                .type(ProjectType.CODE)
                .path("/tmp/test")
                .subtitle("A subtitle")
                .description("A detailed description")
                .build();

        assertThat(project.getSubtitle()).isEqualTo("A subtitle");
        assertThat(project.getDescription()).isEqualTo("A detailed description");
    }

    @Test
    void should_defaultSubtitleAndDescriptionToNull_when_notSet() {
        Project project = Project.builder()
                .name("test")
                .type(ProjectType.CODE)
                .path("/tmp/test")
                .build();

        assertThat(project.getSubtitle()).isNull();
        assertThat(project.getDescription()).isNull();
    }
}
