package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TodoExportResponseTest {

    @Test
    void should_returnAllFields_when_constructedWithValidValues() {
        TodoExportResponse response = new TodoExportResponse(
                "GITHUB_ISSUE", "owner/repo#42", "https://github.com/owner/repo/issues/42");

        assertThat(response.referenceType()).isEqualTo("GITHUB_ISSUE");
        assertThat(response.referenceId()).isEqualTo("owner/repo#42");
        assertThat(response.url()).isEqualTo("https://github.com/owner/repo/issues/42");
    }

    @Test
    void should_allowNullReferenceType_when_constructedWithNullReferenceType() {
        TodoExportResponse response = new TodoExportResponse(null, "owner/repo#42", "https://example.com");

        assertThat(response.referenceType()).isNull();
        assertThat(response.referenceId()).isEqualTo("owner/repo#42");
        assertThat(response.url()).isEqualTo("https://example.com");
    }

    @Test
    void should_allowNullReferenceId_when_constructedWithNullReferenceId() {
        TodoExportResponse response = new TodoExportResponse("GITHUB_ISSUE", null, "https://example.com");

        assertThat(response.referenceType()).isEqualTo("GITHUB_ISSUE");
        assertThat(response.referenceId()).isNull();
        assertThat(response.url()).isEqualTo("https://example.com");
    }

    @Test
    void should_allowNullUrl_when_constructedWithNullUrl() {
        TodoExportResponse response = new TodoExportResponse("GITHUB_ISSUE", "owner/repo#42", null);

        assertThat(response.referenceType()).isEqualTo("GITHUB_ISSUE");
        assertThat(response.referenceId()).isEqualTo("owner/repo#42");
        assertThat(response.url()).isNull();
    }

    @Test
    void should_allowAllNullFields_when_constructedWithNulls() {
        TodoExportResponse response = new TodoExportResponse(null, null, null);

        assertThat(response.referenceType()).isNull();
        assertThat(response.referenceId()).isNull();
        assertThat(response.url()).isNull();
    }

    @Test
    void should_allowEmptyStrings_when_constructedWithEmptyValues() {
        TodoExportResponse response = new TodoExportResponse("", "", "");

        assertThat(response.referenceType()).isEmpty();
        assertThat(response.referenceId()).isEmpty();
        assertThat(response.url()).isEmpty();
    }

    @Test
    void should_beEqual_when_valuesAreIdentical() {
        TodoExportResponse a = new TodoExportResponse("GITHUB_ISSUE", "owner/repo#42", "https://example.com");
        TodoExportResponse b = new TodoExportResponse("GITHUB_ISSUE", "owner/repo#42", "https://example.com");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_valuesDiffer() {
        TodoExportResponse a = new TodoExportResponse("GITHUB_ISSUE", "owner/repo#42", "https://example.com");
        TodoExportResponse b = new TodoExportResponse("TRELLO_CARD", "owner/repo#42", "https://example.com");
        TodoExportResponse c = new TodoExportResponse("GITHUB_ISSUE", "owner/repo#43", "https://example.com");
        TodoExportResponse d = new TodoExportResponse("GITHUB_ISSUE", "owner/repo#42", "https://other.com");

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
    }

    @Test
    void should_containFieldValues_when_toStringCalled() {
        TodoExportResponse response = new TodoExportResponse(
                "GITHUB_ISSUE", "owner/repo#42", "https://example.com/issue/42");

        assertThat(response.toString())
                .contains("GITHUB_ISSUE")
                .contains("owner/repo#42")
                .contains("https://example.com/issue/42");
    }
}
