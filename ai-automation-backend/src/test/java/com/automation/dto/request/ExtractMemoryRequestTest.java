package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractMemoryRequestTest {

    // --- Record accessors ---

    @Test
    void should_returnRunIds_when_constructedWithList() {
        var runIds = List.of("run-1", "run-2");
        var request = new ExtractMemoryRequest(runIds, "CLAUDE");

        assertThat(request.runIds()).containsExactly("run-1", "run-2");
    }

    @Test
    void should_returnProvider_when_constructedWithValue() {
        var request = new ExtractMemoryRequest(List.of("run-1"), "OPENAI");

        assertThat(request.provider()).isEqualTo("OPENAI");
    }

    @Test
    void should_returnEmptyList_when_constructedWithEmptyList() {
        var request = new ExtractMemoryRequest(Collections.emptyList(), "CLAUDE");

        assertThat(request.runIds()).isEmpty();
    }

    @Test
    void should_returnNull_when_runIdsIsNull() {
        var request = new ExtractMemoryRequest(null, "CLAUDE");

        assertThat(request.runIds()).isNull();
    }

    @Test
    void should_returnNull_when_providerIsNull() {
        var request = new ExtractMemoryRequest(List.of("run-1"), null);

        assertThat(request.provider()).isNull();
    }

    @Test
    void should_allowBothFieldsNull() {
        var request = new ExtractMemoryRequest(null, null);

        assertThat(request.runIds()).isNull();
        assertThat(request.provider()).isNull();
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_sameRunIdsAndProvider() {
        var a = new ExtractMemoryRequest(List.of("run-1", "run-2"), "CLAUDE");
        var b = new ExtractMemoryRequest(List.of("run-1", "run-2"), "CLAUDE");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentRunIds() {
        var a = new ExtractMemoryRequest(List.of("run-1"), "CLAUDE");
        var b = new ExtractMemoryRequest(List.of("run-2"), "CLAUDE");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentProvider() {
        var a = new ExtractMemoryRequest(List.of("run-1"), "CLAUDE");
        var b = new ExtractMemoryRequest(List.of("run-1"), "OPENAI");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new ExtractMemoryRequest(List.of("run-1"), "CLAUDE");

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        var request = new ExtractMemoryRequest(List.of("run-1"), "CLAUDE");

        assertThat(request).isNotEqualTo("not a request");
    }

    // --- toString ---

    @Test
    void should_containRunIds_inToString() {
        var request = new ExtractMemoryRequest(List.of("run-1"), "CLAUDE");

        assertThat(request.toString()).contains("runIds");
        assertThat(request.toString()).contains("run-1");
    }

    @Test
    void should_containProvider_inToString() {
        var request = new ExtractMemoryRequest(List.of("run-1"), "CLAUDE");

        assertThat(request.toString()).contains("provider=CLAUDE");
    }
}
