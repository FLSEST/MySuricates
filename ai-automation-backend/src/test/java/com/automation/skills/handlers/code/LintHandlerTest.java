package com.automation.skills.handlers.code;

import com.automation.skills.handlers.CapabilityHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LintHandlerTest {

    private LintHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LintHandler();
    }

    // --- Annotation & interface tests ---

    @Test
    void should_haveComponentAnnotation_when_classIsInspected() {
        assertThat(LintHandler.class.isAnnotationPresent(Component.class))
                .as("LintHandler must be annotated with @Component")
                .isTrue();
    }

    @Test
    void should_implementCapabilityHandler_when_classIsInspected() {
        assertThat(handler).isInstanceOf(CapabilityHandler.class);
    }

    // --- getName tests ---

    @Test
    void should_returnLint_when_getNameIsCalled() {
        assertThat(handler.getName()).isEqualTo("lint");
    }

    @Test
    void should_returnConsistentName_when_getNameIsCalledMultipleTimes() {
        assertThat(handler.getName()).isEqualTo(handler.getName());
    }

    // --- execute happy-path tests ---

    @Test
    void should_returnAiDelegatedType_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "int x = 1;");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsEntry("type", "ai-delegated");
    }

    @Test
    void should_includeCodeInCommand_when_executeIsCalled() {
        String code = "public void doStuff() { /* ... */ }";
        Map<String, Object> input = Map.of("code", code);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(code);
    }

    @Test
    void should_returnExactlyTwoKeys_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "int x = 1;");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat(result).containsOnlyKeys("command", "type");
    }

    @Test
    void should_useAutoDetectLanguage_when_languageIsNotProvided() {
        Map<String, Object> input = Map.of("code", "int x = 1;");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("auto-detect");
    }

    @Test
    void should_useProvidedLanguage_when_languageIsInInput() {
        Map<String, Object> input = Map.of("code", "int x = 1;", "language", "java");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("java");
        assertThat((String) result.get("command")).doesNotContain("auto-detect");
    }

    @Test
    void should_mentionStyleIssues_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("style issues");
    }

    @Test
    void should_mentionAntiPatterns_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("anti-patterns");
    }

    @Test
    void should_mentionBestPracticeViolations_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("best practice violations");
    }

    @Test
    void should_buildCorrectCommandFormat_when_executeIsCalled() {
        String code = "var x = 1; var y = 2;";
        Map<String, Object> input = Map.of("code", code, "language", "javascript");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command"))
                .startsWith("Lint the following javascript code.")
                .contains("style issues")
                .contains("anti-patterns")
                .contains("best practice violations")
                .endsWith(code);
    }

    // --- Edge cases ---

    @Test
    void should_handleEmptyCode_when_codeIsEmpty() {
        Map<String, Object> input = Map.of("code", "");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsKey("command");
        assertThat((String) result.get("command")).endsWith("\n\n");
    }

    @Test
    void should_treatMissingCodeAsNull_when_codeKeyIsAbsent() {
        Map<String, Object> input = Map.of();
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("null");
    }

    @Test
    void should_handleNullCodeValue_when_codeIsExplicitlyNull() {
        Map<String, Object> input = new HashMap<>();
        input.put("code", null);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("null");
    }

    @Test
    void should_handleMultilineCode_when_codeContainsNewlines() {
        String code = "public class Foo {\n    private int bar;\n    public int getBar() { return bar; }\n}";
        Map<String, Object> input = Map.of("code", code);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(code);
    }

    @Test
    void should_handleEmptyParams_when_paramsIsEmpty() {
        Map<String, Object> input = Map.of("code", "int x = 1;");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
    }

    @Test
    void should_handleEmptyLanguage_when_languageIsEmptyString() {
        Map<String, Object> input = Map.of("code", "int x = 1;", "language", "");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("Lint the following  code.");
    }
}
