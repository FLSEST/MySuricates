package com.automation.repository;

import com.automation.dto.response.TodoCountResponse;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Todo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TodoRepositoryTest {

    @Autowired
    private TodoRepository repository;

    @Autowired
    private ProjectRepository projectRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.flush();

        Project project = projectRepository.save(Project.builder()
                .name("Todo Test Project")
                .type(ProjectType.CODE)
                .path("/tmp/todo-test-project")
                .status(ProjectStatus.STOPPED)
                .build());
        projectId = project.getId();
    }

    private Todo todo(String title) {
        return Todo.builder().title(title).build();
    }

    private Todo todoWithStatus(String title, Todo.Status status) {
        return Todo.builder().title(title).status(status).build();
    }

    private Todo todoWithSource(String title, Todo.Source source) {
        return Todo.builder().title(title).source(source).build();
    }

    @Test
    void should_save_and_retrieve_todo_with_generated_id() {
        Todo saved = repository.save(todo("Mein Todo"));
        assertThat(saved.getId()).isNotBlank();
        Optional<Todo> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getTitle()).isEqualTo("Mein Todo");
    }

    @Test
    void should_count_todos_by_status() {
        repository.save(todoWithStatus("a", Todo.Status.OPEN));
        repository.save(todoWithStatus("b", Todo.Status.OPEN));
        repository.save(todoWithStatus("c", Todo.Status.DONE));

        long openCount = repository.countByStatus(Todo.Status.OPEN);
        long doneCount = repository.countByStatus(Todo.Status.DONE);

        assertThat(openCount).isEqualTo(2);
        assertThat(doneCount).isEqualTo(1);
    }

    @Test
    void should_count_todos_by_source() {
        repository.save(todoWithSource("user-todo", Todo.Source.USER));
        repository.save(todoWithSource("ai-todo", Todo.Source.AI));
        repository.save(todoWithSource("workflow-todo", Todo.Source.WORKFLOW));

        long userCount = repository.countBySource(Todo.Source.USER);
        long aiCount = repository.countBySource(Todo.Source.AI);
        long workflowCount = repository.countBySource(Todo.Source.WORKFLOW);

        assertThat(userCount).isEqualTo(1);
        assertThat(aiCount).isEqualTo(1);
        assertThat(workflowCount).isEqualTo(1);
    }

    @Test
    void should_aggregate_counts_grouped_by_status() {
        repository.save(todoWithStatus("open-1", Todo.Status.OPEN));
        repository.save(todoWithStatus("inprog-1", Todo.Status.IN_PROGRESS));
        repository.save(todoWithStatus("done-1", Todo.Status.DONE));
        repository.save(todoWithStatus("backlog-1", Todo.Status.BACKLOG));

        TodoCountResponse counts = repository.countGroupedByStatus();

        assertThat(counts).isNotNull();
        assertThat(counts.open()).isEqualTo(1);
        assertThat(counts.inProgress()).isEqualTo(1);
        assertThat(counts.done()).isEqualTo(1);
        assertThat(counts.backlog()).isEqualTo(1);
    }

    @Test
    void should_find_open_todo_by_composite_key() {
        Todo todo = Todo.builder()
                .title("Composite-Key-Match")
                .projectId(projectId)
                .source(Todo.Source.WORKFLOW)
                .referenceType("WORKFLOW")
                .referenceId("ref-1")
                .status(Todo.Status.OPEN)
                .tags("category:performance")
                .build();
        repository.save(todo);

        Optional<Todo> found = repository.findOpenByCompositeKey(
                projectId, "WORKFLOW", "ref-1", "category:performance");

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Composite-Key-Match");
    }

    @Test
    void should_not_find_done_todo_by_composite_key() {
        Todo done = Todo.builder()
                .title("Already-Done")
                .projectId(projectId)
                .source(Todo.Source.WORKFLOW)
                .referenceType("WORKFLOW")
                .referenceId("ref-2")
                .status(Todo.Status.DONE)
                .tags("category:bug")
                .build();
        repository.save(done);

        Optional<Todo> found = repository.findOpenByCompositeKey(
                projectId, "WORKFLOW", "ref-2", "category:bug");

        assertThat(found).isEmpty();
    }

    @Test
    void should_find_by_project_source_reference_and_status_not() {
        Todo open = Todo.builder()
                .title("Open-One")
                .projectId(projectId)
                .source(Todo.Source.WORKFLOW)
                .referenceType("WORKFLOW")
                .referenceId("ref-3")
                .status(Todo.Status.OPEN)
                .build();
        Todo done = Todo.builder()
                .title("Done-One")
                .projectId(projectId)
                .source(Todo.Source.WORKFLOW)
                .referenceType("WORKFLOW")
                .referenceId("ref-3")
                .status(Todo.Status.DONE)
                .build();
        repository.save(open);
        repository.save(done);

        List<Todo> results = repository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                projectId, Todo.Source.WORKFLOW, "WORKFLOW", "ref-3", Todo.Status.DONE);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Open-One");
    }

    @Test
    void should_find_by_project_id_and_status_in() {
        repository.save(Todo.builder().title("p4-open").projectId(projectId).status(Todo.Status.OPEN).build());
        repository.save(Todo.builder().title("p4-inprog").projectId(projectId).status(Todo.Status.IN_PROGRESS).build());
        repository.save(Todo.builder().title("p4-done").projectId(projectId).status(Todo.Status.DONE).build());

        List<Todo> active = repository.findByProjectIdAndStatusIn(
                projectId, List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS));

        assertThat(active).hasSize(2);
        assertThat(active).extracting(Todo::getTitle)
                .containsExactlyInAnyOrder("p4-open", "p4-inprog");
    }

    @Test
    void should_find_backlog_candidates_ordered_by_priority() {
        repository.save(Todo.builder()
                .title("low-prio")
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.LOW)
                .fixAttempts(0)
                .build());
        repository.save(Todo.builder()
                .title("critical-prio")
                .status(Todo.Status.BACKLOG)
                .priority(Todo.Priority.CRITICAL)
                .fixAttempts(0)
                .build());
        repository.save(Todo.builder()
                .title("high-prio")
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.HIGH)
                .fixAttempts(0)
                .build());

        List<Todo> candidates = repository.findBacklogCandidates(5, PageRequest.of(0, 50));

        assertThat(candidates).isNotEmpty();
        int idxCritical = -1, idxHigh = -1, idxLow = -1;
        for (int i = 0; i < candidates.size(); i++) {
            String t = candidates.get(i).getTitle();
            if ("critical-prio".equals(t)) idxCritical = i;
            else if ("high-prio".equals(t)) idxHigh = i;
            else if ("low-prio".equals(t)) idxLow = i;
        }
        assertThat(idxCritical).isLessThan(idxHigh);
        assertThat(idxHigh).isLessThan(idxLow);
    }

    @Test
    void should_exclude_todos_with_max_fix_attempts_from_backlog_candidates() {
        Todo exhausted = Todo.builder()
                .title("exhausted-todo")
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.HIGH)
                .fixAttempts(5)
                .build();
        repository.save(exhausted);

        List<Todo> candidates = repository.findBacklogCandidates(3, PageRequest.of(0, 50));

        assertThat(candidates).extracting(Todo::getTitle).doesNotContain("exhausted-todo");
    }

    @Test
    void should_find_open_by_reference_type_and_id() {
        Todo open = Todo.builder()
                .title("ref-open")
                .referenceType("WISH")
                .referenceId("wish-42")
                .status(Todo.Status.OPEN)
                .build();
        repository.save(open);

        Optional<Todo> found = repository.findOpenByReferenceTypeAndId("WISH", "wish-42");

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("ref-open");
    }

    @Test
    void should_not_find_done_todo_by_reference_type_and_id() {
        Todo done = Todo.builder()
                .title("ref-done")
                .referenceType("WISH")
                .referenceId("wish-43")
                .status(Todo.Status.DONE)
                .build();
        repository.save(done);

        Optional<Todo> found = repository.findOpenByReferenceTypeAndId("WISH", "wish-43");

        assertThat(found).isEmpty();
    }

    @Test
    void should_find_by_last_fix_run_id() {
        Todo todo = Todo.builder()
                .title("with-fix-run")
                .lastFixRunId("run-123")
                .build();
        repository.save(todo);

        Optional<Todo> found = repository.findByLastFixRunId("run-123");

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("with-fix-run");
    }

    @Test
    void should_return_empty_when_no_todo_with_last_fix_run_id() {
        Optional<Todo> found = repository.findByLastFixRunId("nonexistent-run-id");
        assertThat(found).isEmpty();
    }
}
