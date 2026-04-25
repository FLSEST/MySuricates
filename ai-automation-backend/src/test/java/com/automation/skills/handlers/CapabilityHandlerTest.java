package com.automation.skills.handlers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityHandlerTest {

    @Test
    void should_return_name_and_execute_result_when_implemented() {
        CapabilityHandler handler = new CapabilityHandler() {
            @Override
            public String getName() {
                return "test-capability";
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
                return Map.of("status", "ok", "input_keys", input.size(), "param_keys", params.size());
            }
        };

        assertThat(handler.getName()).isEqualTo("test-capability");

        Map<String, Object> result = handler.execute(
                Map.of("text", "hello"),
                Map.of("mode", "fast")
        );

        assertThat(result)
                .containsEntry("status", "ok")
                .containsEntry("input_keys", 1)
                .containsEntry("param_keys", 1);
    }

    @Test
    void should_handle_empty_input_and_params_when_executed() {
        CapabilityHandler handler = new CapabilityHandler() {
            @Override
            public String getName() {
                return "noop";
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
                return Map.of();
            }
        };

        assertThat(handler.getName()).isEqualTo("noop");
        assertThat(handler.execute(Map.of(), Map.of())).isEmpty();
    }
}
