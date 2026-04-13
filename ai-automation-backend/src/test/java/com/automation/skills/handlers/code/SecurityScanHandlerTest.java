package com.automation.skills.handlers.code;

import com.automation.skills.handlers.CapabilityHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityScanHandlerTest {

    private SecurityScanHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SecurityScanHandler();
    }

    // --- Annotation & interface tests ---

    @Test
    void should_haveComponentAnnotation_when_classIsInspected() {
        assertThat(SecurityScanHandler.class.isAnnotationPresent(Component.class))
                .as("SecurityScanHandler must be annotated with @Component")
                .isTrue();
    }

    @Test
    void should_implementCapabilityHandler_when_classIsInspected() {
        assertThat(handler).isInstanceOf(CapabilityHandler.class);
    }

    // --- getName tests ---

    @Test
    void should_returnSecurityScan_when_getNameIsCalled() {
        assertThat(handler.getName()).isEqualTo("security_scan");
    }

    @Test
    void should_returnConsistentName_when_getNameIsCalledMultipleTimes() {
        assertThat(handler.getName()).isEqualTo(handler.getName());
    }

    // --- execute happy-path tests ---

    @Test
    void should_returnAiDelegatedType_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "String query = \"SELECT * FROM users WHERE id=\" + userId;");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat(result).containsEntry("type", "ai-delegated");
    }

    @Test
    void should_includeCodeInCommand_when_executeIsCalled() {
        String code = "public void login(String user, String pass) { /* ... */ }";
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
    void should_mentionOwaspTop10_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("OWASP Top 10");
    }

    @Test
    void should_mentionInjectionFlaws_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("injection flaws");
    }

    @Test
    void should_mentionXss_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("XSS");
    }

    @Test
    void should_mentionAuthenticationProblems_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("authentication problems");
    }

    @Test
    void should_mentionSensitiveDataExposure_when_executeIsCalled() {
        Map<String, Object> input = Map.of("code", "some code");
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command")).contains("sensitive data exposure");
    }

    @Test
    void should_buildCorrectCommandFormat_when_executeIsCalled() {
        String code = "String secret = \"password123\";";
        Map<String, Object> input = Map.of("code", code);
        Map<String, Object> params = Map.of();

        Map<String, Object> result = handler.execute(input, params);

        assertThat((String) result.get("command"))
                .startsWith("Scan the following code for security vulnerabilities.")
                .contains("OWASP Top 10")
                .contains("injection flaws")
                .contains("XSS")
                .contains("authentication problems")
                .contains("sensitive data exposure")
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
        String code = "public class Foo {\n    private String secret;\n    public String getSecret() { return secret; }\n}";
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
}
