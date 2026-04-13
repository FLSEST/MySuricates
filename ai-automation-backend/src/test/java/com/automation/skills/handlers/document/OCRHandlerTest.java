package com.automation.skills.handlers.document;

import com.automation.skills.handlers.CapabilityHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OCRHandlerTest {

    private OCRHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OCRHandler();
    }

    // --- Annotation & interface tests ---

    @Test
    void should_haveComponentAnnotation_when_classIsInspected() {
        assertThat(OCRHandler.class.isAnnotationPresent(Component.class))
                .as("OCRHandler must be annotated with @Component")
                .isTrue();
    }

    @Test
    void should_implementCapabilityHandler_when_classIsInspected() {
        assertThat(handler).isInstanceOf(CapabilityHandler.class);
    }

    // --- getName tests ---

    @Test
    void should_returnOcr_when_getNameIsCalled() {
        assertThat(handler.getName()).isEqualTo("ocr");
    }

    @Test
    void should_returnConsistentName_when_getNameIsCalledMultipleTimes() {
        assertThat(handler.getName()).isEqualTo(handler.getName());
    }

    // --- execute happy-path tests ---

    @Test
    void should_returnAiDelegatedType_when_executeIsCalled() {
        Map<String, Object> input = Map.of("image", "/path/to/image.png");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsEntry("type", "ai-delegated");
    }

    @Test
    void should_includeImagePathInCommand_when_executeIsCalled() {
        String imagePath = "/data/photos/scan.jpg";
        Map<String, Object> input = Map.of("image", imagePath);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(imagePath);
    }

    @Test
    void should_returnExactlyTwoKeys_when_executeIsCalled() {
        Map<String, Object> input = Map.of("image", "/path/to/image.png");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat(result).containsOnlyKeys("command", "type");
    }

    @Test
    void should_buildCorrectCommandFormat_when_executeIsCalled() {
        Map<String, Object> input = Map.of("image", "/tmp/receipt.png");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command"))
                .startsWith("Extract all text from the image at: ")
                .endsWith("/tmp/receipt.png");
    }

    @Test
    void should_ignoreExtraParams_when_paramsAreProvided() {
        Map<String, Object> input = Map.of("image", "/img.png");
        Map<String, Object> params = Map.of("language", "de", "dpi", 300);

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("type", "ai-delegated");
    }

    // --- Edge cases ---

    @Test
    void should_handleEmptyImagePath_when_imageIsEmpty() {
        Map<String, Object> input = Map.of("image", "");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsKey("command");
        assertThat((String) result.get("command"))
                .isEqualTo("Extract all text from the image at: ");
    }

    @Test
    void should_treatMissingImageAsNull_when_imageKeyIsAbsent() {
        Map<String, Object> input = Map.of();
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("null");
    }

    @Test
    void should_handleNullImageValue_when_imageIsExplicitlyNull() {
        Map<String, Object> input = new HashMap<>();
        input.put("image", null);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("null");
    }

    @Test
    void should_handleLongImagePath_when_pathIsVeryLong() {
        String longPath = "/data/" + "a".repeat(500) + ".png";
        Map<String, Object> input = Map.of("image", longPath);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains(longPath);
    }

    @Test
    void should_ignoreExtraInputKeys_when_additionalKeysArePresent() {
        Map<String, Object> input = Map.of("image", "/img.png", "format", "pdf", "extra", 42);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).hasSize(2);
        assertThat((String) result.get("command")).contains("/img.png");
    }
}
