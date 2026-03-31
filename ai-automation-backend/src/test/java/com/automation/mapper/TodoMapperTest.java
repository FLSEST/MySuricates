package com.automation.mapper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoMapperTest {

    @Test
    void jsonToList_validJson_returnsList() {
        List<String> result = TodoMapper.jsonToList("[\"backend\",\"testing\"]");

        assertThat(result).containsExactly("backend", "testing");
    }

    @Test
    void jsonToList_null_returnsEmptyList() {
        assertThat(TodoMapper.jsonToList(null)).isEmpty();
    }

    @Test
    void jsonToList_blank_returnsEmptyList() {
        assertThat(TodoMapper.jsonToList("  ")).isEmpty();
    }

    @Test
    void jsonToList_invalidJson_returnsEmptyList() {
        assertThat(TodoMapper.jsonToList("{not valid json}")).isEmpty();
    }

    @Test
    void listToJson_validList_returnsJson() {
        String result = TodoMapper.listToJson(List.of("a", "b"));

        assertThat(result).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void listToJson_null_returnsNull() {
        assertThat(TodoMapper.listToJson(null)).isNull();
    }

    @Test
    void listToJson_emptyList_returnsNull() {
        assertThat(TodoMapper.listToJson(List.of())).isNull();
    }
}
