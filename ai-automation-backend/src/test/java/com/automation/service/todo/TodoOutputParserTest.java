package com.automation.service.todo;

import com.automation.service.todo.TodoOutputParser.ParsedTodoItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoOutputParserTest {

    @Test
    void parse_singleItem() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: ChainExecutor vollständig testen | HIGH | backend,testing");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("ChainExecutor vollständig testen");
        assertThat(items.get(0).priority()).isEqualTo("HIGH");
        assertThat(items.get(0).tags()).containsExactly("backend", "testing");
    }

    @Test
    void parse_multipleItems() {
        String output = """
                Some report text here...

                todo_item: ChainExecutor testen | HIGH | backend,testing
                todo_item: useWebSocket Hook testen | MEDIUM | frontend,testing
                todo_item: Plugin-System dokumentieren | LOW | documentation
                """;
        List<ParsedTodoItem> items = TodoOutputParser.parse(output);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).priority()).isEqualTo("HIGH");
        assertThat(items.get(1).priority()).isEqualTo("MEDIUM");
        assertThat(items.get(2).priority()).isEqualTo("LOW");
    }

    @Test
    void parse_caseInsensitive() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "TODO_ITEM: Test schreiben | high | backend");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).priority()).isEqualTo("HIGH");
    }

    @Test
    void parse_hyphenVariant() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo-item: Test schreiben | MEDIUM | backend");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("Test schreiben");
    }

    @Test
    void parse_markdownBold() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "**todo_item: Test schreiben | HIGH | backend**");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("Test schreiben");
    }

    @Test
    void parse_markdownHeading() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "### todo_item: InstanceSyncHelper testen | HIGH | backend,testing");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("InstanceSyncHelper testen");
    }

    @Test
    void parse_backticks() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "`todo_item`: ChainExecutor testen | HIGH | backend,testing");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("ChainExecutor testen");
    }

    @Test
    void parse_embeddedInLongerOutput() {
        String output = """
                ## Test Coverage Report

                Coverage is 25.8%.

                ### Findings
                | # | Class | Severity |
                |---|-------|----------|
                | 1 | ChainExecutor | CRITICAL |

                ### Action Items
                todo_item: ChainExecutor vollständig testen | HIGH | backend,testing
                todo_item: ExecutionController testen | HIGH | backend,testing

                ### Summary
                10 findings total.

                gate_status: YELLOW
                gate_category: test-coverage
                """;
        List<ParsedTodoItem> items = TodoOutputParser.parse(output);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("ChainExecutor vollständig testen");
        assertThat(items.get(1).title()).isEqualTo("ExecutionController testen");
    }

    @Test
    void parse_noItems_returnsEmpty() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "Some output without any todo markers");
        assertThat(items).isEmpty();
    }

    @Test
    void parse_null_returnsEmpty() {
        assertThat(TodoOutputParser.parse(null)).isEmpty();
    }

    @Test
    void parse_blank_returnsEmpty() {
        assertThat(TodoOutputParser.parse("  ")).isEmpty();
    }

    @Test
    void parse_invalidPriority_noMatch() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Something | CRITICAL | backend");
        assertThat(items).isEmpty();
    }

    @Test
    void parse_midLine_noMatch() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "The output has todo_item: Test | HIGH | backend in the middle");
        assertThat(items).isEmpty();
    }

    @Test
    void parse_multipleTagsWithSpaces() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Fix security issue | HIGH | security, backend, urgent");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).tags()).containsExactly("security", "backend", "urgent");
    }

    @Test
    void parse_singleTag() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Update docs | LOW | documentation");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).tags()).containsExactly("documentation");
    }
}
