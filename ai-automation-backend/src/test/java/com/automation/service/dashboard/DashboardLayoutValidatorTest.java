package com.automation.service.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardLayoutValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DashboardLayoutValidator validator = new DashboardLayoutValidator(MAPPER);

    private static ObjectNode validBody() {
        try {
            return (ObjectNode) MAPPER.readTree("""
                    {
                      "deviceLabel": "  Laptop  ",
                      "formatVersion": 2,
                      "baseVersionId": "version-1",
                      "force": false,
                      "layout": {
                        "views": [{
                          "widgets": [
                            {"type": "todos", "instanceId": "todos", "hidden": false},
                            {"instanceId": "note-1a2b3c4d", "type": "note", "size": "full",
                             "config": {"text": "hi", "count": 3, "on": true}}
                          ],
                          "name": "",
                          "id": "default",
                          "filter": {}
                        }],
                        "activeViewId": "default"
                      }
                    }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode layout(ObjectNode body) {
        return (ObjectNode) body.get("layout");
    }

    private static ObjectNode view(ObjectNode body, int index) {
        return (ObjectNode) layout(body).get("views").get(index);
    }

    private static ObjectNode widget(ObjectNode body, int index) {
        return (ObjectNode) view(body, 0).get("widgets").get(index);
    }

    private static ObjectNode config(ObjectNode body) {
        return (ObjectNode) widget(body, 1).get("config");
    }

    @Test
    void accepts_a_valid_body_and_returns_canonical_content() {
        ValidatedLayoutSave save = validator.validateSave(validBody());

        assertThat(save.deviceLabel()).isEqualTo("Laptop");
        assertThat(save.formatVersion()).isEqualTo(2);
        assertThat(save.baseVersionId()).isEqualTo("version-1");
        assertThat(save.force()).isFalse();
        assertThat(save.viewCount()).isEqualTo(1);
        assertThat(save.canonicalContent()).isEqualTo(
                "{\"activeViewId\":\"default\",\"views\":[{\"id\":\"default\",\"name\":\"\",\"widgets\":["
                        + "{\"instanceId\":\"todos\",\"type\":\"todos\"},"
                        + "{\"instanceId\":\"note-1a2b3c4d\",\"type\":\"note\",\"size\":\"full\","
                        + "\"config\":{\"count\":3,\"on\":true,\"text\":\"hi\"}}]}]}");
        assertThat(save.contentHash()).isEqualTo(DashboardLayoutValidator.sha256Hex(save.canonicalContent()));
    }

    @Test
    void equal_layouts_hash_equally_regardless_of_key_order() {
        ObjectNode reordered = validBody();
        ObjectNode shuffledConfig = MAPPER.createObjectNode().put("on", true).put("text", "hi").put("count", 3);
        widget(reordered, 1).set("config", shuffledConfig);
        view(reordered, 0).remove("filter");
        widget(reordered, 0).remove("hidden");

        assertThat(validator.validateSave(reordered).contentHash())
                .isEqualTo(validator.validateSave(validBody()).contentHash());
    }

    @Test
    void accepts_missing_base_and_force() {
        ObjectNode body = validBody();
        body.remove("baseVersionId");
        body.remove("force");

        ValidatedLayoutSave save = validator.validateSave(body);

        assertThat(save.baseVersionId()).isNull();
        assertThat(save.force()).isFalse();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("unknown field: extra", (Consumer<ObjectNode>) b -> b.put("extra", 1)),
                Arguments.of("formatVersion must be one of", (Consumer<ObjectNode>) b -> b.put("formatVersion", 3)),
                Arguments.of("deviceLabel is required", (Consumer<ObjectNode>) b -> b.remove("deviceLabel")),
                Arguments.of("deviceLabel must have 1 to 80 characters",
                        (Consumer<ObjectNode>) b -> b.put("deviceLabel", "Lap\ntop")),
                Arguments.of("force must be a boolean", (Consumer<ObjectNode>) b -> b.put("force", "yes")),
                Arguments.of("layout must be an object", (Consumer<ObjectNode>) b -> b.remove("layout")),
                Arguments.of("layout.views must be an array with 1 to 20 views",
                        (Consumer<ObjectNode>) b -> layout(b).putArray("views")),
                Arguments.of("layout.views must be an array with 1 to 20 views", (Consumer<ObjectNode>) b -> {
                    ArrayNode views = layout(b).putArray("views");
                    for (int i = 0; i < 21; i++) {
                        views.addObject().put("id", "v" + i).put("name", "").putArray("widgets");
                    }
                }),
                Arguments.of("activeViewId must reference a view",
                        (Consumer<ObjectNode>) b -> layout(b).put("activeViewId", "other")),
                Arguments.of("layout.views[1].id is duplicate", (Consumer<ObjectNode>) b ->
                        ((ArrayNode) layout(b).get("views")).addObject().put("id", "default").put("name", "")
                                .putArray("widgets")),
                Arguments.of("widgets[2].instanceId is duplicate", (Consumer<ObjectNode>) b ->
                        ((ArrayNode) view(b, 0).get("widgets")).addObject().put("instanceId", "todos").put("type", "todos")),
                Arguments.of("widgets[0].instanceId must match",
                        (Consumer<ObjectNode>) b -> widget(b, 0).put("instanceId", "Bad Id")),
                Arguments.of("widgets[0].instanceId must match",
                        (Consumer<ObjectNode>) b -> widget(b, 0).put("instanceId", "a".repeat(81))),
                Arguments.of("size must be one of", (Consumer<ObjectNode>) b -> widget(b, 1).put("size", "huge")),
                Arguments.of("hidden must be a boolean", (Consumer<ObjectNode>) b -> widget(b, 0).put("hidden", "yes")),
                Arguments.of("unknown field: label", (Consumer<ObjectNode>) b -> widget(b, 0).put("label", "x")),
                Arguments.of("config.nested must be a string", (Consumer<ObjectNode>) b -> config(b).putObject("nested")),
                Arguments.of("config.text must be a string",
                        (Consumer<ObjectNode>) b -> config(b).put("text", "x".repeat(501))),
                Arguments.of("invalid key: <script>", (Consumer<ObjectNode>) b -> config(b).put("<script>", "x")),
                Arguments.of("must have at most 20 keys", (Consumer<ObjectNode>) b -> {
                    for (int i = 0; i < 21; i++) {
                        config(b).put("k" + i, i);
                    }
                }),
                Arguments.of("days must be an integer from 1 to 365",
                        (Consumer<ObjectNode>) b -> view(b, 0).putObject("filter").put("days", 0)),
                Arguments.of("days must be an integer from 1 to 365",
                        (Consumer<ObjectNode>) b -> view(b, 0).putObject("filter").put("days", 366)),
                Arguments.of("name must be a string of at most 80 characters",
                        (Consumer<ObjectNode>) b -> view(b, 0).put("name", "n".repeat(81)))
        );
    }

    @ParameterizedTest(name = "rejects: {0}")
    @MethodSource("invalidBodies")
    void rejects_invalid_bodies(String expectedMessagePart, Consumer<ObjectNode> mutation) {
        ObjectNode body = validBody();
        mutation.accept(body);

        assertThatThrownBy(() -> validator.validateSave(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid dashboard layout: ")
                .hasMessageContaining(expectedMessagePart);
    }

    @Test
    void rename_accepts_a_trimmed_name_or_null() throws Exception {
        assertThat(validator.validateRename(MAPPER.readTree("{\"name\":\"  vor dem Umbau \"}"))).isEqualTo("vor dem Umbau");
        assertThat(validator.validateRename(MAPPER.readTree("{\"name\":null}"))).isNull();
    }

    @Test
    void rename_rejects_missing_name_and_unknown_fields() throws Exception {
        assertThatThrownBy(() -> validator.validateRename(MAPPER.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name is required");
        assertThatThrownBy(() -> validator.validateRename(MAPPER.readTree("{\"name\":\"x\",\"extra\":1}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown field: extra");
    }
}
