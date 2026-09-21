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
    void parse_criticalPriority() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: AssistantContextBuilder testen | CRITICAL | backend,testing | 541 LOC, baut Kontext");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).priority()).isEqualTo("CRITICAL");
        assertThat(items.get(0).description()).contains("541 LOC");
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
        assertThat(TodoOutputParser.parse("todo_item: Something | URGENT | backend")).isEmpty();
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

    @Test
    void parse_emptyString_returnsEmpty() {
        assertThat(TodoOutputParser.parse("")).isEmpty();
    }

    @Test
    void parse_missingAllPipes_noMatch() {
        assertThat(TodoOutputParser.parse("todo_item: Just a title without pipes")).isEmpty();
    }

    @Test
    void parse_missingTagsField_noMatch() {
        assertThat(TodoOutputParser.parse("todo_item: Fix something | HIGH")).isEmpty();
    }

    @Test
    void parse_combinedHeadingAndBold() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "### **todo_item: ComponentTest schreiben | HIGH | frontend,testing | 200 LOC ungetestet**");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("ComponentTest schreiben");
        assertThat(items.get(0).priority()).isEqualTo("HIGH");
        assertThat(items.get(0).tags()).containsExactly("frontend", "testing");
        assertThat(items.get(0).description()).isEqualTo("200 LOC ungetestet");
    }

    @Test
    void parse_singleHashHeading() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "# todo_item: Top-Level Finding | CRITICAL | security");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("Top-Level Finding");
        assertThat(items.get(0).priority()).isEqualTo("CRITICAL");
    }

    @Test
    void parse_sixHashHeading() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "###### todo_item: Deep nested heading | LOW | documentation");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("Deep nested heading");
        assertThat(items.get(0).priority()).isEqualTo("LOW");
    }

    @Test
    void parse_descriptionContainingPipe() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Pipeline fixen | HIGH | backend | Step A | Step B fehlerhaft");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("Pipeline fixen");
        assertThat(items.get(0).tags()).containsExactly("backend");
        // Description captures everything after the third pipe including additional pipes
        assertThat(items.get(0).description()).contains("Step");
    }

    @Test
    void parse_lowPriority() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Dokumentation ergaenzen | LOW | documentation | Nice-to-have Verbesserung");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).priority()).isEqualTo("LOW");
        assertThat(items.get(0).description()).isEqualTo("Nice-to-have Verbesserung");
    }

    @Test
    void parse_windowsLineEndings() {
        String output = "Report output\r\ntodo_item: Win test | HIGH | backend\r\ntodo_item: Win test 2 | MEDIUM | frontend | Desc\r\n";
        List<ParsedTodoItem> items = TodoOutputParser.parse(output);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("Win test");
        assertThat(items.get(1).description()).isEqualTo("Desc");
    }

    @Test
    void parse_singleTag() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Einfacher Test | MEDIUM | backend");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).tags()).containsExactly("backend");
    }

    @Test
    void parse_titleWithBackticks_cleaned() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: `SomeClass` testen | HIGH | backend,testing");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("SomeClass testen");
    }

    @Test
    void parse_descriptionWithMarkdown_cleaned() {
        List<ParsedTodoItem> items = TodoOutputParser.parse(
                "todo_item: Fix issue | HIGH | security | **Critical** `vulnerability` found");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).description()).isEqualTo("Critical vulnerability found");
    }

    @Test
    void parse_parsedTodoItemRecord_fieldsAccessible() {
        ParsedTodoItem item = new ParsedTodoItem("Title", "HIGH", List.of("tag1", "tag2"), "Description");
        assertThat(item.title()).isEqualTo("Title");
        assertThat(item.priority()).isEqualTo("HIGH");
        assertThat(item.tags()).containsExactly("tag1", "tag2");
        assertThat(item.description()).isEqualTo("Description");
    }

    @Test
    void parse_parsedTodoItemRecord_nullDescription() {
        ParsedTodoItem item = new ParsedTodoItem("Title", "HIGH", List.of("tag1"), null);
        assertThat(item.description()).isNull();
    }

    @Test
    void parse_manyItemsInBlock() {
        String output = """
                ## Audit Report

                todo_item: Item 1 | CRITICAL | security | Sofort fixen
                todo_item: Item 2 | HIGH | backend,testing
                todo_item: Item 3 | MEDIUM | frontend | UI-Verbesserung
                todo_item: Item 4 | LOW | documentation
                todo_item: Item 5 | HIGH | performance | Latenz zu hoch

                gate_status: RED
                """;
        List<ParsedTodoItem> items = TodoOutputParser.parse(output);
        assertThat(items).hasSize(5);
        assertThat(items.get(0).priority()).isEqualTo("CRITICAL");
        assertThat(items.get(1).priority()).isEqualTo("HIGH");
        assertThat(items.get(2).priority()).isEqualTo("MEDIUM");
        assertThat(items.get(3).priority()).isEqualTo("LOW");
        assertThat(items.get(4).priority()).isEqualTo("HIGH");
        assertThat(items.get(0).description()).isEqualTo("Sofort fixen");
        assertThat(items.get(1).description()).isNull();
        assertThat(items.get(3).description()).isNull();
    }

    /** foreach setzt "[<item>]: " vor jede Iterationsausgabe (TODO 8671d03b). */
    @Test
    void parse_markerBehindForeachItemPrefix() {
        String output = """
                [@types/react]: todo_item: React 19 migrieren | MEDIUM | dependencies,npm | Typen brechen
                [vite]: kein Befund
                """;
        List<ParsedTodoItem> items = TodoOutputParser.parse(output);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("React 19 migrieren");
        assertThat(items.get(0).description()).isEqualTo("Typen brechen");
    }

    @Test
    void parse_bracketTextInsideALine_isNoPrefix() {
        assertThat(TodoOutputParser.parse("Siehe [Link]: todo_item: X | HIGH | a"))
                .as("nur ein Präfix am Zeilenanfang zählt").isEmpty();
    }
}
