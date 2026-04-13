package com.automation.skills.handlers.document;

import com.automation.skills.handlers.CapabilityHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrammarCheckHandlerTest {

    private GrammarCheckHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GrammarCheckHandler();
    }

    // --- Annotation & interface tests ---

    @Test
    void should_haveComponentAnnotation_when_classIsInspected() {
        assertThat(GrammarCheckHandler.class.isAnnotationPresent(Component.class))
                .as("GrammarCheckHandler must be annotated with @Component")
                .isTrue();
    }

    @Test
    void should_implementCapabilityHandler_when_classIsInspected() {
        assertThat(handler).isInstanceOf(CapabilityHandler.class);
    }

    // --- getName tests ---

    @Test
    void should_returnGrammarCheck_when_getNameIsCalled() {
        assertThat(handler.getName()).isEqualTo("grammar_check");
    }

    @Test
    void should_returnConsistentName_when_getNameIsCalledMultipleTimes() {
        assertThat(handler.getName()).isEqualTo(handler.getName());
    }

    // --- execute happy-path tests ---

    @Test
    void should_returnAiDelegatedType_when_executeIsCalled() {
        Map<String, Object> input = Map.of("text", "This is a test sentence.");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsEntry("type", "ai-delegated");
    }

    @Test
    void should_includeTextInCommand_when_executeIsCalled() {
        String text = "The quick brown fox jumps over the lazy dog.";
        Map<String, Object> input = Map.of("text", text);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(text);
    }

    @Test
    void should_returnExactlyTwoKeys_when_executeIsCalled() {
        Map<String, Object> input = Map.of("text", "Some text to check.");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat(result).containsOnlyKeys("command", "type");
    }

    @Test
    void should_buildCorrectCommandFormat_when_executeIsCalled() {
        Map<String, Object> input = Map.of("text", "Test text.");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command"))
                .startsWith("Check the following text for grammar, spelling, and style issues.")
                .contains("Provide corrections and explanations")
                .endsWith("Test text.");
    }

    @Test
    void should_ignoreExtraParams_when_paramsAreProvided() {
        Map<String, Object> input = Map.of("text", "Hello world.");
        Map<String, Object> params = Map.of("language", "en", "style", "formal");

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("type", "ai-delegated");
    }

    // --- Edge cases ---

    @Test
    void should_handleEmptyText_when_textIsEmpty() {
        Map<String, Object> input = Map.of("text", "");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsKey("command");
        assertThat((String) result.get("command")).endsWith("\n\n");
    }

    @Test
    void should_treatMissingTextAsNull_when_textKeyIsAbsent() {
        Map<String, Object> input = Map.of();
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("null");
    }

    @Test
    void should_handleNullTextValue_when_textIsExplicitlyNull() {
        Map<String, Object> input = new HashMap<>();
        input.put("text", null);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("null");
    }

    @Test
    void should_handleLongText_when_textIsVeryLong() {
        String longText = "a".repeat(5000);
        Map<String, Object> input = Map.of("text", longText);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(longText);
    }

    @Test
    void should_ignoreExtraInputKeys_when_additionalKeysArePresent() {
        Map<String, Object> input = Map.of("text", "Check this.", "format", "pdf", "extra", 42);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat((String) result.get("command")).contains("Check this.");
    }
}
