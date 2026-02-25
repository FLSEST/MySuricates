package com.automation.service.todo;

import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectPathResolverTest {

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ProjectPathResolver resolver;

    private Project projectA;
    private Project projectB;

    @BeforeEach
    void setUp() {
        projectA = Project.builder()
                .id("proj-a")
                .name("Project A")
                .type(ProjectType.CODE)
                .path("/workspace/project-a")
                .build();
        projectB = Project.builder()
                .id("proj-b")
                .name("Project B")
                .type(ProjectType.CODE)
                .path("/workspace/project-b")
                .build();

        lenient().when(projectRepository.findAll()).thenReturn(List.of(projectA, projectB));
    }

    @Test
    void resolve_exactMatch() {
        Optional<Project> result = resolver.resolve("/workspace/project-a/src/Main.java");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("proj-a");
    }

    @Test
    void resolve_longestPrefixMatch() {
        Project nested = Project.builder()
                .id("proj-nested")
                .name("Nested")
                .type(ProjectType.CODE)
                .path("/workspace/project-a/submodule")
                .build();
        when(projectRepository.findAll()).thenReturn(List.of(projectA, nested));

        Optional<Project> result = resolver.resolve("/workspace/project-a/submodule/file.txt");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("proj-nested");
    }

    @Test
    void resolve_noMatch_returnsEmpty() {
        Optional<Project> result = resolver.resolve("/other/path/file.txt");

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_nullPath_returnsEmpty() {
        Optional<Project> result = resolver.resolve(null);

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_blankPath_returnsEmpty() {
        Optional<Project> result = resolver.resolve("   ");

        assertThat(result).isEmpty();
    }

    @Test
    void resolveAll_multipleFiles_returnsDistinct() {
        List<Project> result = resolver.resolveAll(List.of(
                "/workspace/project-a/src/Main.java",
                "/workspace/project-a/src/Test.java",
                "/workspace/project-b/index.ts"
        ));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Project::getId).containsExactlyInAnyOrder("proj-a", "proj-b");
    }

    @Test
    void resolveAll_nullList_returnsEmpty() {
        List<Project> result = resolver.resolveAll(null);

        assertThat(result).isEmpty();
    }

    @Test
    void resolveAll_emptyList_returnsEmpty() {
        List<Project> result = resolver.resolveAll(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void resolveAll_unmatchedFiles_skipped() {
        List<Project> result = resolver.resolveAll(List.of(
                "/workspace/project-a/file.txt",
                "/unknown/path/file.txt"
        ));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("proj-a");
    }
}
