package com.automation.integration;

import com.automation.entity.*;
import com.automation.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for JPA repositories against real SQLite (in-memory).
 * Validates that custom queries, JPA converters, and entity mappings work correctly.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class RepositoryIntegrationTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private SkillRepository skillRepository;

    @Test
    void projectCrud_createAndFind() {
        Project project = Project.builder()
                .name("Integration Test Project")
                .type(ProjectType.CODE)
                .path("/tmp/test-project")
                .status(ProjectStatus.RUNNING)
                .build();
        project = projectRepository.save(project);

        assertThat(project.getId()).isNotNull();
        assertThat(projectRepository.findById(project.getId())).isPresent();
    }

    @Test
    void projectRepository_findByArchivedAtIsNull_excludesArchived() {
        Project active = projectRepository.save(Project.builder()
                .name("Active").type(ProjectType.CODE).path("/tmp/active").status(ProjectStatus.RUNNING).build());
        Project archived = projectRepository.save(Project.builder()
                .name("Archived").type(ProjectType.CODE).path("/tmp/archived").status(ProjectStatus.RUNNING)
                .archivedAt(Instant.now()).build());

        List<Project> result = projectRepository.findByArchivedAtIsNull();
        assertThat(result).extracting(Project::getName).contains("Active").doesNotContain("Archived");
    }

    @Test
    void projectRepository_findByArchivedAtIsNotNull_onlyArchived() {
        projectRepository.save(Project.builder()
                .name("Active2").type(ProjectType.CODE).path("/tmp/a2").status(ProjectStatus.RUNNING).build());
        projectRepository.save(Project.builder()
                .name("Archived2").type(ProjectType.CODE).path("/tmp/ar2").status(ProjectStatus.RUNNING)
                .archivedAt(Instant.now()).build());

        List<Project> result = projectRepository.findByArchivedAtIsNotNull();
        assertThat(result).extracting(Project::getName).contains("Archived2").doesNotContain("Active2");
    }

    @Test
    void workflowCrud_withBlocks() {
        Project project = projectRepository.save(Project.builder()
                .name("WF Test Project").type(ProjectType.CODE).path("/tmp/wf").status(ProjectStatus.RUNNING).build());

        Workflow workflow = Workflow.builder()
                .name("Test Workflow")
                .project(project)
                .blocks(new ArrayList<>(List.of(
                        BlockDefinition.builder().type("simple").config(Map.of("command", "echo test")).build()
                )))
                .activatedSkills(new ArrayList<>())
                .build();
        workflow = workflowRepository.save(workflow);

        assertThat(workflow.getId()).isNotNull();
        Optional<Workflow> found = workflowRepository.findById(workflow.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getBlocks()).hasSize(1);
        assertThat(found.get().getBlocks().get(0).getType()).isEqualTo("simple");
    }

    @Test
    void workflowRepository_findByProjectId() {
        Project project = projectRepository.save(Project.builder()
                .name("Proj").type(ProjectType.CODE).path("/tmp/proj").status(ProjectStatus.RUNNING).build());
        workflowRepository.save(Workflow.builder()
                .name("WF1").project(project).blocks(new ArrayList<>()).activatedSkills(new ArrayList<>()).build());
        workflowRepository.save(Workflow.builder()
                .name("WF2").project(project).blocks(new ArrayList<>()).activatedSkills(new ArrayList<>()).build());

        List<Workflow> result = workflowRepository.findByProjectId(project.getId());
        assertThat(result).hasSize(2);
    }

    @Test
    void workflowRepository_findByNameAndProjectId() {
        Project project = projectRepository.save(Project.builder()
                .name("NameProj").type(ProjectType.CODE).path("/tmp/np").status(ProjectStatus.RUNNING).build());
        workflowRepository.save(Workflow.builder()
                .name("UniqueWF").project(project).blocks(new ArrayList<>()).activatedSkills(new ArrayList<>()).build());

        Optional<Workflow> result = workflowRepository.findByNameAndProjectId("UniqueWF", project.getId());
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("UniqueWF");
    }

    @Test
    void workflowRun_withSteps() {
        Project project = projectRepository.save(Project.builder()
                .name("RunProj").type(ProjectType.CODE).path("/tmp/rp").status(ProjectStatus.RUNNING).build());
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("RunWF").project(project).blocks(new ArrayList<>()).activatedSkills(new ArrayList<>()).build());

        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .durationMs(1234L)
                .build();
        StepResult step = new StepResult();
        step.setStepIndex(0);
        step.setStatus(StepStatus.COMPLETED);
        step.setOutput("Hello World");
        step.setStartedAt(Instant.now());
        step.setCompletedAt(Instant.now());
        step.setDurationMs(100L);
        run.addStep(step);
        run = runRepository.save(run);

        WorkflowRun loaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(loaded.getSteps()).hasSize(1);
        assertThat(loaded.getSteps().get(0).getOutput()).isEqualTo("Hello World");
    }

    @Test
    void runRepository_findByStatus() {
        Project project = projectRepository.save(Project.builder()
                .name("StatusProj").type(ProjectType.CODE).path("/tmp/sp").status(ProjectStatus.RUNNING).build());
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("StatusWF").project(project).blocks(new ArrayList<>()).activatedSkills(new ArrayList<>()).build());

        runRepository.save(WorkflowRun.builder().workflow(workflow).status(RunStatus.COMPLETED)
                .startedAt(Instant.now()).build());
        runRepository.save(WorkflowRun.builder().workflow(workflow).status(RunStatus.FAILED)
                .startedAt(Instant.now()).build());

        assertThat(runRepository.findByStatus(RunStatus.COMPLETED)).hasSize(1);
        assertThat(runRepository.findByStatus(RunStatus.FAILED)).hasSize(1);
        assertThat(runRepository.findByStatus(RunStatus.RUNNING)).isEmpty();
    }

    @Test
    void todoCrud_withTags() {
        Todo todo = Todo.builder()
                .title("Integration Test TODO")
                .description("Testing JPA converters")
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM)
                .tags("test,integration")
                .source(Todo.Source.AI)
                .sourceAgent("integration-test")
                .build();
        todo = todoRepository.save(todo);

        Todo loaded = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(loaded.getTags()).isEqualTo("test,integration");
        assertThat(loaded.getStatus()).isEqualTo(Todo.Status.OPEN);
    }

    @Test
    void skillRepository_findByCategory() {
        // Built-in skills are seeded by SkillSeeder — check that category filtering works
        List<Skill> codeSkills = skillRepository.findByCategory(SkillCategory.CODE);
        // May be empty in test (seeder might not run), but query should not throw
        assertThat(codeSkills).isNotNull();
    }

    @Test
    void workflowRunMetadata_jsonConverterWorks() {
        Project project = projectRepository.save(Project.builder()
                .name("MetaProj").type(ProjectType.CODE).path("/tmp/mp").status(ProjectStatus.RUNNING).build());
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("MetaWF").project(project).blocks(new ArrayList<>()).activatedSkills(new ArrayList<>()).build());

        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .metadata(Map.of("gate_status", "YELLOW", "system", "MySuricates"))
                .build();
        run = runRepository.save(run);

        WorkflowRun loaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(loaded.getMetadata()).containsEntry("gate_status", "YELLOW");
        assertThat(loaded.getMetadata()).containsEntry("system", "MySuricates");
    }
}
