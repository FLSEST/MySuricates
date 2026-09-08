package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Todo;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.todo.TodoSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the todo list filter semantics of {@link TodoSpecifications#buildSpec} against real
 * SQLite: default hides ARCHIVED, single vs. multi status, priority/source enums, tag LIKE with
 * escaped wildcards, case-insensitive free text over title and description.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class TodoSpecificationsIntegrationTest {

    @Autowired private TodoRepository todoRepository;
    @Autowired private ProjectRepository projectRepository;

    private String project;

    @BeforeEach
    void setUp() {
        project = projectRepository.save(Project.builder()
                .name("Spec Test Project").type(ProjectType.CODE)
                .path("/tmp/spec-test").status(ProjectStatus.RUNNING).build()).getId();
        save("Fix login bug", "NPE in AuthService", Todo.Status.OPEN, Todo.Priority.HIGH, Todo.Source.AI, "[\"backend\",\"bug\"]");
        save("Write docs", "Explain the API", Todo.Status.IN_PROGRESS, Todo.Priority.LOW, Todo.Source.USER, "[\"documentation\"]");
        save("Old idea", "archived stuff", Todo.Status.ARCHIVED, Todo.Priority.MEDIUM, Todo.Source.USER, "[\"backend\"]");
        save("Done thing", "finished", Todo.Status.DONE, Todo.Priority.MEDIUM, Todo.Source.WORKFLOW, "[\"audit:x_y\",\"50%\"]");
    }

    private void save(String title, String desc, Todo.Status status, Todo.Priority prio, Todo.Source source, String tags) {
        todoRepository.save(Todo.builder().title(title).description(desc).status(status)
                .priority(prio).source(source).tags(tags).projectId(project).build());
    }

    private List<String> titles(List<String> status, String priority, String source, String tag, String q) {
        return todoRepository.findAll(TodoSpecifications.buildSpec(project, status, priority, source, tag, q))
                .stream().map(Todo::getTitle).sorted().toList();
    }

    @Test
    void noStatusFilter_hidesArchived() {
        assertThat(titles(null, null, null, null, null))
                .containsExactly("Done thing", "Fix login bug", "Write docs");
    }

    @Test
    void singleStatus_exactMatch_canRequestArchivedExplicitly() {
        assertThat(titles(List.of("ARCHIVED"), null, null, null, null)).containsExactly("Old idea");
    }

    @Test
    void multipleStatuses_inClause() {
        assertThat(titles(List.of("OPEN", "IN_PROGRESS"), null, null, null, null))
                .containsExactly("Fix login bug", "Write docs");
    }

    @Test
    void priorityAndSource_filterByEnum() {
        assertThat(titles(null, "HIGH", null, null, null)).containsExactly("Fix login bug");
        assertThat(titles(null, null, "USER", null, null)).containsExactly("Write docs");
    }

    @Test
    void tag_substringMatchOnTagsJson() {
        assertThat(titles(null, null, null, "backend", null)).containsExactly("Fix login bug");
        assertThat(titles(List.of("ARCHIVED", "OPEN"), null, null, "backend", null))
                .containsExactly("Fix login bug", "Old idea");
    }

    @Test
    void tag_wildcardsAreEscaped() {
        assertThat(titles(null, null, null, "x_y", null)).containsExactly("Done thing");
        assertThat(titles(null, null, null, "x%y", null)).isEmpty();
        assertThat(titles(null, null, null, "50%", null)).containsExactly("Done thing");
        assertThat(titles(null, null, null, "5_%", null)).isEmpty();
    }

    @Test
    void freeText_caseInsensitiveOverTitleAndDescription() {
        assertThat(titles(null, null, null, null, "LOGIN")).containsExactly("Fix login bug");
        assertThat(titles(null, null, null, null, "explain the")).containsExactly("Write docs");
        assertThat(titles(null, null, null, null, "nothing-here")).isEmpty();
    }

    @Test
    void blankFilters_areIgnored() {
        assertThat(titles(List.of(), "  ", "", "", " "))
                .containsExactly("Done thing", "Fix login bug", "Write docs");
    }
}
