package com.automation.dto.response;

import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProjectResponseTest {

    private static ProjectType anyType() {
        return ProjectType.values()[0];
    }

    private static ProjectStatus anyStatus() {
        return ProjectStatus.values()[0];
    }

    // --- Constructor & accessors ---

    @Test
    void should_returnAllFields_when_constructedWithAllValues() {
        var type = anyType();
        var status = anyStatus();
        var container = mock(ContainerResponse.class);
        var config = Map.<String, Object>of("key", "value", "count", 3);
        var archivedAt = Instant.parse("2024-01-01T00:00:00Z");
        var createdAt = Instant.parse("2024-02-01T00:00:00Z");
        var updatedAt = Instant.parse("2024-03-01T00:00:00Z");

        var response = new ProjectResponse(
                "id-1",
                "name",
                "subtitle",
                "description",
                type,
                "/some/path",
                config,
                "ai-context",
                status,
                container,
                archivedAt,
                createdAt,
                updatedAt
        );

        assertThat(response.id()).isEqualTo("id-1");
        assertThat(response.name()).isEqualTo("name");
        assertThat(response.subtitle()).isEqualTo("subtitle");
        assertThat(response.description()).isEqualTo("description");
        assertThat(response.type()).isEqualTo(type);
        assertThat(response.path()).isEqualTo("/some/path");
        assertThat(response.config()).isEqualTo(config);
        assertThat(response.aiContext()).isEqualTo("ai-context");
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.container()).isSameAs(container);
        assertThat(response.archivedAt()).isEqualTo(archivedAt);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void should_acceptAllNullValues_when_constructed() {
        var response = new ProjectResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.subtitle()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.type()).isNull();
        assertThat(response.path()).isNull();
        assertThat(response.config()).isNull();
        assertThat(response.aiContext()).isNull();
        assertThat(response.status()).isNull();
        assertThat(response.container()).isNull();
        assertThat(response.archivedAt()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void should_returnEmptyConfig_when_constructedWithEmptyMap() {
        var response = new ProjectResponse(
                "id", "name", null, null, anyType(), null,
                Map.of(), null, anyStatus(), null, null, null, null
        );

        assertThat(response.config()).isEmpty();
    }

    @Test
    void should_preserveConfigEntries_when_constructedWithPopulatedMap() {
        var config = Map.<String, Object>of("a", 1, "b", "two", "c", true);

        var response = new ProjectResponse(
                "id", "name", null, null, anyType(), null,
                config, null, anyStatus(), null, null, null, null
        );

        assertThat(response.config()).hasSize(3)
                .containsEntry("a", 1)
                .containsEntry("b", "two")
                .containsEntry("c", true);
    }

    @Test
    void should_holdMockedContainer_when_constructedWithContainer() {
        var container = mock(ContainerResponse.class);

        var response = new ProjectResponse(
                "id", "name", null, null, anyType(), null,
                null, null, anyStatus(), container, null, null, null
        );

        assertThat(response.container()).isSameAs(container);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_allFieldsAreNull() {
        var a = new ProjectResponse(null, null, null, null, null, null, null, null, null, null, null, null, null);
        var b = new ProjectResponse(null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_allFieldsAreSame() {
        var type = anyType();
        var status = anyStatus();
        var container = mock(ContainerResponse.class);
        var config = Map.<String, Object>of("k", "v");
        var t1 = Instant.parse("2024-01-01T00:00:00Z");
        var t2 = Instant.parse("2024-02-01T00:00:00Z");
        var t3 = Instant.parse("2024-03-01T00:00:00Z");

        var a = new ProjectResponse("id", "n", "s", "d", type, "/p", config, "ctx", status, container, t1, t2, t3);
        var b = new ProjectResponse("id", "n", "s", "d", type, "/p", config, "ctx", status, container, t1, t2, t3);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_idDiffers() {
        var a = new ProjectResponse("id-1", "n", null, null, null, null, null, null, null, null, null, null, null);
        var b = new ProjectResponse("id-2", "n", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var a = new ProjectResponse("id", "name-A", null, null, null, null, null, null, null, null, null, null, null);
        var b = new ProjectResponse("id", "name-B", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_pathDiffers() {
        var a = new ProjectResponse("id", "n", null, null, null, "/path-a", null, null, null, null, null, null, null);
        var b = new ProjectResponse("id", "n", null, null, null, "/path-b", null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_containerDiffers() {
        var c1 = mock(ContainerResponse.class);
        var c2 = mock(ContainerResponse.class);

        var a = new ProjectResponse("id", "n", null, null, null, null, null, null, null, c1, null, null, null);
        var b = new ProjectResponse("id", "n", null, null, null, null, null, null, null, c2, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_createdAtDiffers() {
        var t1 = Instant.parse("2024-01-01T00:00:00Z");
        var t2 = Instant.parse("2025-01-01T00:00:00Z");

        var a = new ProjectResponse("id", "n", null, null, null, null, null, null, null, null, null, t1, null);
        var b = new ProjectResponse("id", "n", null, null, null, null, null, null, null, null, null, t2, null);

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_includeFieldNamesInToString() {
        var response = new ProjectResponse(
                "id-x", "name-x", "subtitle-x", "desc-x", anyType(), "/p",
                Map.of(), "ctx-x", anyStatus(), null,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-02-01T00:00:00Z"),
                Instant.parse("2024-03-01T00:00:00Z")
        );

        assertThat(response.toString())
                .contains("id-x")
                .contains("name-x")
                .contains("subtitle-x")
                .contains("desc-x")
                .contains("/p")
                .contains("ctx-x");
    }
}
