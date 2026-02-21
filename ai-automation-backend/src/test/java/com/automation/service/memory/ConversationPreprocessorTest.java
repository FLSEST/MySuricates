package com.automation.service.memory;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPreprocessorTest {

    private ConversationPreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        preprocessor = new ConversationPreprocessor();
    }

    @Test
    void preprocess_basicSteps() {
        StepResult step = StepResult.builder()
                .stepIndex(0)
                .input("echo hello")
                .output("hello world")
                .status(StepStatus.COMPLETED)
                .metadata(new HashMap<>())
                .build();

        String result = preprocessor.preprocess(List.of(step));

        assertThat(result).contains("=== Step 0 ===");
        assertThat(result).contains("Input: echo hello");
        assertThat(result).contains("hello world");
        assertThat(result).contains("Status: COMPLETED");
    }

    @Test
    void compressCodeBlocks_largeBlockReplaced() {
        StringBuilder largeCode = new StringBuilder("```java\n");
        for (int i = 0; i < 50; i++) {
            largeCode.append("line ").append(i).append("\n");
        }
        largeCode.append("```");

        String result = preprocessor.compressCodeBlocks(largeCode.toString());

        assertThat(result).contains("[Code block: ");
        assertThat(result).contains("lines, java]");
        assertThat(result).doesNotContain("line 25");
    }

    @Test
    void compressCodeBlocks_smallBlockKept() {
        String smallCode = "```python\nprint('hello')\nprint('world')\n```";

        String result = preprocessor.compressCodeBlocks(smallCode);

        assertThat(result).contains("print('hello')");
    }

    @Test
    void stripMetaCommentary_removesMetaLines() {
        String input = "Let me check the configuration\nActual content here\nI'll now run the tests\nMore content";

        String result = preprocessor.stripMetaCommentary(input);

        assertThat(result).doesNotContain("Let me check");
        assertThat(result).doesNotContain("I'll now run");
        assertThat(result).contains("Actual content here");
        assertThat(result).contains("More content");
    }

    @Test
    void collapseRepeatedFailures_collapsesRepeats() {
        String input = "Run tests → exit code 1\nRun tests → exit code 1\nRun tests → exit code 1\nRun tests → exit code 1\nRun tests → exit code 1\nSuccess";

        String result = preprocessor.collapseRepeatedFailures(input);

        assertThat(result).contains("[Repeated 5x: Run tests");
        assertThat(result).contains("Success");
    }

    @Test
    void collapseRepeatedFailures_keepsUniqueLines() {
        String input = "line one\nline two\nline three";

        String result = preprocessor.collapseRepeatedFailures(input);

        assertThat(result).contains("line one");
        assertThat(result).contains("line two");
        assertThat(result).contains("line three");
        assertThat(result).doesNotContain("[Repeated");
    }

    @Test
    void compressDataDumps_largeJsonReplaced() {
        StringBuilder dump = new StringBuilder("{\n");
        for (int i = 0; i < 25; i++) {
            dump.append("  \"key").append(i).append("\": \"value").append(i).append("\",\n");
        }
        dump.append("}");

        String result = preprocessor.compressDataDumps(dump.toString());

        assertThat(result).contains("[Data dump:");
        assertThat(result).contains("lines]");
    }

    @Test
    void processOutput_appliesAllRules() {
        String input = "Let me look at the files\nActual output\nActual output\nActual output\nActual output";

        String result = preprocessor.processOutput(input);

        assertThat(result).doesNotContain("Let me look");
        assertThat(result).contains("[Repeated 4x: Actual output]");
    }

    @Test
    void preprocess_handlesEmptySteps() {
        StepResult step = StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.COMPLETED)
                .metadata(new HashMap<>())
                .build();

        String result = preprocessor.preprocess(List.of(step));

        assertThat(result).contains("=== Step 0 ===");
        assertThat(result).contains("Status: COMPLETED");
    }

    @Test
    void preprocess_handlesAgenticIterations() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("iterations", List.of(
                Map.of("reasoning", "Check config files", "action", "read config.yml", "phase", "acted", "output", "Found settings"),
                Map.of("reasoning", "Apply changes", "action", "write config.yml", "phase", "completed", "output", "Done")
        ));

        StepResult step = StepResult.builder()
                .stepIndex(0)
                .input("Fix config")
                .output("Config fixed")
                .status(StepStatus.COMPLETED)
                .metadata(metadata)
                .build();

        String result = preprocessor.preprocess(List.of(step));

        assertThat(result).contains("[Iter 1/2 | acted]");
        assertThat(result).contains("[Iter 2/2 | completed]");
        assertThat(result).contains("Check config files");
    }
}
