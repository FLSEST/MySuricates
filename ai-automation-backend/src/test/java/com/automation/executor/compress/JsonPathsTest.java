package com.automation.executor.compress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPathsTest {

    @Test
    void parse_acceptsSurroundingWhitespaceAndScalars() {
        assertThat(JsonPaths.parse("  \n{\"a\":1}\n  ", "op").get("a").asInt()).isEqualTo(1);
        assertThat(JsonPaths.parse("42", "op").asInt()).isEqualTo(42);
    }

    @Test
    void parse_emptyOrInvalid_namesTheOp() {
        assertThatThrownBy(() -> JsonPaths.parse("", "json.x"))
                .isInstanceOf(CompressionException.class).hasMessage("Stage json.x: input is not valid JSON");
        assertThatThrownBy(() -> JsonPaths.parse("   ", "json.x")).isInstanceOf(CompressionException.class);
        assertThatThrownBy(() -> JsonPaths.parse("{\"a\":", "json.x")).isInstanceOf(CompressionException.class);
    }

    @Test
    void write_isCompact() {
        assertThat(JsonPaths.write(JsonPaths.parse("{ \"a\" : [ 1 , 2 ] }", "op"))).isEqualTo("{\"a\":[1,2]}");
    }

    @Test
    void at_navigatesDottedPaths_andReturnsNullWhenBlocked() {
        JsonNode root = JsonPaths.parse("{\"a\":{\"b\":5},\"arr\":[{\"c\":1}],\"s\":\"x\"}", "op");

        assertThat(JsonPaths.at(root, "")).isSameAs(root);
        assertThat(JsonPaths.at(root, null)).isSameAs(root);
        assertThat(JsonPaths.at(root, "a.b").asInt()).isEqualTo(5);
        assertThat(JsonPaths.at(root, "a.fehlt")).isNull();
        assertThat(JsonPaths.at(root, "a.fehlt.tiefer")).isNull();
        assertThat(JsonPaths.at(root, "arr.c")).as("Arrays werden nicht durchlaufen").isNull();
        assertThat(JsonPaths.at(root, "s.x")).isNull();
    }

    @Test
    void arrayIn_returnsLiveArrayOfTheParsedRoot() {
        JsonPaths.Document doc = JsonPaths.arrayIn("{\"items\":[1,2]}", "items", "op");

        doc.array().remove(0);

        assertThat(JsonPaths.write(doc.root())).isEqualTo("{\"items\":[2]}");
    }

    @Test
    void arrayAt_notAnArray_namesPathOrRoot() {
        JsonNode root = JsonPaths.parse("{\"a\":1}", "op");

        assertThatThrownBy(() -> JsonPaths.arrayAt(root, "", "json.limit"))
                .isInstanceOf(CompressionException.class).hasMessage("Stage json.limit: '(root)' is not an array");
        assertThatThrownBy(() -> JsonPaths.arrayAt(root, "a", "json.limit"))
                .hasMessage("Stage json.limit: 'a' is not an array");
        assertThatThrownBy(() -> JsonPaths.arrayAt(root, "nope", "json.limit"))
                .hasMessage("Stage json.limit: 'nope' is not an array");
    }

    @Test
    void missing_coversJavaNullJsonNullAndMissingNode() {
        assertThat(JsonPaths.missing(null)).isTrue();
        assertThat(JsonPaths.missing(NullNode.getInstance())).isTrue();
        assertThat(JsonPaths.missing(MissingNode.getInstance())).isTrue();
        assertThat(JsonPaths.missing(TextNode.valueOf(""))).isFalse();
    }
}
