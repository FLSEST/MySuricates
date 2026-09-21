package com.automation.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardLayoutCanonicalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode layout() {
        return (ObjectNode) read("""
                {"views":[{"widgets":[{"type":"note","instanceId":"note-1"}],"name":"Main","id":"main"}],
                 "activeViewId":"main"}
                """);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode view(ObjectNode layout) {
        return (ObjectNode) layout.get("views").get(0);
    }

    private static ObjectNode widget(ObjectNode layout) {
        return (ObjectNode) view(layout).get("widgets").get(0);
    }

    private static String canonical(Consumer<ObjectNode> change) {
        ObjectNode layout = layout();
        change.accept(layout);
        return DashboardLayoutCanonicalizer.write(DashboardLayoutCanonicalizer.canonicalLayout(layout));
    }

    private static void rejects(Consumer<ObjectNode> change, String messagePart) {
        ObjectNode layout = layout();
        change.accept(layout);
        assertThatThrownBy(() -> DashboardLayoutCanonicalizer.canonicalLayout(layout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid dashboard layout: ")
                .hasMessageContaining(messagePart);
    }

    @Test
    void writes_fixed_key_order_regardless_of_input_order() {
        assertThat(canonical(l -> { })).isEqualTo(
                "{\"activeViewId\":\"main\",\"views\":[{\"id\":\"main\",\"name\":\"Main\",\"widgets\":["
                        + "{\"instanceId\":\"note-1\",\"type\":\"note\"}]}]}");
    }

    @Test
    void omits_defaults_and_empty_containers() {
        String out = canonical(l -> {
            view(l).putObject("filter").putNull("projectId").putNull("days");
            widget(l).put("hidden", false).putNull("size");
            widget(l).putObject("config");
        });

        assertThat(out).doesNotContain("filter", "hidden", "size", "config");
    }

    @Test
    void keeps_set_values_and_puts_filter_before_widgets() {
        String out = canonical(l -> {
            view(l).putObject("filter").put("days", 365).put("projectId", "project-1");
            widget(l).put("hidden", true).put("size", "twoThirds");
        });

        assertThat(out).isEqualTo(
                "{\"activeViewId\":\"main\",\"views\":[{\"id\":\"main\",\"name\":\"Main\","
                        + "\"filter\":{\"projectId\":\"project-1\",\"days\":365},\"widgets\":["
                        + "{\"instanceId\":\"note-1\",\"type\":\"note\",\"hidden\":true,\"size\":\"twoThirds\"}]}]}");
    }

    @Test
    void sorts_config_keys_and_normalizes_integral_doubles() {
        String out = canonical(l -> widget(l).putObject("config")
                .put("zeta", 3.0).put("alpha", "a").put("mid", true).put("frac", 1.5));

        assertThat(out).contains("\"config\":{\"alpha\":\"a\",\"frac\":1.5,\"mid\":true,\"zeta\":3}");
    }

    @Test
    void equal_layouts_with_int_and_integral_double_serialize_equally() {
        String asInt = canonical(l -> widget(l).putObject("config").put("count", 3));
        String asDouble = canonical(l -> widget(l).putObject("config").put("count", 3.0));

        assertThat(asInt).isEqualTo(asDouble);
    }

    @Test
    void huge_integral_double_stays_a_double() {
        ObjectNode l = layout();
        widget(l).putObject("config").put("big", 1e15).put("below", 999_999_999_999_999.0);

        JsonNode config = DashboardLayoutCanonicalizer.canonicalLayout(l)
                .get("views").get(0).get("widgets").get(0).get("config");

        assertThat(config.get("big").isDouble()).isTrue();
        assertThat(config.get("below").isIntegralNumber()).isTrue();
        assertThat(config.get("below").longValue()).isEqualTo(999_999_999_999_999L);
    }

    @Test
    void rejects_non_object_layout() {
        assertThatThrownBy(() -> DashboardLayoutCanonicalizer.canonicalLayout(null))
                .hasMessageContaining("layout must be an object");
        assertThatThrownBy(() -> DashboardLayoutCanonicalizer.canonicalLayout(MAPPER.createArrayNode()))
                .hasMessageContaining("layout must be an object");
    }

    @Test
    void rejects_unknown_fields_on_every_level() {
        rejects(l -> l.put("extra", 1), "layout has an unknown field: extra");
        rejects(l -> view(l).put("extra", 1), "layout.views[0] has an unknown field");
        rejects(l -> widget(l).put("extra", 1), "layout.views[0].widgets[0] has an unknown field");
        rejects(l -> view(l).putObject("filter").put("extra", 1), "filter has an unknown field");
    }

    @Test
    void view_count_must_be_between_one_and_twenty() {
        rejects(l -> l.putArray("views"), "1 to 20 views");
        rejects(l -> l.remove("views"), "1 to 20 views");
        rejects(l -> {
            ArrayNode views = l.putArray("views");
            for (int i = 0; i < 21; i++) {
                views.addObject().put("id", "v" + i).put("name", "").putArray("widgets");
            }
        }, "1 to 20 views");
    }

    @Test
    void twenty_views_are_accepted() {
        ObjectNode l = layout();
        ArrayNode views = l.putArray("views");
        for (int i = 0; i < 20; i++) {
            views.addObject().put("id", "v" + i).put("name", "").putArray("widgets");
        }
        l.put("activeViewId", "v19");

        assertThat(DashboardLayoutCanonicalizer.canonicalLayout(l).get("views")).hasSize(20);
    }

    @Test
    void active_view_must_reference_an_existing_view() {
        rejects(l -> l.put("activeViewId", "other"), "activeViewId must reference a view");
        rejects(l -> l.put("activeViewId", "Main"), "layout.activeViewId must match");
    }

    @Test
    void rejects_duplicate_view_and_instance_ids() {
        rejects(l -> ((ArrayNode) l.get("views")).add(view(l).deepCopy()), "layout.views[1].id is duplicate");
        rejects(l -> ((ArrayNode) view(l).get("widgets")).add(widget(l).deepCopy()),
                "widgets[1].instanceId is duplicate");
    }

    @Test
    void widget_count_is_capped_at_one_hundred() {
        rejects(l -> {
            ArrayNode widgets = view(l).putArray("widgets");
            for (int i = 0; i < 101; i++) {
                widgets.addObject().put("instanceId", "w" + i).put("type", "note");
            }
        }, "at most 100 entries");
        rejects(l -> view(l).put("widgets", "none"), "widgets must be an array");
    }

    @Test
    void view_name_is_counted_in_code_points() {
        String eighty = "\uD83D\uDE00".repeat(80);
        assertThat(canonical(l -> view(l).put("name", eighty))).contains(eighty);
        rejects(l -> view(l).put("name", eighty + "x"), "name must be a string of at most 80 characters");
        rejects(l -> view(l).put("name", 5), "name must be a string");
    }

    @Test
    void filter_days_must_be_an_integer_within_range() {
        rejects(l -> view(l).putObject("filter").put("days", 0), "days must be an integer from 1 to 365");
        rejects(l -> view(l).putObject("filter").put("days", 366), "days must be an integer from 1 to 365");
        rejects(l -> view(l).putObject("filter").put("days", 7.5), "days must be an integer");
        rejects(l -> view(l).putObject("filter").put("projectId", "Not An Id"), "filter.projectId must match");
    }

    @Test
    void widget_flags_and_sizes_are_type_checked() {
        rejects(l -> widget(l).put("hidden", "yes"), "hidden must be a boolean");
        rejects(l -> widget(l).put("size", "huge"), "size must be one of");
        rejects(l -> widget(l).put("type", "Note"), "type must match");
        rejects(l -> widget(l).remove("instanceId"), "instanceId must match");
    }

    @Test
    void config_rejects_bad_keys_and_values() {
        rejects(l -> widget(l).putObject("config").put("1key", "x"), "has an invalid key: 1key");
        rejects(l -> widget(l).putObject("config").put("key", "x".repeat(501)),
                "must be a string (at most 500 characters)");
        rejects(l -> widget(l).putObject("config").putObject("nested"), "config.nested must be a string");
        rejects(l -> widget(l).putObject("config").set("nan", DoubleNode.valueOf(Double.NaN)), "finite number");
        rejects(l -> widget(l).put("config", "x"), "config must be an object");
        rejects(l -> {
            ObjectNode config = widget(l).putObject("config");
            for (int i = 0; i < 21; i++) {
                config.put("k" + i, i);
            }
        }, "at most 20 keys");
    }

    @Test
    void config_string_of_exactly_five_hundred_characters_is_kept() {
        assertThat(canonical(l -> widget(l).putObject("config").put("text", "x".repeat(500))))
                .contains("x".repeat(500));
    }
}
