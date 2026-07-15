package com.automation.service.assistant.context;

import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectsContextSectionTest {

    @Mock private ProjectRepository projectRepository;

    private ProjectsContextSection section;

    @BeforeEach
    void setUp() {
        section = new ProjectsContextSection(projectRepository);
    }

    @Test
    void compact_showsCount() {
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of(
                Project.builder().id("p1").name("Alpha").build()));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true, List.of());

        assertThat(sb.toString()).isEqualTo("**Projects:** 1\n");
    }

    @Test
    void compact_noProjects_appendsNothing() {
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, true, List.of());

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsProjectsWithWorkflowCounts() {
        Project alpha = Project.builder().id("p-alpha").name("Alpha").build();
        Project beta = Project.builder().id("p-beta").name("Beta").build();
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of(alpha, beta));
        List<Workflow> workflows = List.of(
                Workflow.builder().id("wf1").name("Build").project(alpha).build(),
                Workflow.builder().id("wf2").name("Deploy").project(alpha).build());

        StringBuilder sb = new StringBuilder();
        section.append(sb, false, workflows);

        assertThat(sb.toString())
                .contains("**My Projects (2):**")
                .contains("Alpha (ID: p-alpha, 2 workflows)")
                .contains("Beta (ID: p-beta, 0 workflows)");
    }

    @Test
    void full_noProjects_appendsNothing() {
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, false, List.of());

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void exception_doesNotThrow() {
        when(projectRepository.findByArchivedAtIsNull()).thenThrow(new RuntimeException("DB error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false, List.of());

        assertThat(sb.toString()).isEmpty();
    }
}
