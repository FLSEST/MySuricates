package com.automation.integration;

import com.automation.dto.projection.NeedsYouRow;
import com.automation.dto.projection.ProjectCount;
import com.automation.dto.projection.ProjectStatusCounts;
import com.automation.dto.response.TodoCountResponse;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for TodoRepository custom queries against real SQLite (in-memory).
 * Tests: countByStatus, countBySource, countGroupedByStatus, findOpenByCompositeKey,
 * findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot,
 * findByProjectIdAndStatusIn, findBacklogCandidates, findOpenByReferenceTypeAndId.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class TodoRepositoryIntegrationTest {

    @Autowired private TodoRepository todoRepository;
    @Autowired private ProjectRepository projectRepository;

    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.save(Project.builder()
                .name("Todo Test Project").type(ProjectType.CODE)
                .path("/tmp/todo-test").status(ProjectStatus.RUNNING).build());
    }

    private Todo createTodo(String title, Todo.Status status, Todo.Priority priority, Todo.Source source) {
        return todoRepository.save(Todo.builder()
                .title(title).status(status).priority(priority).source(source).build());
    }

    private Todo createTodoWithProject(String title, Todo.Status status, Todo.Source source,
                                       String refType, String refId) {
        return todoRepository.save(Todo.builder()
                .title(title).status(status).priority(Todo.Priority.MEDIUM).source(source)
                .projectId(testProject.getId()).referenceType(refType).referenceId(refId).build());
    }

    private Project createProject(String name) {
        return projectRepository.save(Project.builder()
                .name(name).type(ProjectType.CODE).path("/tmp/" + name).status(ProjectStatus.RUNNING).build());
    }

    // --- countByStatus ---

    @Test
    void countByStatus_returnsCorrectCount() {
        createTodo("Open 1", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.USER);
        createTodo("Open 2", Todo.Status.OPEN, Todo.Priority.HIGH, Todo.Source.AI);
        createTodo("Done 1", Todo.Status.DONE, Todo.Priority.LOW, Todo.Source.USER);

        assertThat(todoRepository.countByStatus(Todo.Status.OPEN)).isEqualTo(2);
        assertThat(todoRepository.countByStatus(Todo.Status.DONE)).isEqualTo(1);
        assertThat(todoRepository.countByStatus(Todo.Status.IN_PROGRESS)).isZero();
    }

    @Test
    void countByStatus_returnsZeroWhenEmpty() {
        assertThat(todoRepository.countByStatus(Todo.Status.OPEN)).isZero();
    }

    // --- countBySource ---

    @Test
    void countBySource_returnsCorrectCount() {
        createTodo("User Todo", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.USER);
        createTodo("AI Todo 1", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.AI);
        createTodo("AI Todo 2", Todo.Status.DONE, Todo.Priority.LOW, Todo.Source.AI);
        createTodo("WF Todo", Todo.Status.OPEN, Todo.Priority.HIGH, Todo.Source.WORKFLOW);

        assertThat(todoRepository.countBySource(Todo.Source.USER)).isEqualTo(1);
        assertThat(todoRepository.countBySource(Todo.Source.AI)).isEqualTo(2);
        assertThat(todoRepository.countBySource(Todo.Source.WORKFLOW)).isEqualTo(1);
    }

    @Test
    void countBySource_returnsZeroForUnusedSource() {
        createTodo("Only User", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.USER);
        assertThat(todoRepository.countBySource(Todo.Source.WORKFLOW)).isZero();
    }

    // --- countGroupedByStatus ---

    @Test
    void countGroupedByStatus_returnsAllCounts() {
        createTodo("Open", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.USER);
        createTodo("Open 2", Todo.Status.OPEN, Todo.Priority.HIGH, Todo.Source.AI);
        createTodo("InProg", Todo.Status.IN_PROGRESS, Todo.Priority.MEDIUM, Todo.Source.USER);
        createTodo("Done", Todo.Status.DONE, Todo.Priority.LOW, Todo.Source.AI);
        createTodo("Backlog", Todo.Status.BACKLOG, Todo.Priority.LOW, Todo.Source.WORKFLOW);
        createTodo("Backlog 2", Todo.Status.BACKLOG, Todo.Priority.MEDIUM, Todo.Source.AI);

        TodoCountResponse counts = todoRepository.countGroupedByStatus();
        assertThat(counts.open()).isEqualTo(2);
        assertThat(counts.inProgress()).isEqualTo(1);
        assertThat(counts.done()).isEqualTo(1);
        assertThat(counts.backlog()).isEqualTo(2);
    }

    @Test
    void countGroupedByStatus_countsOnlyMatchingStatuses() {
        createTodo("Open Only", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.USER);

        TodoCountResponse counts = todoRepository.countGroupedByStatus();
        assertThat(counts.open()).isEqualTo(1);
        assertThat(counts.inProgress()).isZero();
        assertThat(counts.done()).isZero();
        assertThat(counts.backlog()).isZero();
    }

    // --- findOpenByCompositeKey ---

    @Test
    void findOpenByCompositeKey_findsMatchingTodo() {
        Todo todo = todoRepository.save(Todo.builder()
                .title("Composite Key Todo")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH)
                .source(Todo.Source.WORKFLOW)
                .projectId(testProject.getId()).referenceType("workflow").referenceId("wf-123")
                .tags("security,performance")
                .build());

        Optional<Todo> result = todoRepository.findOpenByCompositeKey(
                testProject.getId(), "workflow", "wf-123", "security");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(todo.getId());
    }

    @Test
    void findOpenByCompositeKey_excludesDoneTodos() {
        todoRepository.save(Todo.builder()
                .title("Done Composite")
                .status(Todo.Status.DONE).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.WORKFLOW)
                .projectId(testProject.getId()).referenceType("workflow").referenceId("wf-456")
                .tags("bug")
                .build());

        Optional<Todo> result = todoRepository.findOpenByCompositeKey(
                testProject.getId(), "workflow", "wf-456", "bug");
        assertThat(result).isEmpty();
    }

    @Test
    void findOpenByCompositeKey_findsInProgressTodos() {
        Todo todo = todoRepository.save(Todo.builder()
                .title("InProgress Composite")
                .status(Todo.Status.IN_PROGRESS).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.WORKFLOW)
                .projectId(testProject.getId()).referenceType("workflow").referenceId("wf-789")
                .tags("feature")
                .build());

        Optional<Todo> result = todoRepository.findOpenByCompositeKey(
                testProject.getId(), "workflow", "wf-789", "feature");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(todo.getId());
    }

    @Test
    void findOpenByCompositeKey_returnsEmptyForNonMatchingTag() {
        todoRepository.save(Todo.builder()
                .title("Wrong Tag")
                .status(Todo.Status.OPEN).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.WORKFLOW)
                .projectId(testProject.getId()).referenceType("workflow").referenceId("wf-abc")
                .tags("performance")
                .build());

        Optional<Todo> result = todoRepository.findOpenByCompositeKey(
                testProject.getId(), "workflow", "wf-abc", "security");
        assertThat(result).isEmpty();
    }

    @Test
    void findOpenByCompositeKey_onlyMatchesWorkflowSource() {
        todoRepository.save(Todo.builder()
                .title("AI Source")
                .status(Todo.Status.OPEN).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.AI)
                .projectId(testProject.getId()).referenceType("workflow").referenceId("wf-def")
                .tags("bug")
                .build());

        Optional<Todo> result = todoRepository.findOpenByCompositeKey(
                testProject.getId(), "workflow", "wf-def", "bug");
        assertThat(result).isEmpty();
    }

    // --- findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot ---

    @Test
    void findByProjectAndSourceAndRefAndStatusNot_findsNonDoneTodos() {
        Todo open = createTodoWithProject("Open Ref", Todo.Status.OPEN,
                Todo.Source.WORKFLOW, "workflow", "wf-1");
        Todo inProgress = createTodoWithProject("InProg Ref", Todo.Status.IN_PROGRESS,
                Todo.Source.WORKFLOW, "workflow", "wf-1");
        createTodoWithProject("Done Ref", Todo.Status.DONE,
                Todo.Source.WORKFLOW, "workflow", "wf-1");

        List<Todo> result = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                testProject.getId(), Todo.Source.WORKFLOW, "workflow", "wf-1", Todo.Status.DONE);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Todo::getId)
                .containsExactlyInAnyOrder(open.getId(), inProgress.getId());
    }

    @Test
    void findByProjectAndSourceAndRefAndStatusNot_filtersCorrectly() {
        Project otherProject = createProject("other-proj");
        todoRepository.save(Todo.builder()
                .title("Wrong Project").status(Todo.Status.OPEN).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.WORKFLOW).projectId(otherProject.getId())
                .referenceType("workflow").referenceId("wf-2").build());
        createTodoWithProject("Wrong Source", Todo.Status.OPEN,
                Todo.Source.AI, "workflow", "wf-2");
        createTodoWithProject("Wrong RefType", Todo.Status.OPEN,
                Todo.Source.WORKFLOW, "health-check", "wf-2");
        Todo match = createTodoWithProject("Match", Todo.Status.OPEN,
                Todo.Source.WORKFLOW, "workflow", "wf-2");

        List<Todo> result = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                testProject.getId(), Todo.Source.WORKFLOW, "workflow", "wf-2", Todo.Status.DONE);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(match.getId());
    }

    @Test
    void findByProjectAndSourceAndRefAndStatusNot_returnsEmptyWhenAllExcluded() {
        createTodoWithProject("All Done", Todo.Status.DONE,
                Todo.Source.WORKFLOW, "workflow", "wf-3");

        List<Todo> result = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                testProject.getId(), Todo.Source.WORKFLOW, "workflow", "wf-3", Todo.Status.DONE);
        assertThat(result).isEmpty();
    }

    // --- findByProjectIdAndStatusIn ---

    @Test
    void findByProjectIdAndStatusIn_findsMatchingStatuses() {
        createTodoWithProject("Open P", Todo.Status.OPEN,
                Todo.Source.USER, null, null);
        createTodoWithProject("InProg P", Todo.Status.IN_PROGRESS,
                Todo.Source.AI, null, null);
        createTodoWithProject("Done P", Todo.Status.DONE,
                Todo.Source.USER, null, null);
        createTodoWithProject("Backlog P", Todo.Status.BACKLOG,
                Todo.Source.WORKFLOW, null, null);

        List<Todo> result = todoRepository.findByProjectIdAndStatusIn(
                testProject.getId(), List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS));
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Todo::getStatus)
                .containsExactlyInAnyOrder(Todo.Status.OPEN, Todo.Status.IN_PROGRESS);
    }

    @Test
    void findByProjectIdAndStatusIn_excludesOtherProjects() {
        Project otherProject = createProject("other-proj-2");
        createTodoWithProject("Test Proj", Todo.Status.OPEN,
                Todo.Source.USER, null, null);
        todoRepository.save(Todo.builder()
                .title("Other Proj").status(Todo.Status.OPEN).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.USER).projectId(otherProject.getId()).build());

        List<Todo> result = todoRepository.findByProjectIdAndStatusIn(
                testProject.getId(), List.of(Todo.Status.OPEN));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProjectId()).isEqualTo(testProject.getId());
    }

    @Test
    void findByProjectIdAndStatusIn_returnsEmptyWhenNoMatch() {
        createTodoWithProject("Done Only", Todo.Status.DONE,
                Todo.Source.USER, null, null);

        List<Todo> result = todoRepository.findByProjectIdAndStatusIn(
                testProject.getId(), List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS));
        assertThat(result).isEmpty();
    }

    // --- findBacklogCandidates ---

    @Test
    void findBacklogCandidates_returnsOpenAndBacklogTodos() {
        createTodo("Open Candidate", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.AI);
        createTodo("Backlog Candidate", Todo.Status.BACKLOG, Todo.Priority.HIGH, Todo.Source.WORKFLOW);
        createTodo("Done Not Candidate", Todo.Status.DONE, Todo.Priority.HIGH, Todo.Source.AI);
        createTodo("InProg Not Candidate", Todo.Status.IN_PROGRESS, Todo.Priority.CRITICAL, Todo.Source.USER);

        List<Todo> result = todoRepository.findBacklogCandidates(3, PageRequest.of(0, 10));
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Todo::getStatus)
                .containsExactlyInAnyOrder(Todo.Status.OPEN, Todo.Status.BACKLOG);
    }

    @Test
    void findBacklogCandidates_orderedByPriorityThenCreatedAt() {
        Todo low = createTodo("Low", Todo.Status.OPEN, Todo.Priority.LOW, Todo.Source.AI);
        Todo critical = createTodo("Critical", Todo.Status.OPEN, Todo.Priority.CRITICAL, Todo.Source.AI);
        Todo high = createTodo("High", Todo.Status.BACKLOG, Todo.Priority.HIGH, Todo.Source.AI);
        Todo medium = createTodo("Medium", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.AI);

        List<Todo> result = todoRepository.findBacklogCandidates(3, PageRequest.of(0, 10));
        assertThat(result).extracting(Todo::getTitle)
                .containsExactly("Critical", "High", "Medium", "Low");
    }

    @Test
    void findBacklogCandidates_excludesExceededMaxAttempts() {
        Todo withinLimit = createTodo("Within Limit", Todo.Status.OPEN, Todo.Priority.HIGH, Todo.Source.AI);
        Todo exceeded = todoRepository.save(Todo.builder()
                .title("Exceeded Attempts")
                .status(Todo.Status.OPEN).priority(Todo.Priority.CRITICAL)
                .source(Todo.Source.AI).fixAttempts(3)
                .build());

        List<Todo> result = todoRepository.findBacklogCandidates(3, PageRequest.of(0, 10));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(withinLimit.getId());
    }

    @Test
    void findBacklogCandidates_respectsPageable() {
        createTodo("C1", Todo.Status.OPEN, Todo.Priority.CRITICAL, Todo.Source.AI);
        createTodo("C2", Todo.Status.OPEN, Todo.Priority.HIGH, Todo.Source.AI);
        createTodo("C3", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.AI);

        List<Todo> result = todoRepository.findBacklogCandidates(3, PageRequest.of(0, 2));
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Todo::getTitle).containsExactly("C1", "C2");
    }

    @Test
    void findBacklogCandidates_returnsEmptyWhenNoCandidates() {
        createTodo("Done", Todo.Status.DONE, Todo.Priority.HIGH, Todo.Source.AI);
        createTodo("InProg", Todo.Status.IN_PROGRESS, Todo.Priority.CRITICAL, Todo.Source.USER);

        List<Todo> result = todoRepository.findBacklogCandidates(3, PageRequest.of(0, 10));
        assertThat(result).isEmpty();
    }

    // --- findOpenByReferenceTypeAndId ---

    @Test
    void findOpenByReferenceTypeAndId_findsOpenTodo() {
        Todo todo = todoRepository.save(Todo.builder()
                .title("Open Ref Todo")
                .status(Todo.Status.OPEN).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.AI)
                .referenceType("health-check").referenceId("hc-001")
                .build());

        Optional<Todo> result = todoRepository.findOpenByReferenceTypeAndId("health-check", "hc-001");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(todo.getId());
    }

    @Test
    void findOpenByReferenceTypeAndId_findsInProgressTodo() {
        Todo todo = todoRepository.save(Todo.builder()
                .title("InProg Ref Todo")
                .status(Todo.Status.IN_PROGRESS).priority(Todo.Priority.HIGH)
                .source(Todo.Source.WORKFLOW)
                .referenceType("health-check").referenceId("hc-002")
                .build());

        Optional<Todo> result = todoRepository.findOpenByReferenceTypeAndId("health-check", "hc-002");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(todo.getId());
    }

    @Test
    void findOpenByReferenceTypeAndId_excludesDoneTodos() {
        todoRepository.save(Todo.builder()
                .title("Done Ref Todo")
                .status(Todo.Status.DONE).priority(Todo.Priority.LOW)
                .source(Todo.Source.AI)
                .referenceType("health-check").referenceId("hc-003")
                .build());

        Optional<Todo> result = todoRepository.findOpenByReferenceTypeAndId("health-check", "hc-003");
        assertThat(result).isEmpty();
    }

    @Test
    void findOpenByReferenceTypeAndId_returnsEmptyForNonMatchingType() {
        todoRepository.save(Todo.builder()
                .title("Wrong Type")
                .status(Todo.Status.OPEN).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.AI)
                .referenceType("workflow").referenceId("hc-004")
                .build());

        Optional<Todo> result = todoRepository.findOpenByReferenceTypeAndId("health-check", "hc-004");
        assertThat(result).isEmpty();
    }

    @Test
    void findOpenByReferenceTypeAndId_returnsEmptyForNonMatchingId() {
        todoRepository.save(Todo.builder()
                .title("Wrong Id")
                .status(Todo.Status.OPEN).priority(Todo.Priority.MEDIUM)
                .source(Todo.Source.AI)
                .referenceType("health-check").referenceId("hc-005")
                .build());

        Optional<Todo> result = todoRepository.findOpenByReferenceTypeAndId("health-check", "hc-999");
        assertThat(result).isEmpty();
    }

    @Test
    void findOpenByReferenceTypeAndId_findsBacklogTodo() {
        Todo todo = todoRepository.save(Todo.builder()
                .title("Backlog Ref Todo")
                .status(Todo.Status.BACKLOG).priority(Todo.Priority.LOW)
                .source(Todo.Source.AI)
                .referenceType("health-check").referenceId("hc-006")
                .build());

        Optional<Todo> result = todoRepository.findOpenByReferenceTypeAndId("health-check", "hc-006");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(todo.getId());
    }

    // --- countGroupedByStatus: ARCHIVED bucket ---

    @Test
    void countGroupedByStatus_countsArchived() {
        createTodo("Archived 1", Todo.Status.ARCHIVED, Todo.Priority.LOW, Todo.Source.WORKFLOW);
        createTodo("Archived 2", Todo.Status.ARCHIVED, Todo.Priority.LOW, Todo.Source.AI);
        createTodo("Open", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.USER);

        TodoCountResponse counts = todoRepository.countGroupedByStatus();
        assertThat(counts.archived()).isEqualTo(2);
        assertThat(counts.open()).isEqualTo(1);
    }

    // --- aggregateByProject (cockpit) ---

    @Test
    void aggregateByProject_returnsStatusAndActiveSourceCounts() {
        createTodoWithProject("Open User", Todo.Status.OPEN, Todo.Source.USER, null, null);
        createTodoWithProject("Open AI", Todo.Status.OPEN, Todo.Source.AI, null, null);
        createTodoWithProject("Backlog WF", Todo.Status.BACKLOG, Todo.Source.WORKFLOW, null, null);
        createTodoWithProject("Done AI", Todo.Status.DONE, Todo.Source.AI, null, null);
        createTodoWithProject("Archived AI", Todo.Status.ARCHIVED, Todo.Source.AI, null, null);

        ProjectStatusCounts row = todoRepository.aggregateByProject().stream()
                .filter(c -> testProject.getId().equals(c.projectId()))
                .findFirst().orElseThrow();

        assertThat(row.open()).isEqualTo(2);
        assertThat(row.backlog()).isEqualTo(1);
        assertThat(row.done()).isEqualTo(1);
        assertThat(row.archived()).isEqualTo(1);
        assertThat(row.userActive()).isEqualTo(1);       // only the OPEN USER
        assertThat(row.machineActive()).isEqualTo(2);     // OPEN AI + BACKLOG WF (active only, not DONE/ARCHIVED)
    }

    // --- staleCountByProject (cockpit) ---

    private Todo createAutoTodoWithProject(String title, Todo.Status status, Todo.Source source) {
        return todoRepository.save(Todo.builder()
                .title(title).status(status).priority(Todo.Priority.MEDIUM).source(source)
                .projectId(testProject.getId()).tags("auto:workflow,backend").build());
    }

    @Test
    void staleCountByProject_countsActiveAutoWorkflowMachineTodos() {
        createAutoTodoWithProject("Open AI auto", Todo.Status.OPEN, Todo.Source.AI);
        createAutoTodoWithProject("Backlog WF auto", Todo.Status.BACKLOG, Todo.Source.WORKFLOW);
        // excluded: USER source
        todoRepository.save(Todo.builder().title("User auto").status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM).source(Todo.Source.USER)
                .projectId(testProject.getId()).tags("auto:workflow").build());
        // excluded: no auto:workflow tag
        createTodoWithProject("Open AI untagged", Todo.Status.OPEN, Todo.Source.AI, null, null);
        // excluded: DONE
        createAutoTodoWithProject("Done AI auto", Todo.Status.DONE, Todo.Source.AI);

        // future cutoff → updatedAt < cutoff holds for all, so we test the status/source/tag filter
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        ProjectCount row = todoRepository.staleCountByProject(future).stream()
                .filter(c -> testProject.getId().equals(c.projectId()))
                .findFirst().orElseThrow();

        assertThat(row.cnt()).isEqualTo(2);
    }

    @Test
    void findStaleAutoTodoIdsForProject_scopesToProject() {
        Todo inProject = createAutoTodoWithProject("In project", Todo.Status.OPEN, Todo.Source.AI);
        Project other = createProject("other-stale");
        todoRepository.save(Todo.builder().title("Other project").status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM).source(Todo.Source.AI)
                .projectId(other.getId()).tags("auto:workflow").build());

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        List<String> ids = todoRepository.findStaleAutoTodoIdsForProject(
                testProject.getId(), future, PageRequest.of(0, 100));

        assertThat(ids).containsExactly(inProject.getId());
    }

    // --- findNeedsYou (cockpit "Braucht dich") ---

    @Test
    void findNeedsYou_returnsUserCriticalOrOverdue_excludesRoutine() {
        Instant now = Instant.now();
        todoRepository.save(Todo.builder().title("User").status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM).source(Todo.Source.USER).build());
        todoRepository.save(Todo.builder().title("Critical").status(Todo.Status.OPEN)
                .priority(Todo.Priority.CRITICAL).source(Todo.Source.AI).build());
        todoRepository.save(Todo.builder().title("Overdue").status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM).source(Todo.Source.AI)
                .dueDate(now.minus(2, ChronoUnit.DAYS)).build());
        // routine machine todo: not USER, not CRITICAL, no due date → excluded
        todoRepository.save(Todo.builder().title("Routine").status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM).source(Todo.Source.AI).build());

        List<NeedsYouRow> rows = todoRepository.findNeedsYou(now, PageRequest.of(0, 50));

        assertThat(rows).extracting(NeedsYouRow::title)
                .containsExactlyInAnyOrder("User", "Critical", "Overdue");
        // CRITICAL ranks first
        assertThat(rows.get(0).title()).isEqualTo("Critical");
    }

    // --- findStaleAutoTodoIds (hygiene) ---

    @Test
    void findStaleAutoTodoIds_onlyAutoWorkflowTaggedActiveMachineTodos() {
        Todo a = todoRepository.save(Todo.builder().title("Auto open").status(Todo.Status.OPEN)
                .priority(Todo.Priority.HIGH).source(Todo.Source.AI).tags("auto:workflow,backend").build());
        Todo b = todoRepository.save(Todo.builder().title("Auto backlog").status(Todo.Status.BACKLOG)
                .priority(Todo.Priority.LOW).source(Todo.Source.WORKFLOW).tags("auto:workflow").build());
        // excluded: no auto:workflow tag
        todoRepository.save(Todo.builder().title("Manual").status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM).source(Todo.Source.AI).tags("manual").build());
        // excluded: DONE
        todoRepository.save(Todo.builder().title("Auto done").status(Todo.Status.DONE)
                .priority(Todo.Priority.LOW).source(Todo.Source.AI).tags("auto:workflow").build());

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        List<String> ids = todoRepository.findStaleAutoTodoIds(future, PageRequest.of(0, 100));

        assertThat(ids).containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    // --- bulkSetStatus (hygiene + cockpit bulk action) ---

    @Test
    void bulkSetStatus_updatesAllGivenTodos() {
        Todo a = createTodo("A", Todo.Status.OPEN, Todo.Priority.MEDIUM, Todo.Source.AI);
        Todo b = createTodo("B", Todo.Status.BACKLOG, Todo.Priority.LOW, Todo.Source.WORKFLOW);
        Todo untouched = createTodo("C", Todo.Status.OPEN, Todo.Priority.HIGH, Todo.Source.USER);

        int updated = todoRepository.bulkSetStatus(
                List.of(a.getId(), b.getId()), Todo.Status.ARCHIVED, Instant.now());

        assertThat(updated).isEqualTo(2);
        assertThat(todoRepository.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(Todo.Status.ARCHIVED);
        assertThat(todoRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(Todo.Status.ARCHIVED);
        assertThat(todoRepository.findById(untouched.getId()).orElseThrow().getStatus()).isEqualTo(Todo.Status.OPEN);
    }
}
