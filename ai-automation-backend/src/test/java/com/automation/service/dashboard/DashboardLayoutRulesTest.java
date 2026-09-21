package com.automation.service.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardLayoutRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void invalid_prefixes_every_message() {
        assertThat(DashboardLayoutRules.invalid("x broke"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid dashboard layout: x broke");
    }

    @Test
    void only_format_version_two_is_supported() {
        assertThat(DashboardLayoutRules.SUPPORTED_FORMAT_VERSIONS).containsExactly(2);
    }

    @Test
    void require_object_rejects_null_and_non_objects() {
        assertThatCode(() -> DashboardLayoutRules.requireObject(MAPPER.createObjectNode(), "p")).doesNotThrowAnyException();
        assertThatThrownBy(() -> DashboardLayoutRules.requireObject(null, "layout.views[0]"))
                .hasMessage("Invalid dashboard layout: layout.views[0] must be an object");
        assertThatThrownBy(() -> DashboardLayoutRules.requireObject(MAPPER.createArrayNode(), "p"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DashboardLayoutRules.requireObject(JsonNodeFactory.instance.nullNode(), "p"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void require_only_fields_names_the_first_unknown_field() {
        ObjectNode node = MAPPER.createObjectNode().put("id", "a").put("color", "red");

        assertThatCode(() -> DashboardLayoutRules.requireOnlyFields(node, Set.of("id", "color"), "w"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DashboardLayoutRules.requireOnlyFields(node, Set.of("id"), "w"))
                .hasMessage("Invalid dashboard layout: w has an unknown field: color");
    }

    @Test
    void required_id_accepts_lowercase_slug_up_to_eighty_characters() {
        assertThat(DashboardLayoutRules.requiredId(TextNode.valueOf("note-1a2b"), "p")).isEqualTo("note-1a2b");
        assertThat(DashboardLayoutRules.requiredId(TextNode.valueOf("0"), "p")).isEqualTo("0");
        String eighty = "a".repeat(DashboardLayoutRules.MAX_ID_LENGTH);
        assertThat(DashboardLayoutRules.requiredId(TextNode.valueOf(eighty), "p")).isEqualTo(eighty);
    }

    @Test
    void required_id_rejects_everything_else() {
        for (String bad : new String[]{"", "-lead", "Upper", "under_score", "with space", "a".repeat(81), "ümlaut"}) {
            assertThatThrownBy(() -> DashboardLayoutRules.requiredId(TextNode.valueOf(bad), "view.id"))
                    .as(bad)
                    .hasMessageStartingWith("Invalid dashboard layout: view.id must match")
                    .hasMessageContaining("at most 80 characters");
        }
        assertThatThrownBy(() -> DashboardLayoutRules.requiredId(null, "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DashboardLayoutRules.requiredId(JsonNodeFactory.instance.numberNode(1), "p"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void echo_replaces_control_characters_and_caps_length() {
        assertThat(DashboardLayoutRules.echo("bad\nkey\u0000")).isEqualTo("bad?key?");
        assertThat(DashboardLayoutRules.echo("x".repeat(40))).isEqualTo("x".repeat(40));
        assertThat(DashboardLayoutRules.echo("x".repeat(41))).isEqualTo("x".repeat(40) + "…");
        assertThat(DashboardLayoutRules.echo("")).isEmpty();
    }
}
