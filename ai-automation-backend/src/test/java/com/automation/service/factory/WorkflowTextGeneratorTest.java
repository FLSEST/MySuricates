package com.automation.service.factory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTextGeneratorTest {

    @Test
    void should_be_an_interface() {
        assertThat(WorkflowTextGenerator.class.isInterface()).isTrue();
    }

    @Test
    void should_declare_generate_method_with_expected_signature() throws NoSuchMethodException {
        Method method = WorkflowTextGenerator.class.getMethod(
                "generate", String.class, String.class, int.class);

        assertThat(method.getReturnType()).isEqualTo(String.class);
        assertThat(method.getParameterTypes())
                .containsExactly(String.class, String.class, int.class);
    }

    @Test
    void should_declare_ioexception_in_throws_clause() throws NoSuchMethodException {
        Method method = WorkflowTextGenerator.class.getMethod(
                "generate", String.class, String.class, int.class);

        assertThat(Arrays.asList(method.getExceptionTypes()))
                .contains(IOException.class);
    }

    @Test
    void should_return_value_from_implementation_when_invoked() throws IOException {
        WorkflowTextGenerator generator = (prompt, systemPrompt, timeoutSeconds) ->
                "result:" + prompt + "|" + systemPrompt + "|" + timeoutSeconds;

        String result = generator.generate("hello", "system", 42);

        assertThat(result).isEqualTo("result:hello|system|42");
    }

    @Test
    void should_propagate_ioexception_from_implementation() {
        WorkflowTextGenerator generator = (prompt, systemPrompt, timeoutSeconds) -> {
            throw new IOException("boom");
        };

        assertThatThrownBy(() -> generator.generate("p", "s", 10))
                .isInstanceOf(IOException.class)
                .hasMessage("boom");
    }

    @Test
    void should_pass_through_null_arguments_to_implementation() throws IOException {
        WorkflowTextGenerator generator = (prompt, systemPrompt, timeoutSeconds) ->
                (prompt == null ? "null" : prompt) + "/" + (systemPrompt == null ? "null" : systemPrompt);

        String result = generator.generate(null, null, 0);

        assertThat(result).isEqualTo("null/null");
    }

    @Test
    void should_accept_zero_and_negative_timeouts_in_contract() throws IOException {
        WorkflowTextGenerator generator = (prompt, systemPrompt, timeoutSeconds) ->
                String.valueOf(timeoutSeconds);

        assertThat(generator.generate("p", "s", 0)).isEqualTo("0");
        assertThat(generator.generate("p", "s", -1)).isEqualTo("-1");
    }
}
