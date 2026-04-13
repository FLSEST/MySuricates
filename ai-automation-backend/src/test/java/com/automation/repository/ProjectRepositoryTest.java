package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
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
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository repository;

    private Project project(String name, String path, ProjectStatus status) {
        return Project.builder()
                .name(name)
                .type(ProjectType.CODE)
                .path(path)
                .status(status)
                .build();
    }

    private Project archivedProject(String name, String path) {
        return Project.builder()
                .name(name)
                .type(ProjectType.CODE)
                .path(path)
                .status(ProjectStatus.STOPPED)
                .archivedAt(Instant.now())
                .build();
    }

    // --- findByArchivedAtIsNull ---

    @Test
    void findByArchivedAtIsNull_returnsOnlyNonArchivedProjects() {
        repository.save(project("Active Project", "/tmp/proj-active", ProjectStatus.RUNNING));
        repository.save(archivedProject("Archived Project", "/tmp/proj-archived"));

        List<Project> result = repository.findByArchivedAtIsNull();

        assertThat(result).isNotEmpty();
        assertThat(result).allMatch(p -> p.getArchivedAt() == null);
    }

    @Test
    void findByArchivedAtIsNull_emptyWhenAllArchived() {
        repository.save(archivedProject("Archived A", "/tmp/proj-arch-a"));
        repository.save(archivedProject("Archived B", "/tmp/proj-arch-b"));

        List<Project> result = repository.findByArchivedAtIsNull();

        assertThat(result).isEmpty();
    }

    @Test
    void findByArchivedAtIsNull_includesProjectsWithNullArchivedAt() {
        Project p1 = repository.save(project("Live Alpha", "/tmp/proj-live-alpha", ProjectStatus.STOPPED));
        Project p2 = repository.save(project("Live Beta", "/tmp/proj-live-beta", ProjectStatus.RUNNING));
        repository.save(archivedProject("Archived Only", "/tmp/proj-archived-only"));

        List<Project> result = repository.findByArchivedAtIsNull();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Project::getId)
                .containsExactlyInAnyOrder(p1.getId(), p2.getId());
    }

    // --- findByArchivedAtIsNotNull ---

    @Test
    void findByArchivedAtIsNotNull_returnsOnlyArchivedProjects() {
        repository.save(project("Active Only", "/tmp/proj-active-only", ProjectStatus.STOPPED));
        repository.save(archivedProject("Archived One", "/tmp/proj-arc-one"));
        repository.save(archivedProject("Archived Two", "/tmp/proj-arc-two"));

        List<Project> result = repository.findByArchivedAtIsNotNull();

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(p -> p.getArchivedAt() != null);
    }

    @Test
    void findByArchivedAtIsNotNull_emptyWhenNoneArchived() {
        repository.save(project("Non Archived", "/tmp/proj-non-arch", ProjectStatus.RUNNING));

        List<Project> result = repository.findByArchivedAtIsNotNull();

        assertThat(result).isEmpty();
    }

    // --- findByStatus ---

    @Test
    void findByStatus_returnsProjectsWithMatchingStatus() {
        repository.save(project("Running A", "/tmp/proj-run-a", ProjectStatus.RUNNING));
        repository.save(project("Running B", "/tmp/proj-run-b", ProjectStatus.RUNNING));
        repository.save(project("Stopped C", "/tmp/proj-stop-c", ProjectStatus.STOPPED));

        List<Project> result = repository.findByStatus(ProjectStatus.RUNNING);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(p -> p.getStatus() == ProjectStatus.RUNNING);
    }

    @Test
    void findByStatus_emptyWhenNoMatchingStatus() {
        repository.save(project("Stopped Only", "/tmp/proj-stop-only", ProjectStatus.STOPPED));

        List<Project> result = repository.findByStatus(ProjectStatus.ERROR);

        assertThat(result).isEmpty();
    }

    @Test
    void findByStatus_doesNotReturnOtherStatuses() {
        repository.save(project("Starting", "/tmp/proj-starting", ProjectStatus.STARTING));
        repository.save(project("Stopped", "/tmp/proj-stopped", ProjectStatus.STOPPED));
        repository.save(project("Error", "/tmp/proj-error", ProjectStatus.ERROR));

        List<Project> result = repository.findByStatus(ProjectStatus.STARTING);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ProjectStatus.STARTING);
    }

    // --- findByNameContainingIgnoreCase ---

    @Test
    void findByNameContainingIgnoreCase_returnsMatchingProjects() {
        repository.save(project("Alpha Workflow", "/tmp/proj-alpha-wf", ProjectStatus.STOPPED));
        repository.save(project("Beta Workflow", "/tmp/proj-beta-wf", ProjectStatus.STOPPED));
        repository.save(project("Gamma Tracker", "/tmp/proj-gamma", ProjectStatus.STOPPED));

        List<Project> result = repository.findByNameContainingIgnoreCase("Workflow");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(p -> p.getName().toLowerCase().contains("workflow"));
    }

    @Test
    void findByNameContainingIgnoreCase_isCaseInsensitive() {
        repository.save(project("Delta Pipeline", "/tmp/proj-delta", ProjectStatus.STOPPED));

        assertThat(repository.findByNameContainingIgnoreCase("delta")).isNotEmpty();
        assertThat(repository.findByNameContainingIgnoreCase("DELTA")).isNotEmpty();
        assertThat(repository.findByNameContainingIgnoreCase("DeLtA")).isNotEmpty();
    }

    @Test
    void findByNameContainingIgnoreCase_emptyWhenNoMatch() {
        repository.save(project("Epsilon Project", "/tmp/proj-epsilon", ProjectStatus.STOPPED));

        List<Project> result = repository.findByNameContainingIgnoreCase("NoSuchNameXYZ");

        assertThat(result).isEmpty();
    }

    @Test
    void findByNameContainingIgnoreCase_matchesPartialName() {
        repository.save(project("Zeta Automation Suite", "/tmp/proj-zeta", ProjectStatus.STOPPED));

        List<Project> result = repository.findByNameContainingIgnoreCase("Automation");

        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(p -> p.getName().equals("Zeta Automation Suite"));
    }

    // --- findFirstByName ---

    @Test
    void findFirstByName_returnsProjectWithExactName() {
        repository.save(project("Eta Project", "/tmp/proj-eta", ProjectStatus.STOPPED));

        Optional<Project> result = repository.findFirstByName("Eta Project");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Eta Project");
    }

    @Test
    void findFirstByName_emptyForUnknownName() {
        Optional<Project> result = repository.findFirstByName("No Such Project XYZ");

        assertThat(result).isEmpty();
    }

    @Test
    void findFirstByName_doesNotMatchPartialName() {
        repository.save(project("Theta Project", "/tmp/proj-theta", ProjectStatus.STOPPED));

        Optional<Project> result = repository.findFirstByName("Theta");

        assertThat(result).isEmpty();
    }

    @Test
    void findFirstByName_returnsCorrectProjectWhenMultipleExist() {
        Project saved = repository.save(project("Iota Runner", "/tmp/proj-iota", ProjectStatus.RUNNING));
        repository.save(project("Kappa Runner", "/tmp/proj-kappa", ProjectStatus.STOPPED));

        Optional<Project> result = repository.findFirstByName("Iota Runner");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    // --- existsByPath ---

    @Test
    void existsByPath_returnsTrueWhenPathExists() {
        repository.save(project("Lambda Project", "/tmp/proj-lambda-unique", ProjectStatus.STOPPED));

        boolean exists = repository.existsByPath("/tmp/proj-lambda-unique");

        assertThat(exists).isTrue();
    }

    @Test
    void existsByPath_returnsFalseForUnknownPath() {
        boolean exists = repository.existsByPath("/tmp/no-such-path-xyz-12345");

        assertThat(exists).isFalse();
    }

    @Test
    void existsByPath_returnsFalseAfterProjectIsDeleted() {
        Project saved = repository.save(project("Mu Project", "/tmp/proj-mu-del", ProjectStatus.STOPPED));
        repository.delete(saved);
        repository.flush();

        boolean exists = repository.existsByPath("/tmp/proj-mu-del");

        assertThat(exists).isFalse();
    }
}
