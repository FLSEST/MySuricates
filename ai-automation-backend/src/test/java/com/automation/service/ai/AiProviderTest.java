package com.automation.service.ai;

import com.automation.service.ai.AiProvider.ActionRequest;
import com.automation.service.ai.AiProvider.ActionResult;
import com.automation.service.ai.AiProvider.ReasoningRequest;
import com.automation.service.ai.AiProvider.ReasoningResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the AiProvider interface — specifically the inner record types
 * (ReasoningRequest, ActionRequest, ReasoningResult, ActionResult) and their
 * backward-compatible convenience constructors.
 */
class AiProviderTest {

    @Nested
    class ReasoningRequestTests {

        @Test
        void should_default_optional_fields_to_null_when_constructed_with_prompt_only() {
            ReasoningRequest req = new ReasoningRequest("hello");

            assertThat(req.prompt()).isEqualTo("hello");
            assertThat(req.systemPrompt()).isNull();
            assertThat(req.workingDirectory()).isNull();
            assertThat(req.maxTurns()).isNull();
            assertThat(req.timeoutSeconds()).isNull();
        }

        @Test
        void should_set_systemPrompt_and_default_other_fields_when_two_arg_constructor_used() {
            ReasoningRequest req = new ReasoningRequest("p", "sys");

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.systemPrompt()).isEqualTo("sys");
            assertThat(req.workingDirectory()).isNull();
            assertThat(req.maxTurns()).isNull();
            assertThat(req.timeoutSeconds()).isNull();
        }

