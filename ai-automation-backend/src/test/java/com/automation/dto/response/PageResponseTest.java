package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var items = List.of("alpha", "beta", "gamma");
        var response = new PageResponse<>(items, 0, 10, 25L, 3);

        assertThat(response.content()).containsExactly("alpha", "beta", "gamma");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(25L);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void should_returnEmptyContent_when_constructedWithEmptyList() {
        var response = new PageResponse<>(List.of(), 0, 10, 0L, 0);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void should_acceptNullContent_when_constructed() {
        var response = new PageResponse<String>(null, 0, 10, 0L, 0);

        assertThat(response.content()).isNull();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
    }

    @Test
    void should_returnCorrectPagingMetadata_when_middlePage() {
        var items = List.of("item1", "item2");
        var response = new PageResponse<>(items, 2, 5, 42L, 9);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(42L);
        assertThat(response.totalPages()).isEqualTo(9);
    }

    @Test
    void should_returnCorrectPagingMetadata_when_lastPage() {
        var items = List.of("only-item");
        var response = new PageResponse<>(items, 4, 10, 41L, 5);

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isEqualTo(4);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(41L);
        assertThat(response.totalPages()).isEqualTo(5);
    }

    @Test
    void should_handleLargeTotalElements_when_constructed() {
        var response = new PageResponse<>(List.of("a"), 0, 20, Long.MAX_VALUE, Integer.MAX_VALUE);

        assertThat(response.totalElements()).isEqualTo(Long.MAX_VALUE);
        assertThat(response.totalPages()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void should_workWithIntegerType_when_constructed() {
        var items = List.of(1, 2, 3);
        var response = new PageResponse<>(items, 0, 10, 3L, 1);

        assertThat(response.content()).containsExactly(1, 2, 3);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new PageResponse<>(List.of("x", "y"), 1, 10, 20L, 2);
        var b = new PageResponse<>(List.of("x", "y"), 1, 10, 20L, 2);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothEmptyContent() {
        var a = new PageResponse<>(List.of(), 0, 10, 0L, 0);
        var b = new PageResponse<>(List.of(), 0, 10, 0L, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothNullContent() {
        var a = new PageResponse<String>(null, 0, 5, 0L, 0);
        var b = new PageResponse<String>(null, 0, 5, 0L, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentContent() {
        var a = new PageResponse<>(List.of("a"), 0, 10, 1L, 1);
        var b = new PageResponse<>(List.of("b"), 0, 10, 1L, 1);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentPage() {
        var a = new PageResponse<>(List.of("x"), 0, 10, 10L, 1);
        var b = new PageResponse<>(List.of("x"), 1, 10, 10L, 1);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSize() {
        var a = new PageResponse<>(List.of("x"), 0, 10, 10L, 1);
        var b = new PageResponse<>(List.of("x"), 0, 20, 10L, 1);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTotalElements() {
        var a = new PageResponse<>(List.of("x"), 0, 10, 10L, 1);
        var b = new PageResponse<>(List.of("x"), 0, 10, 99L, 1);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTotalPages() {
        var a = new PageResponse<>(List.of("x"), 0, 10, 10L, 1);
        var b = new PageResponse<>(List.of("x"), 0, 10, 10L, 5);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_contentNullVsEmpty() {
        var a = new PageResponse<String>(null, 0, 10, 0L, 0);
        var b = new PageResponse<>(List.of(), 0, 10, 0L, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new PageResponse<>(List.of("x"), 0, 10, 1L, 1);

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new PageResponse<>(List.of("hello"), 2, 15, 100L, 7);
        var str = response.toString();

        assertThat(str).contains("hello");
        assertThat(str).contains("2");
        assertThat(str).contains("15");
        assertThat(str).contains("100");
        assertThat(str).contains("7");
    }

    @Test
    void should_containFieldNames_when_toStringCalled() {
        var response = new PageResponse<>(List.of(), 0, 10, 0L, 0);
        var str = response.toString();

        assertThat(str).contains("content");
        assertThat(str).contains("page");
        assertThat(str).contains("size");
        assertThat(str).contains("totalElements");
        assertThat(str).contains("totalPages");
    }

    @Test
    void should_handleNullContentInToString_when_contentIsNull() {
        var response = new PageResponse<String>(null, 0, 10, 0L, 0);
        var str = response.toString();

        assertThat(str).contains("null");
    }
}
