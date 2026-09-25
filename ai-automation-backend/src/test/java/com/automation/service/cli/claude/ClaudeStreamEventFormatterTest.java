package com.automation.service.cli.claude;

import com.automation.service.cli.claude.ClaudeStreamEventFormatter.Line;
import com.automation.service.live.LiveLineKind;
import com.automation.service.live.LiveOutputSink;
import com.automation.util.LineListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ClaudeStreamEventFormatterTest {

    private static List<Line> format(String json) {
        return ClaudeStreamEventFormatter.format(json, 0);
    }

    @Test
    void systemInit_showsModelAndToolCount() {
        assertThat(format("""
                {"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-5","tools":["Bash","Read","Edit"]}"""))
                .containsExactly(new Line(LiveLineKind.SYSTEM, "session · claude-opus-5 · 3 tools"));
    }

    @Test
    void unknownSystemSubtypesAndEventTypesAreIgnored() {
        assertThat(format("{\"type\":\"system\",\"subtype\":\"hook_response\"}")).isEmpty();
        assertThat(format("{\"type\":\"rate_limit_event\"}")).isEmpty();
        assertThat(format("   ")).isEmpty();
    }

    @Test
    void assistantText_isCollapsedToOneLineAndTruncated() {
        String longText = "Ich prüfe\n\nzuerst die Tests. " + "x".repeat(400);
        List<Line> lines = format("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":"
                + quote(longText) + "}]}}");

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).kind()).isEqualTo(LiveLineKind.TEXT);
        assertThat(lines.get(0).text()).startsWith("Ich prüfe zuerst die Tests. xxx").endsWith("…")
                .hasSize(ClaudeStreamEventFormatter.TEXT_PREVIEW_CHARS + 1);
    }

    @Test
    void assistantToolUses_areSummarized_thinkingIsSkipped() {
        // fixture from ClaudeOutputParserTest
        List<Line> lines = format("""
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"hmm"},{"type":"tool_use","name":"Read","input":{"file_path":"src/main.ts"}},{"type":"tool_use","name":"Edit","input":{"file_path":"src/main.ts","old":"a","new":"b"}}]}}""");

        assertThat(lines).containsExactly(
                new Line(LiveLineKind.TOOL, "Read: src/main.ts"),
                new Line(LiveLineKind.TOOL, "Edit: src/main.ts"));
    }

    @Test
    void toolSummaries_perTool() throws Exception {
        assertThat(summary("Bash", "{\"command\":\"mvn test\\n  -q\"}")).isEqualTo("Bash: mvn test …");
        assertThat(summary("Bash", "{\"command\":\"npm   run  lint\"}")).isEqualTo("Bash: npm run lint");
        assertThat(summary("Grep", "{\"pattern\":\"TODO\",\"path\":\"src\"}")).isEqualTo("Grep: TODO in src");
        assertThat(summary("Glob", "{\"pattern\":\"**/*.ts\"}")).isEqualTo("Glob: **/*.ts");
        assertThat(summary("WebFetch", "{\"url\":\"https://example.org\"}")).isEqualTo("WebFetch: https://example.org");
        assertThat(summary("WebSearch", "{\"query\":\"spring stomp\"}")).isEqualTo("WebSearch: spring stomp");
        assertThat(summary("Task", "{\"description\":\"Explore tests\",\"prompt\":\"long\"}")).isEqualTo("Task: Explore tests");
        assertThat(summary("TodoWrite", "{\"todos\":[{},{}]}")).isEqualTo("TodoWrite: 2 todos");
        assertThat(summary("NotebookEdit", "{\"notebook_path\":\"a.ipynb\"}")).isEqualTo("NotebookEdit: a.ipynb");
        assertThat(summary("mcp__playwright__browser_click", "{\"ref\":\"e12\"}"))
                .isEqualTo("playwright/browser_click: {\"ref\":\"e12\"}");
        assertThat(summary("Unknown", "{}")).isEqualTo("Unknown");
        assertThat(summary("Custom", "{\"blob\":\"" + "y".repeat(500) + "\"}"))
                .hasSize(ClaudeStreamEventFormatter.TOOL_SUMMARY_CHARS + 1).endsWith("…");
    }

    @Test
    void toolResults_showSizeAndErrorFlagOnly() {
        List<Line> lines = format("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"%s"},{"type":"tool_result","tool_use_id":"t2","is_error":true,"content":[{"type":"text","text":"command not found"}]}]}}"""
                .formatted("a".repeat(1300)));

        assertThat(lines).containsExactly(
                new Line(LiveLineKind.TOOL_RESULT, "↳ 1.3 KB"),
                new Line(LiveLineKind.TOOL_ERROR, "↳ error · 17 B"));
    }

    @Test
    void resultSuccess_summarizesTurnsCostAndDuration() {
        // fixture from ClaudeOutputParserTest
        assertThat(format("""
                {"type":"result","subtype":"success","result":"Hello world","session_id":"sess-123","total_cost_usd":0.004,"duration_ms":3200,"duration_api_ms":2800,"num_turns":1}"""))
                .containsExactly(new Line(LiveLineKind.RESULT, "✓ 1 turn · $0.004 · 3s"));
    }

    @Test
    void resultError_showsSubtype() {
        assertThat(format("""
                {"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":12,"total_cost_usd":1.5,"duration_ms":192000}"""))
                .containsExactly(new Line(LiveLineKind.RESULT, "✗ error_max_turns · 12 turns · $1.50 · 3m 12s"));
    }

    @Test
    void nonJsonLines_arePassedThroughRaw() {
        assertThat(format("Error: authentication failed"))
                .containsExactly(new Line(LiveLineKind.LINE, "Error: authentication failed"));
        assertThat(format("{not json at all"))
                .containsExactly(new Line(LiveLineKind.LINE, "{not json at all"));
    }

    @Test
    void truncatedLines_areClassifiedByPrefix() {
        assertThat(ClaudeStreamEventFormatter.format("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_res", 5_000_000))
                .containsExactly(new Line(LiveLineKind.TOOL_RESULT, "↳ > 2.0 MB"));
        assertThat(ClaudeStreamEventFormatter.format("{\"type\":\"assistant\",\"message\":{", 5_000_000))
                .containsExactly(new Line(LiveLineKind.TOOL, "(large message)"));
        assertThat(ClaudeStreamEventFormatter.format("{\"type\":\"result\",\"result\":\"aaa", 5_000_000)).isEmpty();
        assertThat(ClaudeStreamEventFormatter.format("plain text", 10))
                .containsExactly(new Line(LiveLineKind.LINE, "plain text …"));
    }

    @Test
    void randomInputNeverThrows() {
        Random random = new Random(7);
        String alphabet = "{}[]\":,type assistant user result tool_use content 0123 \\ \nä";
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder(random.nextBoolean() ? "{\"type\":\"assistant\"," : "");
            int len = random.nextInt(80);
            for (int j = 0; j < len; j++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String input = sb.toString();
            assertThatCode(() -> ClaudeStreamEventFormatter.format(input, random.nextInt(3))).doesNotThrowAnyException();
        }
    }

    @Test
    void listener_isNoneForInactiveSink_andFormatsIntoActiveSink() {
        assertThat(ClaudeStreamEventFormatter.listener(LiveOutputSink.NOOP)).isSameAs(LineListener.NONE);

        List<Line> emitted = new ArrayList<>();
        LineListener listener = ClaudeStreamEventFormatter.listener((kind, text) -> emitted.add(new Line(kind, text)));
        listener.onLine("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}", 0);

        assertThat(listener.maxLineBytes()).isEqualTo(ClaudeStreamEventFormatter.MAX_JSON_LINE_BYTES);
        assertThat(emitted).containsExactly(new Line(LiveLineKind.TOOL, "Bash: ls"));
    }

    @Test
    void formatBytesAndDuration() {
        assertThat(ClaudeStreamEventFormatter.formatBytes(0)).isEqualTo("0 B");
        assertThat(ClaudeStreamEventFormatter.formatBytes(1023)).isEqualTo("1023 B");
        assertThat(ClaudeStreamEventFormatter.formatBytes(3 * 1024 * 1024)).isEqualTo("3.0 MB");
        assertThat(ClaudeStreamEventFormatter.formatDuration(59_400)).isEqualTo("59s");
        assertThat(ClaudeStreamEventFormatter.formatDuration(61_000)).isEqualTo("1m 1s");
    }

    private static String summary(String tool, String inputJson) throws Exception {
        return ClaudeStreamEventFormatter.summarizeTool(tool, new ObjectMapper().readTree(inputJson));
    }

    private static String quote(String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