        @Test
        void should_assign_all_fields_when_full_constructor_used() {
            ReasoningRequest req = new ReasoningRequest("p", "s", "/work", 3, 60);

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.systemPrompt()).isEqualTo("s");
            assertThat(req.workingDirectory()).isEqualTo("/work");
            assertThat(req.maxTurns()).isEqualTo(3);
            assertThat(req.timeoutSeconds()).isEqualTo(60);
        }

        @Test
        void should_be_equal_when_records_have_same_values() {
            ReasoningRequest a = new ReasoningRequest("p", "s", "/w", 1, 10);
            ReasoningRequest b = new ReasoningRequest("p", "s", "/w", 1, 10);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    class ActionRequestTests {

        @Test
        void should_default_to_readOnly_true_and_priority_false_when_4_arg_constructor_used() {
            ActionRequest req = new ActionRequest("p", "claude-sonnet-4-6", "sys", "/work");

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(req.systemPrompt()).isEqualTo("sys");
            assertThat(req.workingDirectory()).isEqualTo("/work");
            assertThat(req.timeoutSeconds()).isNull();
            assertThat(req.priority()).isFalse();
            assertThat(req.readOnly()).isTrue();
            assertThat(req.allowedTools()).isNull();
            assertThat(req.disallowedTools()).isNull();
        }

        @Test
        void should_apply_timeout_and_keep_other_defaults_when_5_arg_constructor_used() {
            ActionRequest req = new ActionRequest("p", "m", "s", "/w", 120);

            assertThat(req.timeoutSeconds()).isEqualTo(120);
            assertThat(req.priority()).isFalse();
            assertThat(req.readOnly()).isTrue();
            assertThat(req.allowedTools()).isNull();
            assertThat(req.disallowedTools()).isNull();
        }

        @Test
        void should_apply_priority_and_default_readOnly_to_true_when_6_arg_constructor_used() {
            ActionRequest req = new ActionRequest("p", "m", "s", "/w", 90, true);

            assertThat(req.timeoutSeconds()).isEqualTo(90);
            assertThat(req.priority()).isTrue();
            assertThat(req.readOnly()).isTrue();
            assertThat(req.allowedTools()).isNull();
            assertThat(req.disallowedTools()).isNull();
        }

        @Test
        void should_set_priority_and_readOnly_explicitly_when_7_arg_constructor_used() {
            ActionRequest req = new ActionRequest("p", "m", "s", "/w", 90, true, false);

            assertThat(req.priority()).isTrue();
            assertThat(req.readOnly()).isFalse();
            assertThat(req.allowedTools()).isNull();
            assertThat(req.disallowedTools()).isNull();
        }

        @Test
        void should_apply_tool_whitelists_and_default_readOnly_to_true_when_8_arg_constructor_used() {
            List<String> allowed = List.of("Read", "Grep");
            List<String> disallowed = List.of("Bash");

            ActionRequest req = new ActionRequest("p", "m", "s", "/w", 30, true, allowed, disallowed);

            assertThat(req.priority()).isTrue();
            assertThat(req.readOnly()).isTrue();
            assertThat(req.allowedTools()).containsExactly("Read", "Grep");
            assertThat(req.disallowedTools()).containsExactly("Bash");
        }

        @Test
        void should_assign_all_fields_when_full_constructor_used() {
            List<String> allowed = List.of("Read");
            List<String> disallowed = List.of("Write");

            ActionRequest req = new ActionRequest(
                    "prompt", "model", "sys", "/work",
                    600, true, false, allowed, disallowed);

            assertThat(req.prompt()).isEqualTo("prompt");
            assertThat(req.model()).isEqualTo("model");
            assertThat(req.systemPrompt()).isEqualTo("sys");
            assertThat(req.workingDirectory()).isEqualTo("/work");
            assertThat(req.timeoutSeconds()).isEqualTo(600);
            assertThat(req.priority()).isTrue();
            assertThat(req.readOnly()).isFalse();
            assertThat(req.allowedTools()).isSameAs(allowed);
            assertThat(req.disallowedTools()).isSameAs(disallowed);
        }

        @Test
        void should_accept_null_optional_fields_in_full_constructor() {
            ActionRequest req = new ActionRequest(
                    "p", null, null, null, null, false, true, null, null);

            assertThat(req.prompt()).isEqualTo("p");
            assertThat(req.model()).isNull();
            assertThat(req.systemPrompt()).isNull();
            assertThat(req.workingDirectory()).isNull();
            assertThat(req.timeoutSeconds()).isNull();
            assertThat(req.allowedTools()).isNull();
            assertThat(req.disallowedTools()).isNull();
        }

        @Test
        void should_be_equal_when_records_have_same_values() {
            ActionRequest a = new ActionRequest("p", "m", "s", "/w");
            ActionRequest b = new ActionRequest("p", "m", "s", "/w");

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    class ResultRecordsTests {

        @Test
        void reasoningResult_should_expose_text_and_metadata() {
            AiResponseMetadata meta = AiResponseMetadata.estimated(100, 50);

            ReasoningResult result = new ReasoningResult("answer", meta);

            assertThat(result.text()).isEqualTo("answer");
            assertThat(result.metadata()).isSameAs(meta);
        }

        @Test
        void reasoningResult_should_allow_null_metadata() {
            ReasoningResult result = new ReasoningResult("answer", null);

            assertThat(result.text()).isEqualTo("answer");
            assertThat(result.metadata()).isNull();
        }

        @Test
        void actionResult_should_expose_text_exitCode_and_metadata() {
            AiResponseMetadata meta = AiResponseMetadata.estimated(200, 80);

            ActionResult result = new ActionResult("done", 0, meta);

            assertThat(result.text()).isEqualTo("done");
            assertThat(result.exitCode()).isZero();
            assertThat(result.metadata()).isSameAs(meta);
        }

        @Test
        void actionResult_should_capture_nonZero_exitCode() {
            ActionResult result = new ActionResult("error", 1, null);

            assertThat(result.text()).isEqualTo("error");
            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.metadata()).isNull();
        }

        @Test
        void resultRecords_should_be_equal_when_values_match() {
            ActionResult a = new ActionResult("t", 0, null);
            ActionResult b = new ActionResult("t", 0, null);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }
}
