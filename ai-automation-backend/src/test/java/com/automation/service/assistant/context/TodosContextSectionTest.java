package com.automation.service.assistant.context;

import com.automation.dto.projection.PriorityCount;
import com.automation.dto.projection.TodoHeadline;
import com.automation.entity.Todo;
import com.automation.repository.TodoSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Rendering of the assistant's TODO section. It reads only the summary queries – no TODO entity, no
 * description (TODO f1074d2e); which TODOs count as open and their order are decided there, and
 * {@code TodoRepositoryTest} checks those against the database.
 */
@ExtendWith(MockitoExtension.class)
class TodosContextSectionTest {

    private static final List<Todo.Status> OPEN = List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS);

    @Mock private TodoSummaryRepository summaryRepository;

    private TodosContextSection section;

    @BeforeEach
    void setUp() {
        section = new TodosContextSection(summaryRepository);
    }

    private static PriorityCount count(Todo.Priority priority, long count) {
        return new PriorityCount(priority, count);
    }

    private static TodoHeadline headline(String title, Todo.Priority priority) {
        return new TodoHeadline(title, priority);
    }

    @Test
    void compact_showsCounts() {
        when(summaryRepository.countByPriority(OPEN)).thenReturn(List.of(
                count(Todo.Priority.HIGH, 1),
                count(Todo.Priority.LOW, 1)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString())
                .contains("2 open")
                .contains("1 HIGH")
                .contains("0 MEDIUM")
                .contains("1 LOW");
    }

    /** Todo.Priority knows CRITICAL; the short form left it out (finding 74573181). */
    @Test
    void compact_countsCritical_andAddsItToTheTotal() {
        when(summaryRepository.countByPriority(OPEN)).thenReturn(List.of(
                count(Todo.Priority.CRITICAL, 2),
                count(Todo.Priority.MEDIUM, 3)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString())
                .isEqualTo("**Todos:** 5 open (2 CRITICAL, 0 HIGH, 3 MEDIUM, 0 LOW)\n");
    }

    @Test
    void compact_noOpen_appendsNothing() {
        when(summaryRepository.countByPriority(OPEN)).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsTodosWithPriority() {
        when(summaryRepository.findHeadlines(eq(OPEN), any(Pageable.class))).thenReturn(List.of(
                headline("Fix critical bug", Todo.Priority.HIGH),
                headline("Nice to have", Todo.Priority.LOW)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("Open Todos (2):")
                .contains("[HIGH] Fix critical bug")
                .contains("[LOW] Nice to have");
    }

    /** The query sorts (CRITICAL first); the section keeps that order. */
    @Test
    void full_keepsTheOrderOfTheQuery() {
        when(summaryRepository.findHeadlines(eq(OPEN), any(Pageable.class))).thenReturn(List.of(
                headline("Critical", Todo.Priority.CRITICAL),
                headline("High", Todo.Priority.HIGH),
                headline("Medium", Todo.Priority.MEDIUM),
                headline("Low", Todo.Priority.LOW)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String result = sb.toString();
        int criticalPos = result.indexOf("[CRITICAL]");
        int highPos = result.indexOf("[HIGH]");
        int medPos = result.indexOf("[MEDIUM]");
        int lowPos = result.indexOf("[LOW]");
        assertThat(criticalPos).isLessThan(highPos);
        assertThat(highPos).isLessThan(medPos);
        assertThat(medPos).isLessThan(lowPos);
    }

    @Test
    void full_limitsTo10_inTheQuery() {
        List<TodoHeadline> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(headline("Todo " + i, Todo.Priority.MEDIUM));
        }
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(summaryRepository.findHeadlines(eq(OPEN), page.capture())).thenReturn(ten);

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
        assertThat(sb.toString()).contains("Open Todos (10):");
    }

    /**
     * Open = OPEN + IN_PROGRESS, as in the open_todos context variable. Filtering only DONE counted
     * ARCHIVED and BACKLOG as open (finding 74573181).
     */
    @Test
    void bothModes_askOnlyForOpenAndInProgress() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Todo.Status>> compactStatuses = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Todo.Status>> fullStatuses = ArgumentCaptor.forClass(Collection.class);
        when(summaryRepository.countByPriority(compactStatuses.capture())).thenReturn(List.of());
        when(summaryRepository.findHeadlines(fullStatuses.capture(), any(Pageable.class))).thenReturn(List.of());

        section.append(new StringBuilder(), true);
        section.append(new StringBuilder(), false);

        assertThat(compactStatuses.getValue()).containsExactlyInAnyOrder(Todo.Status.OPEN, Todo.Status.IN_PROGRESS);
        assertThat(fullStatuses.getValue()).containsExactlyInAnyOrder(Todo.Status.OPEN, Todo.Status.IN_PROGRESS);
    }

    @Test
    void full_noOpen_appendsNothing() {
        when(summaryRepository.findHeadlines(eq(OPEN), any(Pageable.class))).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void exception_doesNotThrow() {
        when(summaryRepository.findHeadlines(eq(OPEN), any(Pageable.class))).thenThrow(new RuntimeException("DB error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);
        // Should not throw, just log
        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void compact_exception_doesNotThrow() {
        when(summaryRepository.countByPriority(OPEN)).thenThrow(new RuntimeException("DB error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }
}
