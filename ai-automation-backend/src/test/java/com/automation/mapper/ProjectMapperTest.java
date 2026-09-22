package com.automation.mapper;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.response.ContainerResponse;
import com.automation.dto.response.ProjectResponse;
import com.automation.entity.*;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectMapperTest {

    @Mock
    private ContainerMapper containerMapper;

    @Mock
    private WorkflowRepository workflowRepository;

    @InjectMocks
    private ProjectMapperImpl mapper;

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            Container container = Container.builder().id("c-1").build();
            ContainerResponse containerResponse = new ContainerResponse("c-1", "docker-id", "img", ContainerStatus.RUNNING, now, null);
            when(containerMapper.toResponse(container)).thenReturn(containerResponse);

            Project project = Project.builder()
                    .id("p-1")
                    .name("My Project")
                    .subtitle("Subtitle")
                    .description("Description")
                    .type(ProjectType.CODE)
                    .path("/home/user/project")
                    .config(Map.of("key", "val"))
                    .aiContext("AI context text")
                    .status(ProjectStatus.RUNNING)
                    .container(container)
                    .archivedAt(null)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            ProjectResponse response = mapper.toResponse(project);

            assertThat(response.id()).isEqualTo("p-1");
            assertThat(response.name()).isEqualTo("My Project");
            assertThat(response.subtitle()).isEqualTo("Subtitle");
            assertThat(response.description()).isEqualTo("Description");
            assertThat(response.type()).isEqualTo(ProjectType.CODE);
            assertThat(response.path()).isEqualTo("/home/user/project");
            assertThat(response.config()).containsEntry("key", "val");
            assertThat(response.aiContext()).isEqualTo("AI context text");
            assertThat(response.status()).isEqualTo(ProjectStatus.RUNNING);
            assertThat(response.container()).isEqualTo(containerResponse);
            assertThat(response.archivedAt()).isNull();
            assertThat(response.createdAt()).isEqualTo(now);
            assertThat(response.updatedAt()).isEqualTo(now);
        }

        @Test
        void nullContainer_delegatesToContainerMapper() {
            when(containerMapper.toResponse(null)).thenReturn(null);
            Project project = Project.builder()
                    .id("p-2")
                    .name("No Container")
                    .type(ProjectType.CONTENT)
                    .path("/path")
                    .status(ProjectStatus.STOPPED)
                    .build();

            ProjectResponse response = mapper.toResponse(project);

            assertThat(response.container()).isNull();
        }

        @Test
        void nullOptionalFields_mappedAsNull() {
            when(containerMapper.toResponse(null)).thenReturn(null);
            Project project = Project.builder()
                    .id("p-3")
                    .name("Minimal")
                    .type(ProjectType.RESEARCH)
                    .path("/p")
                    .status(ProjectStatus.STOPPED)
                    .build();

            ProjectResponse response = mapper.toResponse(project);

            assertThat(response.subtitle()).isNull();
            assertThat(response.description()).isNull();
            assertThat(response.aiContext()).isNull();
            assertThat(response.archivedAt()).isNull();
            assertThat(response.createdAt()).isNull();
            assertThat(response.updatedAt()).isNull();
        }
    }

    @Nested
    class ToEntity {

        @Test
        void mapsRequestFields() {
            CreateProjectRequest request = new CreateProjectRequest(
                    "New Project",
                    "Sub",
                    "Desc",
                    ProjectType.CODE,
                    "/home/dev/project",
                    Map.of("timeout", 30),
                    "AI context"
            );

            Project project = mapper.toEntity(request);

            assertThat(project.getName()).isEqualTo("New Project");
            assertThat(project.getSubtitle()).isEqualTo("Sub");
            assertThat(project.getDescription()).isEqualTo("Desc");
            assertThat(project.getType()).isEqualTo(ProjectType.CODE);
            assertThat(project.getPath()).isEqualTo("/home/dev/project");
            assertThat(project.getConfig()).containsEntry("timeout", 30);
            assertThat(project.getAiContext()).isEqualTo("AI context");
        }

        @Test
        void constantMappings_appliedCorrectly() {
            CreateProjectRequest request = new CreateProjectRequest(
                    "Project",
                    null, null,
                    ProjectType.BUSINESS,
                    "/path",
                    null, null
            );

            Project project = mapper.toEntity(request);

            assertThat(project.getStatus()).isEqualTo(ProjectStatus.STOPPED);
        }

        @Test
        void ignoredFields_notPopulated() {
            CreateProjectRequest request = new CreateProjectRequest(
                    "Project",
                    null, null,
                    ProjectType.PERSONAL,
                    "/path",
                    null, null
            );

            Project project = mapper.toEntity(request);

            assertThat(project.getId()).isNull();
            assertThat(project.getContainer()).isNull();
            assertThat(project.getArchivedAt()).isNull();
            assertThat(project.getCreatedAt()).isNull();
            assertThat(project.getUpdatedAt()).isNull();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        when(containerMapper.toResponse(null)).thenReturn(null);
        Project p1 = Project.builder().id("p1").name("A").type(ProjectType.CODE).path("/a").status(ProjectStatus.STOPPED).build();
        Project p2 = Project.builder().id("p2").name("B").type(ProjectType.CONTENT).path("/b").status(ProjectStatus.STOPPED).build();

        List<ProjectResponse> responses = mapper.toResponseList(List.of(p1, p2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("p1");
        assertThat(responses.get(1).id()).isEqualTo("p2");
    }

    @Test
    void toResponseList_workflowCountsFromOneGroupedQuery() {
        when(containerMapper.toResponse(null)).thenReturn(null);
        when(workflowRepository.countWorkflowsGroupedByProject())
                .thenReturn(List.<Object[]>of(new Object[]{"p1", 5L}));
        Project p1 = Project.builder().id("p1").name("A").type(ProjectType.CODE).path("/a").status(ProjectStatus.STOPPED).build();
        Project p2 = Project.builder().id("p2").name("B").type(ProjectType.CODE).path("/b").status(ProjectStatus.STOPPED).build();

        List<ProjectResponse> responses = mapper.toResponseList(List.of(p1, p2));

        assertThat(responses.get(0).workflowCount()).isEqualTo(5L);
        assertThat(responses.get(1).workflowCount()).isZero();
        verify(workflowRepository, never()).countByProjectId(org.mockito.ArgumentMatchers.anyString());
    }
}
