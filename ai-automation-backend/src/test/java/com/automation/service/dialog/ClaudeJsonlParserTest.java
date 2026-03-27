package com.automation.service.dialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeJsonlParserTest {

    private final ClaudeJsonlParser parser = new ClaudeJsonlParser();

    @Test
    void parsesEmptyFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.jsonl");
        Files.writeString(file, "");
        var session = parser.parse(file);
        assertThat(session.turns()).isEmpty();
    }

    @Test
    void skipsFileHistorySnapshot(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.jsonl");
        Files.writeString(file, """
                {"type":"file-history-snapshot"}
                {"type":"user","sessionId":"abc","cwd":"/home/test","timestamp":"2026-01-01T10:00:00Z","message":{"role":"user","content":"Hello"}}
                """);
        var session = parser.parse(file);
        assertThat(session.sessionId()).isEqualTo("abc");
        assertThat(session.projectPath()).isEqualTo("/home/test");
        assertThat(session.turns()).hasSize(1);
        assertThat(session.turns().get(0).role()).isEqualTo("user");
    }

    @Test
    void parsesTextContentAsString(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.jsonl");
        Files.writeString(file, """
                {"type":"user","sessionId":"s1","cwd":"/proj","timestamp":"2026-01-01T10:00:00Z","message":{"role":"user","content":"Explain this"}}
                """);
        var session = parser.parse(file);
        assertThat(session.turns()).hasSize(1);
        List<ClaudeJsonlParser.DialogMessage> messages = session.turns().get(0).messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).type()).isEqualTo("text");
        assertThat(messages.get(0).text()).isEqualTo("Explain this");
    }

    @Test
    void parsesToolUseBlock(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.jsonl");
        Files.writeString(file, """
                {"type":"assistant","sessionId":"s1","cwd":"/proj","timestamp":"2026-01-01T10:01:00Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls -la"}}]}}
                """);
        var session = parser.parse(file);
        assertThat(session.turns()).hasSize(1);
        var msg = session.turns().get(0).messages().get(0);
        assertThat(msg.type()).isEqualTo("tool_use");
        assertThat(msg.toolName()).isEqualTo("Bash");
        assertThat(msg.toolInput()).isEqualTo("ls -la");
        assertThat(msg.toolUseId()).isEqualTo("toolu_1");
    }

    @Test
    void parsesToolResultBlock(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.jsonl");
        Files.writeString(file, """
                {"type":"user","sessionId":"s1","cwd":"/proj","timestamp":"2026-01-01T10:02:00Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"file1.txt\\nfile2.txt"}]}]}}
                """);
        var session = parser.parse(file);
        assertThat(session.turns()).hasSize(1);
        var msg = session.turns().get(0).messages().get(0);
        assertThat(msg.type()).isEqualTo("tool_result");
        assertThat(msg.resultText()).contains("file1.txt");
    }

    @Test
    void computesTimestampsCorrectly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.jsonl");
        Files.writeString(file, """
                {"type":"user","sessionId":"s1","cwd":"/proj","timestamp":"2026-01-01T10:00:00Z","message":{"role":"user","content":"first"}}
                {"type":"user","sessionId":"s1","cwd":"/proj","timestamp":"2026-01-01T10:30:00Z","message":{"role":"user","content":"last"}}
                """);
        var session = parser.parse(file);
        assertThat(session.startedAt()).isNotNull();
        assertThat(session.endedAt()).isNotNull();
        assertThat(session.endedAt()).isAfter(session.startedAt());
    }

    @Test
    void handlesInvalidJsonLinesGracefully(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.jsonl");
        Files.writeString(file, """
                NOT VALID JSON
                {"type":"user","sessionId":"s1","cwd":"/proj","timestamp":"2026-01-01T10:00:00Z","message":{"role":"user","content":"hello"}}
                """);
        var session = parser.parse(file);
        assertThat(session.turns()).hasSize(1);
    }
}
