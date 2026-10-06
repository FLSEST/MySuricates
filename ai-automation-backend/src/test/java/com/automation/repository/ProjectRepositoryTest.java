package com.automation.repository;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integration tests of one JVM share one database, and not all of them roll back. So the
 * list queries below are checked against what they returned before the test saved its own
 * projects ({@code before} + saved), never against an empty table — a project another test left
 * behind turned them red depending on the class order (TODO 437afb05).
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository repository;

    @Autowired
    private ContainerRepository containerRepository;

    @PersistenceContext
    private EntityManager entityManager;

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

    /** Ids in query order; a list, not a set, so a duplicated row still fails the comparison. */
    private static List<String> ids(List<Project> projects) {
        return projects.stream().map(Project::getId).toList();
    }

    /** What a query must return after the test saved {@code saved}: its earlier result plus them. */
    private static List<String> plus(List<String> before, Project... saved) {
        List<String> expected = new ArrayList<>(before);
        for (Project project : saved) {
            expected.add(project.getId());
        }
        return expected;
    }

    // --- findByArchivedAtIsNull ---

    @Test
    void findByArchivedAtIsNull_returnsOnlyNonArchivedProjects() {
        Project active = repository.save(project("Active Project", "/tmp/proj-active", ProjectStatus.RUNNING));
        Project archived = repository.save(archivedProject("Archived Project", "/tmp/proj-archived"));

        List<Project> result = repository.findByArchivedAtIsNull();

        assertThat(ids(result)).contains(active.getId()).doesNotContain(archived.getId());
        assertThat(result).allMatch(p -> p.getArchivedAt() == null);
    }

    @Test
    void findByArchivedAtIsNull_unchangedWhenOnlyArchivedProjectsAreAdded() {
        List<String> before = ids(repository.findByArchivedAtIsNull());
        repository.save(archivedProject("Archived A", "/tmp/proj-arch-a"));
        repository.save(archivedProject("Archived B", "/tmp/proj-arch-b"));

        List<Project> result = repository.findByArchivedAtIsNull();

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void findByArchivedAtIsNull_includesProjectsWithNullArchivedAt() {
        List<String> before = ids(repository.findByArchivedAtIsNull());
        Project p1 = repository.save(project("Live Alpha", "/tmp/proj-live-alpha", ProjectStatus.STOPPED));
        Project p2 = repository.save(project("Live Beta", "/tmp/proj-live-beta", ProjectStatus.RUNNING));
        repository.save(archivedProject("Archived Only", "/tmp/proj-archived-only"));

        List<Project> result = repository.findByArchivedAtIsNull();

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(plus(before, p1, p2));
    }

    // --- findByArchivedAtIsNotNull ---

    @Test
    void findByArchivedAtIsNotNull_returnsOnlyArchivedProjects() {
        List<String> before = ids(repository.findByArchivedAtIsNotNull());
        repository.save(project("Active Only", "/tmp/proj-active-only", ProjectStatus.STOPPED));
        Project one = repository.save(archivedProject("Archived One", "/tmp/proj-arc-one"));
        Project two = repository.save(archivedProject("Archived Two", "/tmp/proj-arc-two"));

        List<Project> result = repository.findByArchivedAtIsNotNull();

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(plus(before, one, two));
        assertThat(result).allMatch(p -> p.getArchivedAt() != null);
    }

    @Test
    void findByArchivedAtIsNotNull_unchangedWhenOnlyActiveProjectsAreAdded() {
        List<String> before = ids(repository.findByArchivedAtIsNotNull());
        repository.save(project("Non Archived", "/tmp/proj-non-arch", ProjectStatus.RUNNING));

        List<Project> result = repository.findByArchivedAtIsNotNull();

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(before);
    }

    // --- findByStatus ---

    @Test
    void findByStatus_returnsProjectsWithMatchingStatus() {
        List<String> before = ids(repository.findByStatus(ProjectStatus.RUNNING));
        Project a = repository.save(project("Running A", "/tmp/proj-run-a", ProjectStatus.RUNNING));
        Project b = repository.save(project("Running B", "/tmp/proj-run-b", ProjectStatus.RUNNING));
        repository.save(project("Stopped C", "/tmp/proj-stop-c", ProjectStatus.STOPPED));

        List<Project> result = repository.findByStatus(ProjectStatus.RUNNING);

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(plus(before, a, b));
        assertThat(result).allMatch(p -> p.getStatus() == ProjectStatus.RUNNING);
    }

    @Test
    void findByStatus_unchangedWhenNoAddedProjectMatches() {
        List<String> before = ids(repository.findByStatus(ProjectStatus.ERROR));
        repository.save(project("Stopped Only", "/tmp/proj-stop-only", ProjectStatus.STOPPED));

        List<Project> result = repository.findByStatus(ProjectStatus.ERROR);

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void findByStatus_doesNotReturnOtherStatuses() {
        List<String> before = ids(repository.findByStatus(ProjectStatus.STARTING));
        Project starting = repository.save(project("Starting", "/tmp/proj-starting", ProjectStatus.STARTING));
        repository.save(project("Stopped", "/tmp/proj-stopped", ProjectStatus.STOPPED));
        repository.save(project("Error", "/tmp/proj-error", ProjectStatus.ERROR));

        List<Project> result = repository.findByStatus(ProjectStatus.STARTING);

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(plus(before, starting));
        assertThat(result).allMatch(p -> p.getStatus() == ProjectStatus.STARTING);
    }

    // --- findByNameContainingIgnoreCase ---

    @Test
    void findByNameContainingIgnoreCase_returnsMatchingProjects() {
        List<String> before = ids(repository.findByNameContainingIgnoreCase("Workflow"));
        Project alpha = repository.save(project("Alpha Workflow", "/tmp/proj-alpha-wf", ProjectStatus.STOPPED));
        Project beta = repository.save(project("Beta Workflow", "/tmp/proj-beta-wf", ProjectStatus.STOPPED));
        repository.save(project("Gamma Tracker", "/tmp/proj-gamma", ProjectStatus.STOPPED));

        List<Project> result = repository.findByNameContainingIgnoreCase("Workflow");

        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(plus(before, alpha, beta));
        assertThat(result).allMatch(p -> p.getName().toLowerCase().contains("workflow"));
    }

    @Test
    void findByNameContainingIgnoreCase_isCaseInsensitive() {
        Project delta = repository.save(project("Delta Pipeline", "/tmp/proj-delta", ProjectStatus.STOPPED));

        // By id: "not empty" would also hold for a project named "Delta..." that another test left behind
        assertThat(ids(repository.findByNameContainingIgnoreCase("delta"))).contains(delta.getId());
        assertThat(ids(repository.findByNameContainingIgnoreCase("DELTA"))).contains(delta.getId());
        assertThat(ids(repository.findByNameContainingIgnoreCase("DeLtA"))).contains(delta.getId());
    }

    @Test
    void findByNameContainingIgnoreCase_emptyWhenNoMatch() {
        repository.save(project("Epsilon Project", "/tmp/proj-epsilon", ProjectStatus.STOPPED));

        List<Project> result = repository.findByNameContainingIgnoreCase("NoSuchNameXYZ");

        assertThat(result).isEmpty();
    }

    @Test
    void findByNameContainingIgnoreCase_matchesPartialName() {
        Project zeta = repository.save(project("Zeta Automation Suite", "/tmp/proj-zeta", ProjectStatus.STOPPED));

        List<Project> result = repository.findByNameContainingIgnoreCase("Automation");

        assertThat(ids(result)).contains(zeta.getId());
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

    // --- findActiveWithContainer ---

    /** Same selection as findByArchivedAtIsNull — the join must not filter anything. */
    @Test
    void findActiveWithContainer_returnsOnlyNonArchivedProjects() {
        Project live = repository.save(project("Join Live", "/tmp/proj-join-live", ProjectStatus.RUNNING));
        repository.save(archivedProject("Join Archived", "/tmp/proj-join-archived"));

        List<Project> result = repository.findActiveWithContainer();

        assertThat(result).extracting(Project::getId).contains(live.getId());
        assertThat(result).allMatch(p -> p.getArchivedAt() == null);
    }

    /**
     * {@code LEFT}, not {@code INNER}: most projects have no container, and they
     * must not be missing from the list.
     */
    @Test
    void findActiveWithContainer_keepsProjectsWithoutContainer() {
        Project bare = repository.save(project("Join Bare", "/tmp/proj-join-bare", ProjectStatus.STOPPED));

        List<Project> result = repository.findActiveWithContainer();

        assertThat(result).extracting(Project::getId).contains(bare.getId());
        assertThat(result).filteredOn(p -> p.getId().equals(bare.getId()))
                .allSatisfy(p -> assertThat(p.getContainer()).isNull());
    }

    /** And the container comes back fetched along, otherwise the join would be pointless. */
    @Test
    void findActiveWithContainer_fetchesTheContainer() {
        Project withContainer = repository.save(
                project("Join Container", "/tmp/proj-join-container", ProjectStatus.RUNNING));
        containerRepository.save(Container.builder()
                .project(withContainer)
                .dockerContainerId("docker-join-container")
                .imageName("automation/test:latest")
                .status(ContainerStatus.RUNNING)
                .build());

        // Without clearing the persistence context the query returns the already managed
        // Project instance whose container field is still null — the join would then not
        // be measured at all.
        entityManager.flush();
        entityManager.clear();

        List<Project> result = repository.findActiveWithContainer();

        assertThat(result).filteredOn(p -> p.getId().equals(withContainer.getId()))
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getContainer().getDockerContainerId())
                        .isEqualTo("docker-join-container"));
    }

    /** A project must not appear twice just because of the join. */
    @Test
    void findActiveWithContainer_returnsEachProjectOnce() {
        Project saved = repository.save(project("Join Once", "/tmp/proj-join-once", ProjectStatus.STOPPED));

        List<Project> result = repository.findActiveWithContainer();

        assertThat(result).extracting(Project::getId)
                .filteredOn(id -> id.equals(saved.getId()))
                .hasSize(1);
    }
}
