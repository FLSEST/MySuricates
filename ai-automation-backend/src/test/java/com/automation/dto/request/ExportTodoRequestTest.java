package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportTodoRequestTest {

    @Test
    void should_returnProviderAndTarget_when_constructedWithValidValues() {
        ExportTodoRequest request = new ExportTodoRequest("GITHUB", "owner/repo");

        assertThat(request.provider()).isEqualTo("GITHUB");
        assertThat(request.target()).isEqualTo("owner/repo");
    }

    @Test
    void should_allowNullProvider_when_constructedWithNullProvider() {
        ExportTodoRequest request = new ExportTodoRequest(null, "owner/repo");

        assertThat(request.provider()).isNull();
        assertThat(request.target()).isEqualTo("owner/repo");
    }

    @Test
    void should_allowNullTarget_when_constructedWithNullTarget() {
        ExportTodoRequest request = new ExportTodoRequest("GITHUB", null);

        assertThat(request.provider()).isEqualTo("GITHUB");
        assertThat(request.target()).isNull();
    }

    @Test
    void should_allowAllNullFields_when_constructedWithNulls() {
        ExportTodoRequest request = new ExportTodoRequest(null, null);

        assertThat(request.provider()).isNull();
        assertThat(request.target()).isNull();
    }

    @Test
    void should_allowEmptyStrings_when_constructedWithEmptyValues() {
        ExportTodoRequest request = new ExportTodoRequest("", "");

        assertThat(request.provider()).isEmpty();
        assertThat(request.target()).isEmpty();
    }

    @Test
    void should_beEqual_when_valuesAreIdentical() {
        ExportTodoRequest a = new ExportTodoRequest("GITHUB", "owner/repo");
        ExportTodoRequest b = new ExportTodoRequest("GITHUB", "owner/repo");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_valuesDiffer() {
        ExportTodoRequest a = new ExportTodoRequest("GITHUB", "owner/repo");
        ExportTodoRequest b = new ExportTodoRequest("TRELLO", "owner/repo");
        ExportTodoRequest c = new ExportTodoRequest("GITHUB", "other/repo");

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void should_containFieldValues_when_toStringCalled() {
        ExportTodoRequest request = new ExportTodoRequest("GITHUB", "owner/repo");

        assertThat(request.toString())
                .contains("GITHUB")
                .contains("owner/repo");
    }
}
