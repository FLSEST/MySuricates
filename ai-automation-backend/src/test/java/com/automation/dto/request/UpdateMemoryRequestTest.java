package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateMemoryRequestTest {

    @Test
    void should_setAllFields_when_constructedWithValues() {
        var request = new UpdateMemoryRequest("Memory Title", "Memory Content");

        assertThat(request.title()).isEqualTo("Memory Title");
        assertThat(request.content()).isEqualTo("Memory Content");
    }

    @Test
    void should_allowNullFields_when_constructedWithNulls() {
        var request = new UpdateMemoryRequest(null, null);

        assertThat(request.title()).isNull();
        assertThat(request.content()).isNull();
    }

    @Test
    void should_beEqual_when_fieldValuesMatch() {
        var a = new UpdateMemoryRequest("Title", "Content");
        var b = new UpdateMemoryRequest("Title", "Content");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_containFieldsInToString() {
        var request = new UpdateMemoryRequest("Title", "Content");

        assertThat(request.toString())
                .contains("title=Title")
                .contains("content=Content");
    }
}
