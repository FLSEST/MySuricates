package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateWorkflowTemplateRequestTest {

    private UpdateWorkflowTemplateRequest sample() {
        return new UpdateWorkflowTemplateRequest(
                Map.of("de", "Mein Template", "en", "My Template"),
                Map.of("de", "Beschreibung", "en", "Description"),
                "rocket",
                "automation",
                List.of(BlockDefinition.builder().type("shell").build()),
                "Run carefully",
                "claude-sonnet-4-6",
                42
        );
    }

    @Nested
    @DisplayName("Constructor and accessors")
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_via_accessors() {
            UpdateWorkflowTemplateRequest request = sample();

            assertThat(request.name()).containsEntry("de", "Mein Template").containsEntry("en", "My Template");
            assertThat(request.description()).containsEntry("de", "Beschreibung").containsEntry("en", "Description");
            assertThat(request.icon()).isEqualTo("rocket");
            assertThat(request.category()).isEqualTo("automation");
            assertThat(request.blocks()).hasSize(1);
            assertThat(request.blocks().get(0).getType()).isEqualTo("shell");
            assertThat(request.instructions()).isEqualTo("Run carefully");
            assertThat(request.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(request.sortOrder()).isEqualTo(42);
        }

        @Test
        void should_allow_null_optional_fields() {
            UpdateWorkflowTemplateRequest request = new UpdateWorkflowTemplateRequest(
                    null, null, null, null, null, null, null, null
            );

            assertThat(request.name()).isNull();
            assertThat(request.description()).isNull();
            assertThat(request.icon()).isNull();
            assertThat(request.category()).isNull();
            assertThat(request.blocks()).isNull();
            assertThat(request.instructions()).isNull();
            assertThat(request.model()).isNull();
            assertThat(request.sortOrder()).isNull();
        }

        @Test
        void should_accept_empty_blocks_list() {
            UpdateWorkflowTemplateRequest request = new UpdateWorkflowTemplateRequest(
                    Map.of("en", "Name"),
                    Map.of("en", "Desc"),
                    "icon",
                    "cat",
                    List.of(),
                    "instr",
                    "model",
                    0
            );

            assertThat(request.blocks()).isEmpty();
            assertThat(request.sortOrder()).isZero();
        }

        @Test
        void should_accept_empty_translation_maps() {
            UpdateWorkflowTemplateRequest request = new UpdateWorkflowTemplateRequest(
                    Map.of(),
                    Map.of(),
                    "icon",
                    "cat",
                    List.of(),
                    null,
                    null,
                    1
            );

            assertThat(request.name()).isEmpty();
            assertThat(request.description()).isEmpty();
        }

        @Test
        void should_preserve_block_order() {
            BlockDefinition first = BlockDefinition.builder().type("shell").build();
            BlockDefinition second = BlockDefinition.builder().type("simple").build();
            BlockDefinition third = BlockDefinition.builder().type("agentic").build();

            UpdateWorkflowTemplateRequest request = new UpdateWorkflowTemplateRequest(
                    Map.of("en", "n"),
                    Map.of("en", "d"),
                    "icon",
                    "cat",
                    List.of(first, second, third),
                    null,
                    null,
                    1
            );

            assertThat(request.blocks()).containsExactly(first, second, third);
        }
    }

    @Nested
    @DisplayName("Record equality and hashCode")
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            BlockDefinition sharedBlock = BlockDefinition.builder().type("shell").build();
            UpdateWorkflowTemplateRequest a = new UpdateWorkflowTemplateRequest(
                    Map.of("de", "Mein Template", "en", "My Template"),
                    Map.of("de", "Beschreibung", "en", "Description"),
                    "rocket", "automation", List.of(sharedBlock),
                    "Run carefully", "claude-sonnet-4-6", 42
            );
            UpdateWorkflowTemplateRequest b = new UpdateWorkflowTemplateRequest(
                    Map.of("de", "Mein Template", "en", "My Template"),
                    Map.of("de", "Beschreibung", "en", "Description"),
                    "rocket", "automation", List.of(sharedBlock),
                    "Run carefully", "claude-sonnet-4-6", 42
            );

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_icon_differs() {
            UpdateWorkflowTemplateRequest a = sample();
            UpdateWorkflowTemplateRequest b = new UpdateWorkflowTemplateRequest(
                    a.name(), a.description(), "different-icon", a.category(),
                    a.blocks(), a.instructions(), a.model(), a.sortOrder()
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_not_be_equal_when_sort_order_differs() {
            UpdateWorkflowTemplateRequest a = sample();
            UpdateWorkflowTemplateRequest b = new UpdateWorkflowTemplateRequest(
                    a.name(), a.description(), a.icon(), a.category(),
                    a.blocks(), a.instructions(), a.model(), 999
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_all_fields_in_toString() {
            String repr = sample().toString();

            assertThat(repr)
                    .contains("rocket")
                    .contains("automation")
                    .contains("claude-sonnet-4-6")
                    .contains("Run carefully")
                    .contains("42");
        }
    }
}
