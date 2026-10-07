package com.automation.service.project.bundle;

import com.automation.dto.bundle.BundleImportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleRequestReaderTest {

    private final BundleRequestReader reader = new BundleRequestReader(new ObjectMapper().findAndRegisterModules());

    @Test
    void readsAValidBody() {
        BundleImportRequest request = reader.read(
                "{\"project\":{\"mode\":\"new\",\"name\":\"Shop\"},\"onConflict\":\"rename\"}", BundleImportRequest.class);

        assertThat(request.project().mode()).isEqualTo("new");
        assertThat(request.project().name()).isEqualTo("Shop");
        assertThat(request.onConflict()).isEqualTo("rename");
        assertThat(request.pathMappings()).isEmpty();
        assertThat(request.bundle()).isNull();
    }

    @Test
    void rejectsMissingOrBlankBody() {
        assertThatThrownBy(() -> reader.read(null, BundleImportRequest.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Request body is missing");
        assertThatThrownBy(() -> reader.read("  \n", BundleImportRequest.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Request body is missing");
    }

    @Test
    void acceptsABodyOfExactlyTheLimitAndRejectsOneCharacterMore() {
        String atLimit = "\"" + "a".repeat(BundleRequestReader.MAX_CHARS - 2) + "\"";

        assertThat(reader.read(atLimit, String.class)).hasSize(BundleRequestReader.MAX_CHARS - 2);
        assertThatThrownBy(() -> reader.read(atLimit + " ", String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bundle request too large (max 5 MB)");
    }

    @Test
    void rejectsInvalidJsonWithTheParserMessage() {
        assertThatThrownBy(() -> reader.read("{not json", BundleImportRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Bundle request is not valid JSON: ");
        assertThatThrownBy(() -> reader.read("{\"onConflict\":[1,2]}", BundleImportRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Bundle request is not valid JSON: ");
    }
}
