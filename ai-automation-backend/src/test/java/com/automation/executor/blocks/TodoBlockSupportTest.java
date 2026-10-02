package com.automation.executor.blocks;

import com.automation.entity.StepResult;
import com.automation.entity.Todo;
import com.automation.executor.ExecutionContext;
import com.automation.testsupport.TurkishLocale;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoBlockSupportTest {

    private final ExecutionContext context = ExecutionContext.builder().workflowRunId("run").build();

    @Test
    void startResult_drawsTheNextStepIndex_andStampsStart() {
        context.nextStepIndex();

        StepResult result = TodoBlockSupport.startResult(context);

        assertThat(result.getStepIndex()).isEqualTo(1);
        assertThat(result.getStartedAt()).isNotNull();
    }

    @Test
    void finishResult_setsCompletionAndDurationFromStart() {
        StepResult result = new StepResult();
        result.setStartedAt(Instant.now().minusMillis(250));

        TodoBlockSupport.finishResult(result);

        assertThat(result.getCompletedAt()).isAfterOrEqualTo(result.getStartedAt());
        assertThat(result.getDurationMs())
                .isEqualTo(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli())
                .isGreaterThanOrEqualTo(250L);
    }

    @Test
    void resolved_substitutesVariables_andIsNullSafe() {
        context.setVariable("name", "Welt");

        assertThat(TodoBlockSupport.resolved(context, "Hallo {{ name }}")).isEqualTo("Hallo Welt");
        assertThat(TodoBlockSupport.resolved(context, 7)).isEqualTo("7");
        assertThat(TodoBlockSupport.resolved(context, null)).isNull();
    }

    @Test
    void parsePriority_isCaseInsensitive_andFallsBackOnNullOrUnknown() {
        assertThat(TodoBlockSupport.parsePriority("high", Todo.Priority.LOW)).isEqualTo(Todo.Priority.HIGH);
        assertThat(TodoBlockSupport.parsePriority(null, Todo.Priority.MEDIUM)).isEqualTo(Todo.Priority.MEDIUM);
        assertThat(TodoBlockSupport.parsePriority("urgent", Todo.Priority.LOW)).isEqualTo(Todo.Priority.LOW);
    }

    @Test
    void parseSource_isCaseInsensitive_andFallsBackOnNullOrUnknown() {
        assertThat(TodoBlockSupport.parseSource("ai", Todo.Source.USER)).isEqualTo(Todo.Source.AI);
        assertThat(TodoBlockSupport.parseSource(null, Todo.Source.WORKFLOW)).isEqualTo(Todo.Source.WORKFLOW);
        assertThat(TodoBlockSupport.parseSource("robot", Todo.Source.WORKFLOW)).isEqualTo(Todo.Source.WORKFLOW);
    }

    @Test
    void parseTags_acceptsListsAndCommaStrings_andBlankIsAbsent() {
        assertThat(TodoBlockSupport.parseTags(List.of("a", 2))).containsExactly("a", "2");
        assertThat(TodoBlockSupport.parseTags(" backend , bug,ux ")).containsExactly("backend", "bug", "ux");
        assertThat(TodoBlockSupport.parseTags("   ")).isNull();
        assertThat(TodoBlockSupport.parseTags(null)).isNull();
    }

    /** 80796b2d: under tr-TR "high".toUpperCase() is "HİGH" and silently fell back. */
    @Test
    void parsePriorityAndSource_doNotDependOnTheDefaultLocale() {
        assertThat(TurkishLocale.call(() -> TodoBlockSupport.parsePriority(" high ", Todo.Priority.LOW)))
                .isEqualTo(Todo.Priority.HIGH);
        assertThat(TurkishLocale.call(() -> TodoBlockSupport.parseSource("ai", Todo.Source.USER)))
                .isEqualTo(Todo.Source.AI);
    }
}
