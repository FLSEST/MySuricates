package com.automation.service.todo;

import com.automation.service.todo.TodoOutputParser.ParsedTodoItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoOutputParserTest {

    @Test
    void parse_withDescription() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: ChainExecutor testen | HIGH | backend,testing | 160-LOC Execution-Loop komplett ungetestet. Siehe: docs/REPORT.md#1");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("ChainExecutor testen");
        assertThat(items.get(0).priority()).isEqualTo("HIGH");
        assertThat(items.get(0).tags()).containsExactly("backend", "testing");
        assertThat(items.get(0).description()).isEqualTo("160-LOC Execution-Loop komplett ungetestet. Siehe: docs/REPORT.md#1");
    }

    @Test
    void parse_withoutDescription() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: ChainExecutor testen | HIGH | backend,testing");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("ChainExecutor testen");
        assertThat(items.get(0).description()).isNull();
    }

    @Test
    void parse_multipleItems_mixedDescriptions() {
        String output = """
                Some report text here...

                todo_item: ChainExecutor testen | HIGH | backend,testing | 160 LOC, Multi-Entry/stopOnFailure ungetestet
                todo_item: useWebSocket Hook testen | MEDIUM | frontend,testing
                todo_item: Plugin-System dokumentieren | LOW | documentation | Fehlt komplett. Siehe: docs/PLUGINS.md
                """;
        List<ParsedTodoItem> items = TodoOutputParser.parse(output);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).description()).isEqualTo("160 LOC, Multi-Entry/stopOnFailure ungetestet");
        assertThat(items.get(1).description()).isNull();
        assertThat(items.get(2).description()).startsWith("Fehlt komplett");
    }

    @Test
    void parse_caseInsensitive() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "TODO_ITEM: Test schreiben | high | backend | Wichtig fuer Stabilitaet");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).priority()).isEqualTo("HIGH");
        assertThat(items.get(0).description()).isEqualTo("Wichtig fuer Stabilitaet");
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
                "### todo_item: InstanceSyncHelper testen | HIGH | backend,testing | 491 LOC ohne Tests");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).description()).isEqualTo("491 LOC ohne Tests");
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

                ### Action Items
                todo_item: ChainExecutor testen | HIGH | backend,testing | 160 LOC Execution-Loop
                todo_item: ExecutionController testen | HIGH | backend,testing | 5 von 13 Endpoints ungetestet

                gate_status: YELLOW
                """;
        List<ParsedTodoItem> items = TodoOutputParser.parse(output);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).description()).isEqualTo("160 LOC Execution-Loop");
        assertThat(items.get(1).description()).isEqualTo("5 von 13 Endpoints ungetestet");
    }

    @Test
    void parse_noItems_returnsEmpty() {
        assertThat(TodoOutputParser.parse("Some output without any todo markers")).isEmpty();
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
        assertThat(TodoOutputParser.parse("todo_item: Something | CRITICAL | backend")).isEmpty();
    }

    @Test
    void parse_midLine_noMatch() {
        assertThat(TodoOutputParser.parse("The output has todo_item: Test | HIGH | backend in the middle")).isEmpty();
    }

    @Test
    void parse_multipleTagsWithSpaces() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Fix issue | HIGH | security, backend, urgent | CVE-2024-1234 betroffen");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).tags()).containsExactly("security", "backend", "urgent");
        assertThat(items.get(0).description()).isEqualTo("CVE-2024-1234 betroffen");
    }

    @Test
    void parse_descriptionWithSpecialChars() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Fix auth | HIGH | security | XSS in <script>-Tags, CORS zu permissiv (*.example.com)");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).description()).contains("XSS");
    }
}
