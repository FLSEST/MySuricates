package com.automation.integration;

import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import jakarta.persistence.EntityManager;
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
 * Integration tests for WorkflowRepository custom queries against real SQLite (in-memory).
 * Tests: findAllWithActiveProject, findByIdWithSkills, findWithEmptyBlockTypes,
 * findAllReadOnly, updateStableSyncedAt.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowRepositoryIntegrationTest {

    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private SkillRepository skillRepository;
    @Autowired private EntityManager entityManager;

    private Project createActiveProject(String name) {
        return projectRepository.save(Project.builder()
                .name(name).type(ProjectType.CODE).path("/tmp/" + name).status(ProjectStatus.RUNNING).build());
    }

    private Project createArchivedProject(String name) {
        return projectRepository.save(Project.builder()
                .name(name).type(ProjectType.CODE).path("/tmp/" + name).status(ProjectStatus.RUNNING)
                .archivedAt(Instant.now()).build());
    }

    private Workflow createWorkflow(String name, Project project) {
        return workflowRepository.save(Workflow.builder()
                .name(name).project(project).blocks(new ArrayList<>()).activatedSkills(new ArrayList<>()).build());
    }

    // --- findAllWithActiveProject ---

    @Test
    void findAllWithActiveProject_returnsWorkflowsFromActiveProjects() {
        Project active = createActiveProject("active-proj");
        createWorkflow("Active WF", active);

        List<Workflow> result = workflowRepository.findAllWithActiveProject();
        assertThat(result).extracting(Workflow::getName).contains("Active WF");
    }

    @Test
    void findAllWithActiveProject_excludesWorkflowsFromArchivedProjects() {
        Project active = createActiveProject("active-proj2");
        Project archived = createArchivedProject("archived-proj");
        createWorkflow("Active WF2", active);
        createWorkflow("Archived WF", archived);

        List<Workflow> result = workflowRepository.findAllWithActiveProject();
        assertThat(result).extracting(Workflow::getName)
                .contains("Active WF2")
                .doesNotContain("Archived WF");
    }

    @Test
    void findAllWithActiveProject_returnsEmptyWhenAllProjectsArchived() {
        Project archived = createArchivedProject("all-archived");
        createWorkflow("Ghost WF", archived);

        List<Workflow> result = workflowRepository.findAllWithActiveProject();
        assertThat(result).extracting(Workflow::getName).doesNotContain("Ghost WF");
    }

    @Test
    void findAllWithActiveProject_loadsActivatedSkills() {
        Project project = createActiveProject("skill-proj");
        Skill skill = skillRepository.save(Skill.builder()
                .name("Test Skill").category(SkillCategory.CODE).build());
        Workflow workflow = Workflow.builder()
                .name("WF with Skill").project(project)
                .blocks(new ArrayList<>())
                .activatedSkills(new ArrayList<>(List.of(skill)))
                .build();
        workflowRepository.save(workflow);

        entityManager.flush();
        entityManager.clear();

        List<Workflow> result = workflowRepository.findAllWithActiveProject();
        Workflow loaded = result.stream()
                .filter(w -> w.getName().equals("WF with Skill")).findFirst().orElseThrow();
        assertThat(loaded.getActivatedSkills()).hasSize(1);
        assertThat(loaded.getActivatedSkills().get(0).getName()).isEqualTo("Test Skill");
    }

    @Test
    void findAllWithActiveProject_loadsProject() {
        Project project = createActiveProject("eager-proj");
        createWorkflow("Eager WF", project);

        entityManager.flush();
        entityManager.clear();

        List<Workflow> result = workflowRepository.findAllWithActiveProject();
        Workflow loaded = result.stream()
                .filter(w -> w.getName().equals("Eager WF")).findFirst().orElseThrow();
        assertThat(loaded.getProject()).isNotNull();
        assertThat(loaded.getProject().getName()).isEqualTo("eager-proj");
    }

    @Test
    void findAllWithActiveProject_excludesSoftDeletedWorkflows() {
        Project project = createActiveProject("softdel-proj");
        Workflow deleted = Workflow.builder()
                .name("Deleted WF").project(project)
                .blocks(new ArrayList<>()).activatedSkills(new ArrayList<>())
                .deletedAt(Instant.now())
                .build();
        workflowRepository.save(deleted);
        createWorkflow("Alive WF", project);

        List<Workflow> result = workflowRepository.findAllWithActiveProject();
        assertThat(result).extracting(Workflow::getName)
                .contains("Alive WF")
                .doesNotContain("Deleted WF");
    }

    // --- findByIdWithSkills ---

    @Test
    void findByIdWithSkills_returnsWorkflowById() {
        Project project = createActiveProject("byid-proj");
        Workflow workflow = createWorkflow("Find Me", project);

        Optional<Workflow> result = workflowRepository.findByIdWithSkills(workflow.getId());
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Find Me");
    }

    @Test
    void findByIdWithSkills_returnsEmptyForNonExistentId() {
        Optional<Workflow> result = workflowRepository.findByIdWithSkills("non-existent-id");
        assertThat(result).isEmpty();
    }

    @Test
    void findByIdWithSkills_loadsActivatedSkills() {
        Project project = createActiveProject("skills-proj");
        Skill skill1 = skillRepository.save(Skill.builder()
                .name("Skill A").category(SkillCategory.CODE).build());
        Skill skill2 = skillRepository.save(Skill.builder()
                .name("Skill B").category(SkillCategory.DATA).build());

        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("Multi-Skill WF").project(project)
                .blocks(new ArrayList<>())
                .activatedSkills(new ArrayList<>(List.of(skill1, skill2)))
                .build());

        entityManager.flush();
        entityManager.clear();

        Workflow loaded = workflowRepository.findByIdWithSkills(workflow.getId()).orElseThrow();
        assertThat(loaded.getActivatedSkills()).hasSize(2);
        assertThat(loaded.getActivatedSkills()).extracting(Skill::getName)
                .containsExactlyInAnyOrder("Skill A", "Skill B");
    }

    @Test
    void findByIdWithSkills_loadsProject() {
        Project project = createActiveProject("proj-eager");
        Workflow workflow = createWorkflow("Proj WF", project);

        entityManager.flush();
        entityManager.clear();

        Workflow loaded = workflowRepository.findByIdWithSkills(workflow.getId()).orElseThrow();
        assertThat(loaded.getProject()).isNotNull();
        assertThat(loaded.getProject().getName()).isEqualTo("proj-eager");
    }

    @Test
    void findByIdWithSkills_worksWithNoSkills() {
        Project project = createActiveProject("no-skills-proj");
        Workflow workflow = createWorkflow("No Skills WF", project);

        entityManager.flush();
        entityManager.clear();

        Workflow loaded = workflowRepository.findByIdWithSkills(workflow.getId()).orElseThrow();
        assertThat(loaded.getActivatedSkills()).isEmpty();
    }

    // --- findWithEmptyBlockTypes ---

    @Test
    void findWithEmptyBlockTypes_returnsWorkflowsWithEmptyBlockTypes() {
        Project project = createActiveProject("empty-bt-proj");
        // Workflow with empty blocks → @PrePersist sets blockTypes = ""
        createWorkflow("Empty Blocks WF", project);

        List<Workflow> result = workflowRepository.findWithEmptyBlockTypes();
        assertThat(result).extracting(Workflow::getName).contains("Empty Blocks WF");
    }

    @Test
    void findWithEmptyBlockTypes_excludesWorkflowsWithBlockTypes() {
        Project project = createActiveProject("filled-bt-proj");
        Workflow workflow = Workflow.builder()
                .name("Has Blocks WF").project(project)
                .blocks(new ArrayList<>(List.of(
                        BlockDefinition.builder().type("simple").config(Map.of("prompt", "test")).build()
                )))
                .activatedSkills(new ArrayList<>())
                .build();
        workflowRepository.save(workflow);

        List<Workflow> result = workflowRepository.findWithEmptyBlockTypes();
        assertThat(result).extracting(Workflow::getName).doesNotContain("Has Blocks WF");
    }

    @Test
    void findWithEmptyBlockTypes_returnsWorkflowsWithNullBlockTypes() {
        Project project = createActiveProject("null-bt-proj");
        Workflow workflow = createWorkflow("Null BT WF", project);

        // Force blockTypes to null via native SQL to bypass @PrePersist
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE workflows SET block_types = NULL WHERE id = :id")
                .setParameter("id", workflow.getId())
                .executeUpdate();
        entityManager.clear();

        List<Workflow> result = workflowRepository.findWithEmptyBlockTypes();
        assertThat(result).extracting(Workflow::getName).contains("Null BT WF");
    }

    // --- findAllReadOnly ---

    @Test
    void findAllReadOnly_returnsAllWorkflows() {
        Project project = createActiveProject("readonly-proj");
        createWorkflow("RO WF 1", project);
        createWorkflow("RO WF 2", project);

        List<Workflow> result = workflowRepository.findAllReadOnly();
        assertThat(result).extracting(Workflow::getName).contains("RO WF 1", "RO WF 2");
    }

    @Test
    void findAllReadOnly_returnsNonNullList() {
        List<Workflow> result = workflowRepository.findAllReadOnly();
        assertThat(result).isNotNull();
    }

    @Test
    void findAllReadOnly_excludesSoftDeletedWorkflows() {
        Project project = createActiveProject("ro-softdel-proj");
        createWorkflow("Visible WF", project);
        Workflow deleted = Workflow.builder()
                .name("Deleted WF").project(project)
                .blocks(new ArrayList<>()).activatedSkills(new ArrayList<>())
                .deletedAt(Instant.now())
                .build();
        workflowRepository.save(deleted);

        List<Workflow> result = workflowRepository.findAllReadOnly();
        assertThat(result).extracting(Workflow::getName)
                .contains("Visible WF")
                .doesNotContain("Deleted WF");
    }

    // --- updateStableSyncedAt ---

    @Test
    void updateStableSyncedAt_setsTimestamp() {
        Project project = createActiveProject("sync-proj");
        Workflow workflow = createWorkflow("Sync WF", project);
        String syncTime = "2026-04-08T10:00:00Z";

        entityManager.flush();
        entityManager.clear();

        workflowRepository.updateStableSyncedAt(workflow.getId(), syncTime);
        entityManager.flush();
        entityManager.clear();

        Workflow loaded = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertThat(loaded.getStableSyncedAt()).isNotNull();
        assertThat(loaded.getStableSyncedAt().toString()).isEqualTo(syncTime);
    }

    @Test
    void updateStableSyncedAt_updatesExistingTimestamp() {
        Project project = createActiveProject("resync-proj");
        Workflow workflow = createWorkflow("Resync WF", project);
        String firstSync = "2026-04-07T08:00:00Z";
        String secondSync = "2026-04-08T12:00:00Z";

        entityManager.flush();
        entityManager.clear();

        workflowRepository.updateStableSyncedAt(workflow.getId(), firstSync);
        entityManager.flush();
        entityManager.clear();

        workflowRepository.updateStableSyncedAt(workflow.getId(), secondSync);
        entityManager.flush();
        entityManager.clear();

        Workflow loaded = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertThat(loaded.getStableSyncedAt().toString()).isEqualTo(secondSync);
    }

    @Test
    void updateStableSyncedAt_doesNotModifyUpdatedAt() {
        Project project = createActiveProject("nomod-proj");
        Workflow workflow = createWorkflow("NoMod WF", project);

        entityManager.flush();
        entityManager.clear();

        Workflow before = workflowRepository.findById(workflow.getId()).orElseThrow();
        Instant updatedAtBefore = before.getUpdatedAt();

        entityManager.clear();

        workflowRepository.updateStableSyncedAt(workflow.getId(), "2026-04-08T15:00:00Z");
        entityManager.flush();
        entityManager.clear();

        Workflow after = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAtBefore);
        assertThat(after.getStableSyncedAt()).isNotNull();
    }

    @Test
    void updateStableSyncedAt_canSetToNull() {
        Project project = createActiveProject("null-sync-proj");
        Workflow workflow = createWorkflow("NullSync WF", project);

        entityManager.flush();
        entityManager.clear();

        workflowRepository.updateStableSyncedAt(workflow.getId(), "2026-04-08T10:00:00Z");
        entityManager.flush();
        entityManager.clear();

        workflowRepository.updateStableSyncedAt(workflow.getId(), null);
        entityManager.flush();
        entityManager.clear();

        Workflow loaded = workflowRepository.findById(workflow.getId()).orElseThrow();
        assertThat(loaded.getStableSyncedAt()).isNull();
    }
}
