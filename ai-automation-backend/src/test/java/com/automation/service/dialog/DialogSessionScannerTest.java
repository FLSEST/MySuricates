package com.automation.service.dialog;

import com.automation.service.dialog.DialogSessionScanner.DialogSessionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DialogSessionScannerTest {

    private final DialogSessionScanner scanner = new DialogSessionScanner();
    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    class EncodeDecodeTests {

        @Test
        void encodeProjectPath_replacesSlashes() {
            assertThat(DialogSessionScanner.encodeProjectPath("/home/user/project"))
                    .isEqualTo("-home-user-project");
        }

        @Test
        void decodeProjectPath_restoresSlashes() {
            assertThat(DialogSessionScanner.decodeProjectPath("-home-user-project"))
                    .isEqualTo("/home/user/project");
        }

        @Test
        void encodeAndDecode_roundTrip() {
            String original = "/home/flo/Entwicklung/tools/autotrigger";
            String encoded = DialogSessionScanner.encodeProjectPath(original);
            String decoded = DialogSessionScanner.decodeProjectPath(encoded);
            assertThat(decoded).isEqualTo(original);
        }

        @Test
        void decodeProjectPath_withoutLeadingDash_returnsAsIs() {
            assertThat(DialogSessionScanner.decodeProjectPath("noleadingdash"))
                    .isEqualTo("noleadingdash");
        }
    }

    @Nested
    class BuildSessionInfoTests {

        @TempDir
        Path tempDir;

        @Test
        void buildSessionInfo_parsesBasicSession() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "session1.jsonl", List.of(
                    createMessage("user", "session-1", "/project", "2026-01-01T10:00:00Z",
                            "This is my first user message for testing"),
                    createAssistantMessage("session-1", "2026-01-01T10:01:00Z",
                            List.of(Map.of("type", "text", "text", "Hello!")))
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.sessionId()).isEqualTo("session-1");
            assertThat(info.projectPath()).isEqualTo("/project");
            assertThat(info.turnCount()).isEqualTo(2);
            assertThat(info.firstUserMessage()).contains("first user message");
        }

        @Test
        void buildSessionInfo_countsToolCalls() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "session2.jsonl", List.of(
                    createMessage("user", "session-2", "/project", "2026-01-01T10:00:00Z",
                            "Fix the bug"),
                    createAssistantWithTools("session-2", "2026-01-01T10:01:00Z",
                            List.of("Read", "Edit", "Read"))
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.toolCallCount()).isEqualTo(3);
            assertThat(info.dominantTools()).isNotEmpty();
        }

        @Test
        void buildSessionInfo_usesHistoryTitle() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "session3.jsonl", List.of(
                    createMessage("user", "session-3", "/project", "2026-01-01T10:00:00Z",
                            "This is a long user message for testing")
            ));

            Map<String, String> historyTitles = Map.of("session-3", "History Title");
            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, historyTitles);

            assertThat(info).isNotNull();
            assertThat(info.firstUserMessage()).isEqualTo("History Title");
        }

        @Test
        void buildSessionInfo_fallsBackToFirstMessage_whenNoHistoryTitle() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "session4.jsonl", List.of(
                    createMessage("user", "session-4", "/project", "2026-01-01T10:00:00Z",
                            "This is a long user message for fallback testing purposes")
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.firstUserMessage()).contains("long user message");
        }

        @Test
        void buildSessionInfo_substantial_requiresBothThresholds() throws Exception {
            // 3+ tool calls AND 2+ turns = substantial
            Path jsonlFile = createJsonlFile(tempDir, "session5.jsonl", List.of(
                    createMessage("user", "s-5", "/project", "2026-01-01T10:00:00Z",
                            "Implement this feature for me please"),
                    createAssistantWithTools("s-5", "2026-01-01T10:01:00Z",
                            List.of("Read", "Edit", "Write"))
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.substantial()).isTrue();
        }

        @Test
        void buildSessionInfo_notSubstantial_tooFewToolCalls() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "session6.jsonl", List.of(
                    createMessage("user", "s-6", "/project", "2026-01-01T10:00:00Z",
                            "Just a simple question for testing here"),
                    createAssistantMessage("s-6", "2026-01-01T10:01:00Z",
                            List.of(Map.of("type", "text", "text", "Answer")))
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.substantial()).isFalse();
        }

        @Test
        void buildSessionInfo_skipsSystemWrapperMessages() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "session7.jsonl", List.of(
                    createMessage("user", "s-7", "/project", "2026-01-01T10:00:00Z",
                            "<system-reminder>This is injected</system-reminder>"),
                    createMessage("user", "s-7", "/project", "2026-01-01T10:00:01Z",
                            "This is the actual user message for the test session")
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.firstUserMessage()).contains("actual user message");
        }

        @Test
        void buildSessionInfo_extractsTimestampRange() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "session8.jsonl", List.of(
                    createMessage("user", "s-8", "/project", "2026-01-01T10:00:00Z",
                            "This is a sufficiently long message for the test"),
                    createMessage("user", "s-8", "/project", "2026-01-01T11:30:00Z",
                            "And another sufficiently long message for testing")
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.startedAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
            assertThat(info.endedAt()).isEqualTo(Instant.parse("2026-01-01T11:30:00Z"));
        }

        @Test
        void buildSessionInfo_skipsFileHistoryType() throws Exception {
            String fileHistoryLine = mapper.writeValueAsString(Map.of(
                    "type", "file-history-snapshot",
                    "sessionId", "s-9",
                    "timestamp", "2026-01-01T09:00:00Z"
            ));
            Path jsonlFile = createJsonlFile(tempDir, "session9.jsonl", List.of(
                    fileHistoryLine,
                    createMessage("user", "s-9", "/project", "2026-01-01T10:00:00Z",
                            "This is the real first user message for tests")
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            // file-history-snapshot should not count as a turn
            assertThat(info.turnCount()).isEqualTo(1);
        }

        @Test
        void buildSessionInfo_sessionIdFromFilename_whenNotInJson() throws Exception {
            Path jsonlFile = createJsonlFile(tempDir, "abc-123.jsonl", List.of(
                    mapper.writeValueAsString(Map.of(
                            "type", "user",
                            "timestamp", "2026-01-01T10:00:00Z",
                            "message", Map.of("content", "Short msg")
                    ))
            ));

            DialogSessionInfo info = invokeBuildSessionInfo(jsonlFile, Map.of());

            assertThat(info).isNotNull();
            assertThat(info.sessionId()).isEqualTo("abc-123");
        }

        // --- Helpers ---

        private DialogSessionInfo invokeBuildSessionInfo(Path jsonlFile, Map<String, String> historyTitles) throws Exception {
            Method method = DialogSessionScanner.class.getDeclaredMethod(
                    "buildSessionInfo", Path.class, Map.class);
            method.setAccessible(true);
            return (DialogSessionInfo) method.invoke(scanner, jsonlFile, historyTitles);
        }

        private String createMessage(String type, String sessionId, String cwd, String timestamp, String content) throws Exception {
            Map<String, Object> entry = new HashMap<>();
            entry.put("type", type);
            entry.put("sessionId", sessionId);
            entry.put("cwd", cwd);
            entry.put("timestamp", timestamp);
            entry.put("message", Map.of("content", content));
            return mapper.writeValueAsString(entry);
        }

        private String createAssistantMessage(String sessionId, String timestamp, List<Map<String, Object>> blocks) throws Exception {
            Map<String, Object> entry = new HashMap<>();
            entry.put("type", "assistant");
            entry.put("sessionId", sessionId);
            entry.put("timestamp", timestamp);
            entry.put("message", Map.of("content", blocks));
            return mapper.writeValueAsString(entry);
        }

        private String createAssistantWithTools(String sessionId, String timestamp, List<String> toolNames) throws Exception {
            List<Map<String, Object>> blocks = new java.util.ArrayList<>();
            for (String name : toolNames) {
                blocks.add(Map.of("type", "tool_use", "name", name, "id", "tc-" + name));
            }
            return createAssistantMessage(sessionId, timestamp, blocks);
        }

        private Path createJsonlFile(Path dir, String filename, List<String> lines) throws IOException {
            Path file = dir.resolve(filename);
            Files.writeString(file, String.join("\n", lines));
            return file;
        }
    }
}
