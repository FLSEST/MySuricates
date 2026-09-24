package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WishRepositoryTest {

    @Autowired
    private WishRepository repository;

    @Autowired
    private ProjectRepository projectRepository;

    private String projectAId;
    private String projectBId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.flush();

        Project projectA = projectRepository.save(Project.builder()
                .name("Wish Test Project A")
                .type(ProjectType.CODE)
                .path("/tmp/wish-test-project-a")
                .status(ProjectStatus.STOPPED)
                .build());
        projectAId = projectA.getId();

        Project projectB = projectRepository.save(Project.builder()
                .name("Wish Test Project B")
                .type(ProjectType.CODE)
                .path("/tmp/wish-test-project-b")
                .status(ProjectStatus.STOPPED)
                .build());
        projectBId = projectB.getId();
    }

    private Wish wish(String originalText, WishStatus status, String projectId) {
        Wish w = Wish.builder()
                .originalText(originalText)
                .status(status)
                .projectId(projectId)
                .build();
        return repository.save(w);
    }

    @Test
    void should_count_wishes_by_status_in() {
        wish("done", WishStatus.COMPLETED, projectAId);
        wish("failed", WishStatus.FAILED, projectAId);
        wish("cancelled", WishStatus.CANCELLED, projectBId);
        wish("open", WishStatus.RECEIVED, projectBId);

        assertThat(repository.countByStatusIn(List.of(WishStatus.COMPLETED))).isEqualTo(1);
        assertThat(repository.countByStatusIn(List.of(WishStatus.FAILED, WishStatus.CANCELLED))).isEqualTo(2);
    }

    @Test
    void should_find_wishes_by_project_id_ordered_by_created_at_desc() {
        wish("first wish", WishStatus.RECEIVED, projectAId);
        wish("second wish", WishStatus.RECEIVED, projectAId);
        wish("other project", WishStatus.RECEIVED, projectBId);

        List<Wish> result = repository.findByProjectIdOrderByCreatedAtDesc(projectAId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Wish::getProjectId).containsOnly(projectAId);
    }

    @Test
    void should_return_empty_list_when_no_wishes_for_project_id() {
        wish("a wish", WishStatus.RECEIVED, projectAId);

        List<Wish> result = repository.findByProjectIdOrderByCreatedAtDesc("nonexistent-project-id");

        assertThat(result).isEmpty();
    }

    @Test
    void should_find_wishes_by_status_ordered_by_created_at_desc() {
        wish("received wish", WishStatus.RECEIVED, projectAId);
        wish("done wish", WishStatus.COMPLETED, projectAId);
        wish("another received", WishStatus.RECEIVED, projectBId);

        List<Wish> result = repository.findByStatusOrderByCreatedAtDesc(WishStatus.RECEIVED);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Wish::getStatus).containsOnly(WishStatus.RECEIVED);
    }

    @Test
    void should_return_empty_list_when_no_wishes_with_status() {
        wish("received", WishStatus.RECEIVED, projectAId);

        List<Wish> result = repository.findByStatusOrderByCreatedAtDesc(WishStatus.FAILED);

        assertThat(result).isEmpty();
    }

    @Test
    void should_find_all_wishes_ordered_by_created_at_desc() {
        wish("a", WishStatus.RECEIVED, projectAId);
        wish("b", WishStatus.COMPLETED, projectBId);
        wish("c", WishStatus.FAILED, projectAId);

        List<Wish> result = repository.findAllByOrderByCreatedAtDesc();

        assertThat(result).hasSize(3);
    }

    @Test
    void should_find_wishes_by_status_in_and_updated_at_before() {
        wish("old received", WishStatus.RECEIVED, projectAId);
        wish("old completed", WishStatus.COMPLETED, projectBId);
        wish("old failed", WishStatus.FAILED, projectAId);
        repository.flush();

        Instant cutoff = Instant.now().plusSeconds(60);
        List<Wish> result = repository.findByStatusInAndUpdatedAtBefore(
                List.of(WishStatus.RECEIVED, WishStatus.COMPLETED), cutoff);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Wish::getStatus)
                .containsExactlyInAnyOrder(WishStatus.RECEIVED, WishStatus.COMPLETED);
    }

    @Test
    void should_return_empty_when_updated_at_is_after_cutoff() {
        wish("recent", WishStatus.RECEIVED, projectAId);
        repository.flush();

        Instant cutoff = Instant.now().minusSeconds(60);
        List<Wish> result = repository.findByStatusInAndUpdatedAtBefore(
                List.of(WishStatus.RECEIVED), cutoff);

        assertThat(result).isEmpty();
    }
}
