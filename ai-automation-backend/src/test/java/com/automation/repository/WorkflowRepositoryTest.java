package com.automation.repository;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import jakarta.persistence.EntityManager;
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
class WorkflowRepositoryTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EntityManager entityManager;

    private Project activeProject;
    private Project archivedProject;

    @BeforeEach
    void setUp() {
        activeProject = projectRepository.save(Project.builder()
                .name("Active-WF-Repo-Test-" + System.nanoTime())
                .type(ProjectType.CODE)
                .path("/tmp/active-" + System.nanoTime())
                .status(ProjectStatus.STOPPED)
                .build());

        archivedProject = projectRepository.save(Project.builder()
                .name("Archived-WF-Repo-Test-" + System.nanoTime())
                .type(ProjectType.CODE)
                .path("/tmp/archived-" + System.nanoTime())
                .status(ProjectStatus.STOPPED)
                .archivedAt(Instant.now())
                .build());
    }

    private Workflow saveWorkflow(String name, Project project) {
        return workflowRepository.save(Workflow.builder()
                .name(name)
                .project(project)
                .build());
    }

    @Test
    void should_find_workflows_by_project_id() {
        Workflow wf1 = saveWorkflow("WfByProject-A-" + System.nanoTime(), activeProject);
        Workflow wf2 = saveWorkflow("WfByProject-B-" + System.nanoTime(), activeProject);
        saveWorkflow("OtherProj-" + System.nanoTime(), archivedProject);

        List<Workflow> result = workflowRepository.findByProjectId(activeProject.getId());

        assertThat(result).extracting(Workflow::getId).contains(wf1.getId(), wf2.getId());
        assertThat(result).allMatch(w -> w.getProject().getId().equals(activeProject.getId()));
    }

    @Test
    void should_return_empty_when_no_workflows_for_project_id() {
        List<Workflow> result = workflowRepository.findByProjectId("non-existent-project-id");
        assertThat(result).isEmpty();
    }

    @Test
    void should_find_workflows_by_name_containing_ignore_case() {
        String unique = "UniqueSubstr" + System.nanoTime();
        Workflow wf = saveWorkflow("Prefix-" + unique + "-Suffix", activeProject);
        saveWorkflow("Unrelated-" + System.nanoTime(), activeProject);

        List<Workflow> upper = workflowRepository.findByNameContainingIgnoreCase(unique.toUpperCase());
        List<Workflow> lower = workflowRepository.findByNameContainingIgnoreCase(unique.toLowerCase());

        assertThat(upper).extracting(Workflow::getId).contains(wf.getId());
        assertThat(lower).extracting(Workflow::getId).contains(wf.getId());
    }

    @Test
    void should_return_empty_for_nonmatching_name_search() {
        List<Workflow> result = workflowRepository.findByNameContainingIgnoreCase("ZZZ-NoMatch-" + System.nanoTime());
        assertThat(result).isEmpty();
    }

    @Test
    void should_find_workflow_by_name_and_project_id() {
        String name = "FindByNameProj-" + System.nanoTime();
        Workflow wf = saveWorkflow(name, activeProject);

        Optional<Workflow> result = workflowRepository.findByNameAndProjectId(name, activeProject.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wf.getId());
    }

    @Test
    void should_return_empty_when_name_and_project_dont_match() {
        Optional<Workflow> result = workflowRepository.findByNameAndProjectId("nope-" + System.nanoTime(), activeProject.getId());
        assertThat(result).isEmpty();
    }

    @Test
    void should_find_all_id_name_block_types_excluding_given_id() {
        Workflow keep = saveWorkflow("KeepInList-" + System.nanoTime(), activeProject);
        Workflow excluded = saveWorkflow("Excluded-" + System.nanoTime(), activeProject);

        List<Object[]> rows = workflowRepository.findAllIdNameBlockTypes(excluded.getId());

        assertThat(rows).extracting(row -> (String) row[0]).contains(keep.getId()).doesNotContain(excluded.getId());
    }

    @Test
    void should_find_all_workflows_with_active_project_only() {
        Workflow active = saveWorkflow("ActiveProjWf-" + System.nanoTime(), activeProject);
        Workflow archived = saveWorkflow("ArchivedProjWf-" + System.nanoTime(), archivedProject);

        List<Workflow> result = workflowRepository.findAllWithActiveProject();

        assertThat(result).extracting(Workflow::getId).contains(active.getId()).doesNotContain(archived.getId());
        assertThat(result).allMatch(w -> w.getProject().getArchivedAt() == null);
    }

    @Test
    void should_find_workflow_by_id_with_skills_eager() {
        Workflow wf = saveWorkflow("ByIdWithSkills-" + System.nanoTime(), activeProject);

        Optional<Workflow> result = workflowRepository.findByIdWithSkills(wf.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wf.getId());
        assertThat(result.get().getProject()).isNotNull();
    }

    @Test
    void should_return_empty_when_finding_by_unknown_id_with_skills() {
        Optional<Workflow> result = workflowRepository.findByIdWithSkills("unknown-id-" + System.nanoTime());
        assertThat(result).isEmpty();
    }

    @Test
    void should_find_workflows_with_empty_or_null_block_types() {
        Workflow noBlocks = saveWorkflow("NoBlocks-" + System.nanoTime(), activeProject);

        BlockDefinition block = new BlockDefinition();
        block.setType("simple");
        Workflow withBlocks = workflowRepository.save(Workflow.builder()
                .name("WithBlocks-" + System.nanoTime())
                .project(activeProject)
                .blocks(List.of(block))
                .build());

        List<Workflow> result = workflowRepository.findWithEmptyBlockTypes();

        assertThat(result).extracting(Workflow::getId)
                .contains(noBlocks.getId())
                .doesNotContain(withBlocks.getId());
    }

    @Test
    void should_find_untagged_workflows() {
        Workflow untagged = saveWorkflow("Untagged-" + System.nanoTime(), activeProject);

        Workflow tagged = workflowRepository.save(Workflow.builder()
                .name("Tagged-" + System.nanoTime())
                .project(activeProject)
                .tagsGeneratedAt(Instant.now())
                .build());

        Workflow userEdited = workflowRepository.save(Workflow.builder()
                .name("UserEdited-" + System.nanoTime())
                .project(activeProject)
                .tagsUserEdited(true)
                .build());

        List<Workflow> result = workflowRepository.findUntagged();

        assertThat(result).extracting(Workflow::getId)
                .contains(untagged.getId())
                .doesNotContain(tagged.getId(), userEdited.getId());
    }

    @Test
    void should_find_all_read_only() {
        Workflow wf = saveWorkflow("ReadOnly-" + System.nanoTime(), activeProject);

        List<Workflow> result = workflowRepository.findAllReadOnly();

        assertThat(result).extracting(Workflow::getId).contains(wf.getId());
    }

    @Test
    void should_respect_soft_delete_via_sql_restriction() {
        Workflow wf = saveWorkflow("SoftDeleteTest-" + System.nanoTime(), activeProject);
        String id = wf.getId();

        wf.setDeletedAt(Instant.now());
        workflowRepository.save(wf);
        workflowRepository.flush();
        entityManager.clear();

        Optional<Workflow> result = workflowRepository.findById(id);
        assertThat(result).isEmpty();

        List<Workflow> readOnly = workflowRepository.findAllReadOnly();
        assertThat(readOnly).extracting(Workflow::getId).doesNotContain(id);
    }

    @Test
    void should_exclude_soft_deleted_from_native_id_name_block_types_query() {
        Workflow alive = saveWorkflow("AliveNative-" + System.nanoTime(), activeProject);
        Workflow softDeleted = saveWorkflow("DeletedNative-" + System.nanoTime(), activeProject);
        String deletedId = softDeleted.getId();
        softDeleted.setDeletedAt(Instant.now());
        workflowRepository.save(softDeleted);
        workflowRepository.flush();

        List<Object[]> rows = workflowRepository.findAllIdNameBlockTypes("none");

        assertThat(rows).extracting(row -> (String) row[0])
                .contains(alive.getId())
                .doesNotContain(deletedId);
    }
}
