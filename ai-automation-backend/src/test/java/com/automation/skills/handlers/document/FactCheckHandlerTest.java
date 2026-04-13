package com.automation.skills.handlers.document;

import com.automation.skills.handlers.CapabilityHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactCheckHandlerTest {

    private FactCheckHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FactCheckHandler();
    }

    // --- Annotation & interface tests ---

    @Test
    void should_haveComponentAnnotation_when_classIsInspected() {
        assertThat(FactCheckHandler.class.isAnnotationPresent(Component.class))
                .as("FactCheckHandler must be annotated with @Component")
                .isTrue();
    }

    @Test
    void should_implementCapabilityHandler_when_classIsInspected() {
        assertThat(handler).isInstanceOf(CapabilityHandler.class);
    }

    // --- getName tests ---

    @Test
    void should_returnFactCheck_when_getNameIsCalled() {
        assertThat(handler.getName()).isEqualTo("fact_check");
    }

    @Test
    void should_returnConsistentName_when_getNameIsCalledMultipleTimes() {
        assertThat(handler.getName()).isEqualTo(handler.getName());
    }

    // --- execute happy-path tests ---

    @Test
    void should_returnAiDelegatedType_when_executeIsCalled() {
        Map<String, Object> input = Map.of("text", "The earth is flat.");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsEntry("type", "ai-delegated");
    }

    @Test
    void should_useDefaultMediumStrictness_when_noStrictnessProvided() {
        Map<String, Object> input = Map.of("text", "Some claim here.");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("medium strictness");
    }

    @Test
    void should_useProvidedStrictness_when_strictnessIsInParams() {
        Map<String, Object> input = Map.of("text", "Some claim here.");
        Map<String, Object> params = Map.of("strictness", "high");

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("high strictness");
    }

    @Test
    void should_includeTextInCommand_when_executeIsCalled() {
        String text = "Water boils at 100 degrees Celsius.";
        Map<String, Object> input = Map.of("text", text);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(text);
    }

    @Test
    void should_returnExactlyTwoKeys_when_executeIsCalled() {
        Map<String, Object> input = Map.of("text", "A claim.");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat(result).containsOnlyKeys("command", "type");
    }

    @Test
    void should_buildCorrectCommandFormat_when_executeIsCalled() {
        Map<String, Object> input = Map.of("text", "Test text.");
        Map<String, Object> params = Map.of("strictness", "low");

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command"))
                .startsWith("Fact-check the following text with low strictness.")
                .contains("Flag any unverifiable claims")
                .endsWith("Test text.");
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
    void should_handleLowStrictness_when_strictnessIsLow() {
        Map<String, Object> input = Map.of("text", "Claim.");
        Map<String, Object> params = Map.of("strictness", "low");

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("low strictness");
    }

    @Test
    void should_handleEmptyStrictness_when_strictnessIsEmpty() {
        Map<String, Object> input = Map.of("text", "Claim.");
        Map<String, Object> params = Map.of("strictness", "");

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(" strictness");
    }
}
